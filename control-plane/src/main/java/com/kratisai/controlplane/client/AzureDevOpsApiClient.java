package com.kratisai.controlplane.client;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PostExchange;
import org.springframework.web.util.UriBuilderFactory;

/**
 * HTTP interface for the Azure DevOps Git REST API.
 *
 * <p>The organization and any collection path are part of the base URL, which varies per credential,
 * so each call receives a {@link UriBuilderFactory} for the base while the paths stay declarative.
 */
public interface AzureDevOpsApiClient {

    @GetExchange("/_apis/git/repositories")
    ResponseEntity<String> getOrganizationRepositories(
            UriBuilderFactory baseUri,
            @RequestParam("api-version") String apiVersion,
            @RequestHeader("Authorization") String authorization);

    @GetExchange("/{project}/_apis/git/repositories")
    ResponseEntity<String> getProjectRepositories(
            UriBuilderFactory baseUri,
            @PathVariable("project") String project,
            @RequestParam("api-version") String apiVersion,
            @RequestHeader("Authorization") String authorization);

    @GetExchange("/{project}/_apis/git/repositories/{repository}")
    ResponseEntity<String> getRepository(
            UriBuilderFactory baseUri,
            @PathVariable("project") String project,
            @PathVariable("repository") String repository,
            @RequestParam("api-version") String apiVersion,
            @RequestHeader("Authorization") String authorization);

    @GetExchange("/{project}/_apis/git/repositories/{repository}/commits")
    ResponseEntity<String> getCommits(
            UriBuilderFactory baseUri,
            @PathVariable("project") String project,
            @PathVariable("repository") String repository,
            @RequestParam("$top") int top,
            @RequestParam("api-version") String apiVersion,
            @RequestHeader("Authorization") String authorization);

    @GetExchange("/{project}/_apis/git/repositories/{repository}/items")
    ResponseEntity<String> getItem(
            UriBuilderFactory baseUri,
            @PathVariable("project") String project,
            @PathVariable("repository") String repository,
            @RequestParam("path") String path,
            @RequestParam("versionDescriptor.version") String version,
            @RequestParam("includeContent") boolean includeContent,
            @RequestParam("api-version") String apiVersion,
            @RequestHeader("Authorization") String authorization);

    @PostExchange("/{project}/_apis/git/repositories")
    ResponseEntity<String> createRepository(
            UriBuilderFactory baseUri,
            @PathVariable("project") String project,
            @RequestParam("api-version") String apiVersion,
            @RequestBody Map<String, Object> body,
            @RequestHeader("Authorization") String authorization);

    @PostExchange("/{project}/_apis/git/repositories/{repository}/pullrequests")
    ResponseEntity<String> createPullRequest(
            UriBuilderFactory baseUri,
            @PathVariable("project") String project,
            @PathVariable("repository") String repository,
            @RequestParam("api-version") String apiVersion,
            @RequestBody Map<String, Object> body,
            @RequestHeader("Authorization") String authorization);
}
