package com.kratisai.controlplane.client;

import java.net.URI;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PostExchange;

public interface GitLabApiClient {

    @GetExchange
    ResponseEntity<String> getRepositories(URI uri, @RequestHeader("PRIVATE-TOKEN") String privateToken);

    /**
     * Fetches a single file via the Repository Files API.
     *
     * <p>Returns the raw file content (not base64-encoded) when the request succeeds.
     */
    @GetExchange
    ResponseEntity<String> getRawFile(URI uri, @RequestHeader("PRIVATE-TOKEN") String privateToken);

    @PostExchange
    ResponseEntity<String> createMergeRequest(
            URI uri, @RequestBody Map<String, Object> body, @RequestHeader("PRIVATE-TOKEN") String privateToken);
}
