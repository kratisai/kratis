package com.kratisai.controlplane.client;

import java.net.URI;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PostExchange;

/**
 * HTTP interface for the Bitbucket Cloud REST API v2.
 *
 * <p>Authentication is passed per-call via the {@code Authorization} header (Bearer token).
 */
public interface BitbucketApiClient {

    @GetExchange
    ResponseEntity<String> getRepositories(URI uri, @RequestHeader("Authorization") String authorization);

    @GetExchange("/repositories/{workspace}/{repo}/src/{ref}/{path}")
    ResponseEntity<String> getSource(
            @PathVariable String workspace,
            @PathVariable String repo,
            @PathVariable String ref,
            @PathVariable String path,
            @RequestHeader("Authorization") String authorization);

    @PostExchange("/repositories/{workspace}/{repo}/pullrequests")
    ResponseEntity<String> createPullRequest(
            @PathVariable("workspace") String workspace,
            @PathVariable("repo") String repo,
            @RequestBody Map<String, Object> body,
            @RequestHeader("Authorization") String authorization);
}
