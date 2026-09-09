package com.kratisai.controlplane.client;

import java.net.URI;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PostExchange;

public interface GitHubApiClient {

    @GetExchange("/app/installations/{installationId}")
    ResponseEntity<String> getInstallation(
            @PathVariable("installationId") String installationId,
            @RequestHeader("Authorization") String authorization);

    @PostExchange("/app/installations/{installationId}/access_tokens")
    ResponseEntity<String> createInstallationAccessToken(
            @PathVariable("installationId") String installationId,
            @RequestHeader("Authorization") String authorization);

    /**
     * Lists repositories for the given request URI. Callers supply either the installation
     * repositories URI (GitHub App) or the user repositories URI (PAT); both paginate via the
     * {@code Link} header.
     */
    @GetExchange
    ResponseEntity<String> getRepositories(URI uri, @RequestHeader("Authorization") String authorization);

    /**
     * Fetches a single file via the Contents API.
     *
     * <p>GitHub returns a JSON object with base64-encoded {@code content} when the path is a file.
     * Directory paths return a JSON array and will fail deserialization — callers must request files.
     */
    @GetExchange("/repos/{owner}/{repo}/contents/{path}")
    GitHubContentDto getFileContents(
            @PathVariable("owner") String owner,
            @PathVariable("repo") String repo,
            @PathVariable("path") String path,
            @RequestParam("ref") String ref,
            @RequestHeader("Authorization") String authorization);

    @PostExchange("/repos/{owner}/{repo}/pulls")
    ResponseEntity<String> createPullRequest(
            @PathVariable("owner") String owner,
            @PathVariable("repo") String repo,
            @RequestBody Map<String, Object> body,
            @RequestHeader("Authorization") String authorization);
}
