package com.kratisai.controlplane;

import com.kratisai.controlplane.client.AzureDevOpsApiClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.ResponseEntity;
import org.springframework.web.util.UriBuilderFactory;

@TestConfiguration
public class FakeAzureDevOpsApiClientConfig {

    public static class FakeAzureDevOpsApiClient implements AzureDevOpsApiClient {

        private static boolean shouldThrow;
        private static boolean repositoryExists;
        private static boolean repositoryHasCommits;
        private static String mockRepositoriesJson = "{\"value\": [{\"name\": \"azure-core\", \"id\": \"repo-1\","
                + "\"remoteUrl\": \"https://dev.azure.com/kratisai/project/_git/azure-core\","
                + "\"sshUrl\": \"git@ssh.dev.azure.com:v3/kratisai/project/azure-core\","
                + "\"defaultBranch\": \"refs/heads/main\","
                + "\"project\": {\"name\": \"project\"}}]}";
        private static String mockItemContent = "mock azure file content";

        public static void reset() {
            shouldThrow = false;
            repositoryExists = false;
            repositoryHasCommits = false;
            mockRepositoriesJson = "{\"value\": [{\"name\": \"azure-core\", \"id\": \"repo-1\","
                    + "\"remoteUrl\": \"https://dev.azure.com/kratisai/project/_git/azure-core\","
                    + "\"sshUrl\": \"git@ssh.dev.azure.com:v3/kratisai/project/azure-core\","
                    + "\"defaultBranch\": \"refs/heads/main\","
                    + "\"project\": {\"name\": \"project\"}}]}";
            mockItemContent = "mock azure file content";
        }

        @Override
        public ResponseEntity<String> getOrganizationRepositories(
                UriBuilderFactory baseUri, String apiVersion, String authorization) {
            if (shouldThrow) {
                return ResponseEntity.internalServerError().build();
            }
            return ResponseEntity.ok(mockRepositoriesJson);
        }

        @Override
        public ResponseEntity<String> getProjectRepositories(
                UriBuilderFactory baseUri, String project, String apiVersion, String authorization) {
            if (shouldThrow) {
                return ResponseEntity.internalServerError().build();
            }
            return ResponseEntity.ok(mockRepositoriesJson);
        }

        @Override
        public ResponseEntity<String> getRepository(
                UriBuilderFactory baseUri, String project, String repository, String apiVersion, String authorization) {
            if (shouldThrow) {
                return ResponseEntity.internalServerError().build();
            }
            if (!repositoryExists) {
                return ResponseEntity.status(404).body("{\"message\": \"Not Found\"}");
            }
            return ResponseEntity.ok(repositoryJson(repository));
        }

        @Override
        public ResponseEntity<String> getCommits(
                UriBuilderFactory baseUri,
                String project,
                String repository,
                int top,
                String apiVersion,
                String authorization) {
            if (shouldThrow) {
                return ResponseEntity.internalServerError().build();
            }
            return ResponseEntity.ok("{\"count\": " + (repositoryHasCommits ? 1 : 0) + ", \"value\": []}");
        }

        @Override
        public ResponseEntity<String> getItem(
                UriBuilderFactory baseUri,
                String project,
                String repository,
                String path,
                String version,
                boolean includeContent,
                String apiVersion,
                String authorization) {
            if (shouldThrow) {
                return ResponseEntity.internalServerError().build();
            }
            return ResponseEntity.ok(mockItemContent);
        }

        @Override
        public ResponseEntity<String> createRepository(
                UriBuilderFactory baseUri,
                String project,
                String apiVersion,
                java.util.Map<String, Object> body,
                String authorization) {
            if (shouldThrow) {
                return ResponseEntity.internalServerError().build();
            }
            return ResponseEntity.status(201).body(repositoryJson(String.valueOf(body.get("name"))));
        }

        @Override
        public ResponseEntity<String> createPullRequest(
                UriBuilderFactory baseUri,
                String project,
                String repository,
                String apiVersion,
                java.util.Map<String, Object> body,
                String authorization) {
            if (shouldThrow) {
                return ResponseEntity.internalServerError().build();
            }
            return ResponseEntity.ok(
                    "{\"pullRequestId\": 99, \"url\": \"https://dev.azure.com/kratisai/project/_git/azure-core/pullrequest/99\", \"sourceRefName\": \""
                            + body.get("sourceRefName") + "\", \"targetRefName\": \"" + body.get("targetRefName")
                            + "\", \"status\": \"active\"}");
        }

        private static String repositoryJson(String name) {
            return "{\"name\": \"" + name + "\", \"remoteUrl\": \"https://dev.azure.com/kratisai/project/_git/" + name
                    + "\", \"defaultBranch\": \"refs/heads/main\", \"project\": {\"name\": \"project\"}}";
        }
    }

    @Bean
    @Primary
    public AzureDevOpsApiClient fakeAzureDevOpsApiClient() {
        return new FakeAzureDevOpsApiClient();
    }
}
