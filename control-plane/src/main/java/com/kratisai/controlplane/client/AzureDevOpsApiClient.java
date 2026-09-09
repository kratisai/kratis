package com.kratisai.controlplane.client;

import java.net.URI;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PostExchange;

/**
 * HTTP interface for the Azure DevOps Git REST API.
 *
 * <p>Callers build the full request URI (organization/project are part of the Azure DevOps URL
 * structure) and pass the credentials via the {@code Authorization} header (Basic auth with an
 * empty username and the PAT as password).
 */
public interface AzureDevOpsApiClient {

    @GetExchange
    ResponseEntity<String> getRepositories(URI uri, @RequestHeader("Authorization") String authorization);

    @GetExchange
    ResponseEntity<String> getItem(URI uri, @RequestHeader("Authorization") String authorization);

    @PostExchange
    ResponseEntity<String> createPullRequest(
            URI uri, @RequestBody Map<String, Object> body, @RequestHeader("Authorization") String authorization);
}
