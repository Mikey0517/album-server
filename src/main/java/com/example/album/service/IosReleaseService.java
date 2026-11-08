package com.example.album.service;

import com.example.album.config.AppConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Optional;

/**
 * iOS 侧没有“上传安装包”这一环：IPA 由 CI 发到 GitHub Release。这里做三件事：
 * 1) 读 GitHub Releases 的最新 ios-* 版本号，iPad 装上就能看到更新；
 * 2) 把对应的 IPA 镜像到本机 /data/ios 再发给设备；
 * 3) 镜像走 api.github.com 的 asset 接口而不是 releases/download 页面，
 *    因为后者在国内网络经常直接 504，而 asset 接口能正常 302 到 CDN。
 */
@Service
public class IosReleaseService {

    private static final Logger log = LoggerFactory.getLogger(IosReleaseService.class);
    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;
    private static final String TAG_PREFIX = "ios-";
    private static final long MIN_IPA_BYTES = 1024 * 1024;

    private final String repo;
    private final String fallbackUrl;
    private final int fallbackCode;
    private final String fallbackName;
    private final String publicBaseUrl;
    private final AppConfig cfg;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final Object downloadLock = new Object();

    private record Snapshot(IosRelease release, long at) {
    }

    private volatile Snapshot cache;

    public IosReleaseService(
            AppConfig cfg,
            @Value("${ios.release-repo:}") String repo,
            @Value("${ios.update-url:}") String fallbackUrl,
            @Value("${ios.version-code:0}") int fallbackCode,
            @Value("${ios.version-name:}") String fallbackName,
            @Value("${ios.public-base-url:}") String publicBaseUrl) {
        this.cfg = cfg;
        this.repo = repo == null ? "" : repo.trim();
        this.fallbackUrl = fallbackUrl == null ? "" : fallbackUrl.trim();
        this.fallbackCode = fallbackCode;
        this.fallbackName = fallbackName == null ? "" : fallbackName.trim();
        this.publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl.trim().replaceAll("/+$", "");
    }

    /** 当前应该提示用户的 iOS 版本。 */
    public IosRelease current() {
        Snapshot snapshot = cache;
        long now = System.currentTimeMillis();
        if (snapshot != null && now - snapshot.at() < CACHE_TTL_MS) {
            return snapshot.release();
        }
        IosRelease fetched = fetchFromGitHub();
        IosRelease result = fetched != null ? fetched : fallback();
        cache = new Snapshot(result, now);
        return result;
    }

    /** 设备真正去下载的地址：本机镜像，保证不依赖 GitHub 的可用性。 */
    public String mirrorUrl() {
        if (publicBaseUrl.isEmpty()) {
            return current().url();
        }
        return publicBaseUrl + "/api/app/ios/ipa";
    }

    public Path ipaFile(IosRelease release) {
        return cfg.dataDirPath().resolve("ios")
                .resolve("AlbumApp-" + release.versionCode() + ".ipa");
    }

    /** 只看本地有没有现成的镜像，不触发下载。 */
    public Optional<Path> cachedIpa(IosRelease release) {
        Path target = ipaFile(release);
        return isReady(target) ? Optional.of(target) : Optional.empty();
    }

    /** 确保 IPA 已镜像到本地；失败返回 empty，调用方回退到 GitHub 原地址。 */
    public Optional<Path> ensureIpa(IosRelease release) {
        Path target = ipaFile(release);
        if (isReady(target)) {
            return Optional.of(target);
        }
        String url = release.apiAssetUrl() != null && !release.apiAssetUrl().isBlank()
                ? release.apiAssetUrl()
                : release.url();
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        synchronized (downloadLock) {
            if (isReady(target)) {
                return Optional.of(target);
            }
            try {
                Files.createDirectories(target.getParent());
                Path temp = Files.createTempFile(target.getParent(), "ipa-", ".part");
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        // GitHub 的 CDN 在国内只有几十 KB/s，24MB 的包要跑 10 分钟上下，
                        // 超时时间必须给足，否则每次都在最后关头失败。
                        .timeout(Duration.ofMinutes(30))
                        .header("User-Agent", "album-server")
                        .header("Accept", "application/octet-stream")
                        .GET()
                        .build();
                HttpResponse<InputStream> response = http.send(request,
                        HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() / 100 != 2) {
                    log.warn("镜像 iOS 包失败：HTTP {}", response.statusCode());
                    Files.deleteIfExists(temp);
                    return Optional.empty();
                }
                try (InputStream in = response.body()) {
                    Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
                }
                if (Files.size(temp) < MIN_IPA_BYTES) {
                    log.warn("镜像 iOS 包失败：文件过小 {}", Files.size(temp));
                    Files.deleteIfExists(temp);
                    return Optional.empty();
                }
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                log.info("iOS 包已镜像：{} ({} bytes)", target, Files.size(target));
                return Optional.of(target);
            } catch (Exception e) {
                log.warn("镜像 iOS 包异常：{}", e.toString());
                return Optional.empty();
            }
        }
    }

    /** 不阻塞请求线程的镜像预热，给 /ios/ipa 首次未命中时用。 */
    public void ensureIpaAsync() {
        if (prefetching) {
            return;
        }
        prefetching = true;
        Thread worker = new Thread(() -> {
            try {
                ensureIpa(current());
            } finally {
                prefetching = false;
            }
        }, "ios-ipa-prefetch");
        worker.setDaemon(true);
        worker.start();
    }

    private volatile boolean prefetching = false;

    private boolean isReady(Path path) {
        try {
            return Files.exists(path) && Files.size(path) >= MIN_IPA_BYTES;
        } catch (Exception e) {
            return false;
        }
    }

    /** 后台预热：发版后尽早把 IPA 拉到本地，设备点“去更新”时直接下。 */
    @Scheduled(initialDelay = 30_000, fixedDelay = 10 * 60 * 1000)
    public void prefetch() {
        try {
            IosRelease release = current();
            if (release.versionCode() <= 0) {
                return;
            }
            ensureIpa(release);
        } catch (Exception e) {
            log.debug("iOS 包预热失败：{}", e.toString());
        }
    }

    private IosRelease fallback() {
        return new IosRelease(fallbackCode, fallbackName, fallbackUrl, "");
    }

    private IosRelease fetchFromGitHub() {
        if (repo.isEmpty()) {
            return null;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.github.com/repos/" + repo + "/releases/latest"))
                    .timeout(Duration.ofSeconds(8))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "album-server")
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("读取 iOS 发布信息失败：HTTP {}", response.statusCode());
                return null;
            }
            JsonNode root = mapper.readTree(response.body());
            String tag = root.path("tag_name").asText("");
            if (!tag.startsWith(TAG_PREFIX)) {
                return null;
            }
            String rest = tag.substring(TAG_PREFIX.length());
            int dash = rest.lastIndexOf('-');
            if (dash <= 0 || dash == rest.length() - 1) {
                return null;
            }
            String name = rest.substring(0, dash);
            int code;
            try {
                code = Integer.parseInt(rest.substring(dash + 1));
            } catch (NumberFormatException e) {
                return null;
            }
            String downloadUrl = "";
            String assetApiUrl = "";
            for (JsonNode asset : root.path("assets")) {
                if (asset.path("name").asText("").endsWith(".ipa")) {
                    downloadUrl = asset.path("browser_download_url").asText("");
                    String id = asset.path("id").asText("");
                    if (!id.isEmpty()) {
                        assetApiUrl = "https://api.github.com/repos/" + repo
                                + "/releases/assets/" + id;
                    }
                    break;
                }
            }
            if (downloadUrl.isEmpty()) {
                downloadUrl = "https://github.com/" + repo + "/releases/latest/download/AlbumApp.ipa";
            }
            return new IosRelease(code, name, downloadUrl, assetApiUrl);
        } catch (Exception e) {
            log.warn("读取 iOS 发布信息异常：{}", e.toString());
            return null;
        }
    }

    public record IosRelease(int versionCode, String versionName, String url, String apiAssetUrl) {
    }
}
