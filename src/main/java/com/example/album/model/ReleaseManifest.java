package com.example.album.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * data/releases/metadata.json 的清单结构。
 * currentVersionCode 是"当前生效版本"，可以小于最大 versionCode（软回滚）。
 */
public record ReleaseManifest(
        @JsonProperty("currentVersionCode") int currentVersionCode,
        @JsonProperty("releases") List<AppRelease> releases
) {

    public AppRelease current() {
        return releases.stream()
                .filter(r -> r.versionCode() == currentVersionCode)
                .findFirst()
                .orElse(null);
    }
}
