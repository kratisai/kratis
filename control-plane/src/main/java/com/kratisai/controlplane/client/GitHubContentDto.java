package com.kratisai.controlplane.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * GitHub Contents API response for a single file resource.
 *
 * @see <a href="https://docs.github.com/en/rest/repos/contents#get-repository-content">Get repository content</a>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubContentDto(
        String type,
        String encoding,
        int size,
        String name,
        String path,
        String content,
        @JsonProperty("download_url") String downloadUrl) {}
