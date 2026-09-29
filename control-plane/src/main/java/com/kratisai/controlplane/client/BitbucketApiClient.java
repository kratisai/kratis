package com.kratisai.controlplane.client;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PostExchange;

/** HTTP interface for the Bitbucket Cloud REST API v2, rooted at {@code https://api.bitbucket.org/2.0}. */
public interface BitbucketApiClient {

    @GetExchange("/repositories")
    ResponseEntity<String> getRepositories(
            @RequestParam("role") String role,
            @RequestParam("pagelen") int pageLength,
            @RequestParam("page") int page,
            @RequestHeader("Authorization") String authorization);

    @GetExchange("/repositories/{workspace}")
    ResponseEntity<String> getWorkspaceRepositories(
            @PathVariable("workspace") String workspace,
            @RequestParam("role") String role,
            @RequestParam("pagelen") int pageLength,
            @RequestParam("page") int page,
            @RequestHeader("Authorization") String authorization);

    @GetExchange("/repositories/{workspace}/{repo}")
    ResponseEntity<String> getRepository(
            @PathVariable("workspace") String workspace,
            @PathVariable("repo") String repo,
            @RequestHeader("Authorization") String authorization);

    @GetExchange("/repositories/{workspace}/{repo}/refs/branches")
    ResponseEntity<String> getBranches(
            @PathVariable("workspace") String workspace,
            @PathVariable("repo") String repo,
            @RequestParam("pagelen") int pageLength,
            @RequestHeader("Authorization") String authorization);

    @GetExchange("/repositories/{workspace}/{repo}/src/{ref}/{path}")
    ResponseEntity<String> getSource(
            @PathVariable("workspace") String workspace,
            @PathVariable("repo") String repo,
            @PathVariable("ref") String ref,
            @PathVariable("path") String path,
            @RequestHeader("Authorization") String authorization);

    @PostExchange("/repositories/{workspace}/{repo}")
    ResponseEntity<String> createRepository(
            @PathVariable("workspace") String workspace,
            @PathVariable("repo") String repo,
            @RequestBody Map<String, Object> body,
            @RequestHeader("Authorization") String authorization);

    @PostExchange("/repositories/{workspace}/{repo}/pullrequests")
    ResponseEntity<String> createPullRequest(
            @PathVariable("workspace") String workspace,
            @PathVariable("repo") String repo,
            @RequestBody Map<String, Object> body,
            @RequestHeader("Authorization") String authorization);
}
