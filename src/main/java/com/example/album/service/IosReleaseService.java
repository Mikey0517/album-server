package com.example.album.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * iOS 侧没有“上传安装包”这一环：IPA 由 CI 发到 GitHub Release。
 * 这里直接读 GitHub Releases 的最新 ios-* 版本，iPad 一装上就能看到更新，
 * 不用每次发版再改服务器环境变量；网络异常时退回配置里的静态值。
 */
@Service
public class IosReleaseService {

    private static final Logger log = LoggerFactory.getLogger(IosReleaseService.class);
    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;
    private static final String TAG_PREFIX = "ios-";

    private final String repo;
    private final String fallbackUrl;
    private final int fallbackCode;
    private final String fallbackName;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private record Snapshot(IosRelease release, long at) {
    }

    private volatile Snapshot cache;

    public IosReleaseService(
            @Value("${ios.release-repo:}") String repo,
            @Value("${ios.update-url:}") String fallbackUrl,
            @Value("${ios.version-code:0}") int fallbackCode,
            @Value("${ios.version-name:}") String fallbackName) {
        this.repo = repo == null ? "" : repo.trim();
        this.fallbackUrl = fallbackUrl == null ? "" : fallbackUrl.trim();
        this.fallbackCode = fallbackCode;
        this.fallbackName = fallbackName == null ? "" : fallbackName.trim();
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

    private IosRelease fallback() {
        return new IosRelease(fallbackCode, fallbackName, fallbackUrl);
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
            String url = "";
            for (JsonNode asset : root.path("assets")) {
                if (asset.path("name").asText("").endsWith(".ipa")) {
                    url = asset.path("browser_download_url").asText("");
                    break;
                }
            }
            if (url.isEmpty()) {
                url = "https://github.com/" + repo + "/releases/latest/download/AlbumApp.ipa";
            }
            return new IosRelease(code, name, url);
        } catch (Exception e) {
            log.warn("读取 iOS 发布信息异常：{}", e.toString());
            return null;
        }
    }

    public record IosRelease(int versionCode, String versionName, String url) {
    }
}
