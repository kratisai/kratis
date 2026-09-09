package com.kratisai.controlplane;

import com.kratisai.controlplane.client.GitLabApiClient;
import java.net.URI;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.ResponseEntity;

@TestConfiguration
public class FakeGitLabApiClientConfig {

    public static class FakeGitLabApiClient implements GitLabApiClient {

        private static boolean shouldThrow;
        private static String mockRepositoriesJson =
                "[{\"path_with_namespace\": \"kratisai/gitlab-core\", \"http_url_to_repo\": \"https://gitlab.com/kratisai/gitlab-core.git\", \"ssh_url_to_repo\": \"git@gitlab.com:kratisai/gitlab-core.git\", \"default_branch\": \"master\"}]";
        private static String mockRawFileContent = "mock gitlab file content";

        public static void setShouldThrow(boolean throwError) {
            shouldThrow = throwError;
        }

        public static void setMockRepositoriesJson(String json) {
            mockRepositoriesJson = json;
        }

        public static void setMockRawFileContent(String content) {
            mockRawFileContent = content;
        }

        public static void reset() {
            shouldThrow = false;
            mockRepositoriesJson =
                    "[{\"path_with_namespace\": \"kratisai/gitlab-core\", \"http_url_to_repo\": \"https://gitlab.com/kratisai/gitlab-core.git\", \"ssh_url_to_repo\": \"git@gitlab.com:kratisai/gitlab-core.git\", \"default_branch\": \"master\"}]";
            mockRawFileContent = "mock gitlab file content";
        }

        @Override
        public ResponseEntity<String> getRepositories(URI uri, String privateToken) {
            if (shouldThrow) {
                return ResponseEntity.internalServerError().build();
            }
            return ResponseEntity.ok(mockRepositoriesJson);
        }

        @Override
        public ResponseEntity<String> getRawFile(URI uri, String privateToken) {
            if (shouldThrow) {
                return ResponseEntity.internalServerError().build();
            }
            return ResponseEntity.ok(mockRawFileContent);
        }

        @Override
        public ResponseEntity<String> createMergeRequest(
                URI uri, java.util.Map<String, Object> body, String privateToken) {
            if (shouldThrow) {
                return ResponseEntity.internalServerError().build();
            }
            return ResponseEntity.ok(
                    "{\"iid\": 10, \"web_url\": \"https://gitlab.com/kratisai/gitlab-core/-/merge_requests/10\", \"source_branch\": \""
                            + body.get("source_branch") + "\", \"target_branch\": \"" + body.get("target_branch")
                            + "\", \"state\": \"opened\"}");
        }
    }

    @Bean
    @Primary
    public GitLabApiClient fakeGitLabApiClient() {
        return new FakeGitLabApiClient();
    }
}
