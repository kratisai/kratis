package com.kratisai.controlplane.client;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PostExchange;

/** HTTP interface for the GitHub REST API, rooted at {@code https://api.github.com}. */
public interface GitHubApiClient {

    @GetExchange("/app/installations/{installationId}")
    ResponseEntity<String> getInstallation(
            @PathVariable("installationId") String installationId,
            @RequestHeader("Authorization") String authorization);

    @PostExchange("/app/installations/{installationId}/access_tokens")
    ResponseEntity<String> createInstallationAccessToken(
            @PathVariable("installationId") String installationId,
            @RequestHeader("Authorization") String authorization);

    @GetExchange("/installation/repositories")
    ResponseEntity<String> getInstallationRepositories(
            @RequestParam("per_page") int perPage,
            @RequestParam("page") int page,
            @RequestHeader("Authorization") String authorization);

    @GetExchange("/user/repos")
    ResponseEntity<String> getUserRepositories(
            @RequestParam("per_page") int perPage,
            @RequestParam("page") int page,
            @RequestParam("sort") String sort,
            @RequestParam("affiliation") String affiliation,
            @RequestHeader("Authorization") String authorization);

    @GetExchange("/user")
    ResponseEntity<String> getAuthenticatedUser(@RequestHeader("Authorization") String authorization);

    @GetExchange("/repos/{owner}/{repo}")
    ResponseEntity<String> getRepository(
            @PathVariable("owner") String owner,
            @PathVariable("repo") String repo,
            @RequestHeader("Authorization") String authorization);

    @GetExchange("/repos/{owner}/{repo}/branches")
    ResponseEntity<String> getBranches(
            @PathVariable("owner") String owner,
            @PathVariable("repo") String repo,
            @RequestParam("per_page") int perPage,
            @RequestHeader("Authorization") String authorization);

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

    @PostExchange("/user/repos")
    ResponseEntity<String> createUserRepository(
            @RequestBody Map<String, Object> body, @RequestHeader("Authorization") String authorization);

    @PostExchange("/orgs/{org}/repos")
    ResponseEntity<String> createOrgRepository(
            @PathVariable("org") String org,
            @RequestBody Map<String, Object> body,
            @RequestHeader("Authorization") String authorization);
}
