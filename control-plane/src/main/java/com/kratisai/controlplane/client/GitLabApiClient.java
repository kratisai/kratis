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
 * HTTP interface for the GitLab REST API v4.
 *
 * <p>GitLab can be self-hosted, so each call receives a {@link UriBuilderFactory} for the instance
 * base while the paths stay declarative. The template engine encodes path variables, producing the
 * {@code %2F} required for project namespace paths.
 */
public interface GitLabApiClient {

    @GetExchange("/api/v4/projects")
    ResponseEntity<String> getProjects(
            UriBuilderFactory baseUri,
            @RequestParam("membership") boolean membership,
            @RequestParam("simple") boolean simple,
            @RequestParam("per_page") int perPage,
            @RequestHeader("PRIVATE-TOKEN") String privateToken);

    @GetExchange("/api/v4/user")
    ResponseEntity<String> getUser(UriBuilderFactory baseUri, @RequestHeader("PRIVATE-TOKEN") String privateToken);

    @GetExchange("/api/v4/groups/{group}")
    ResponseEntity<String> getGroup(
            UriBuilderFactory baseUri,
            @PathVariable("group") String group,
            @RequestHeader("PRIVATE-TOKEN") String privateToken);

    @GetExchange("/api/v4/projects/{project}")
    ResponseEntity<String> getProject(
            UriBuilderFactory baseUri,
            @PathVariable("project") String project,
            @RequestHeader("PRIVATE-TOKEN") String privateToken);

    @GetExchange("/api/v4/projects/{project}/repository/commits")
    ResponseEntity<String> getCommits(
            UriBuilderFactory baseUri,
            @PathVariable("project") String project,
            @RequestParam("per_page") int perPage,
            @RequestHeader("PRIVATE-TOKEN") String privateToken);

    @GetExchange("/api/v4/projects/{project}/repository/files/{path}/raw")
    ResponseEntity<String> getRawFile(
            UriBuilderFactory baseUri,
            @PathVariable("project") String project,
            @PathVariable("path") String path,
            @RequestParam("ref") String ref,
            @RequestHeader("PRIVATE-TOKEN") String privateToken);

    @PostExchange("/api/v4/projects/{project}/merge_requests")
    ResponseEntity<String> createMergeRequest(
            UriBuilderFactory baseUri,
            @PathVariable("project") String project,
            @RequestBody Map<String, Object> body,
            @RequestHeader("PRIVATE-TOKEN") String privateToken);

    @PostExchange("/api/v4/projects")
    ResponseEntity<String> createProject(
            UriBuilderFactory baseUri,
            @RequestBody Map<String, Object> body,
            @RequestHeader("PRIVATE-TOKEN") String privateToken);
}
