package com.kratisai.controlplane;

import com.kratisai.controlplane.client.AzureDevOpsApiClient;
import java.net.URI;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.ResponseEntity;

@TestConfiguration
public class FakeAzureDevOpsApiClientConfig {

    public static class FakeAzureDevOpsApiClient implements AzureDevOpsApiClient {

        private static boolean shouldThrow;
        private static String mockRepositoriesJson = "{\"value\": [{\"name\": \"azure-core\", \"id\": \"repo-1\","
                + "\"remoteUrl\": \"https://dev.azure.com/kratisai/project/_git/azure-core\","
                + "\"sshUrl\": \"git@ssh.dev.azure.com:v3/kratisai/project/azure-core\","
                + "\"defaultBranch\": \"refs/heads/main\","
                + "\"project\": {\"name\": \"project\"}}]}";
        private static String mockItemContent = "mock azure file content";

        public static void setShouldThrow(boolean throwError) {
            shouldThrow = throwError;
        }

        public static void setMockRepositoriesJson(String json) {
            mockRepositoriesJson = json;
        }

        public static void setMockItemContent(String content) {
            mockItemContent = content;
        }

        public static void reset() {
            shouldThrow = false;
            mockRepositoriesJson = "{\"value\": [{\"name\": \"azure-core\", \"id\": \"repo-1\","
                    + "\"remoteUrl\": \"https://dev.azure.com/kratisai/project/_git/azure-core\","
                    + "\"sshUrl\": \"git@ssh.dev.azure.com:v3/kratisai/project/azure-core\","
                    + "\"defaultBranch\": \"refs/heads/main\","
                    + "\"project\": {\"name\": \"project\"}}]}";
            mockItemContent = "mock azure file content";
        }

        @Override
        public ResponseEntity<String> getRepositories(URI uri, String authorization) {
            if (shouldThrow) {
                return ResponseEntity.internalServerError().build();
            }
            return ResponseEntity.ok(mockRepositoriesJson);
        }

        @Override
        public ResponseEntity<String> getItem(URI uri, String authorization) {
            if (shouldThrow) {
                return ResponseEntity.internalServerError().build();
            }
            return ResponseEntity.ok(mockItemContent);
        }

        @Override
        public ResponseEntity<String> createPullRequest(
                URI uri, java.util.Map<String, Object> body, String authorization) {
            if (shouldThrow) {
                return ResponseEntity.internalServerError().build();
            }
            return ResponseEntity.ok(
                    "{\"pullRequestId\": 99, \"url\": \"https://dev.azure.com/kratisai/project/_git/azure-core/pullrequest/99\", \"sourceRefName\": \""
                            + body.get("sourceRefName") + "\", \"targetRefName\": \"" + body.get("targetRefName")
                            + "\", \"status\": \"active\"}");
        }
    }

    @Bean
    @Primary
    public AzureDevOpsApiClient fakeAzureDevOpsApiClient() {
        return new FakeAzureDevOpsApiClient();
    }
}
