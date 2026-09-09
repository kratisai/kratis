package com.kratisai.controlplane;

import com.kratisai.controlplane.client.BitbucketApiClient;
import java.net.URI;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.ResponseEntity;

@TestConfiguration
public class FakeBitbucketApiClientConfig {

    public static class FakeBitbucketApiClient implements BitbucketApiClient {

        private static boolean shouldThrow;
        private static String mockRepositoriesJson =
                "{\"values\": [{\"full_name\": \"kratisai/bitbucket-core\", \"links\": {\"clone\": ["
                        + "{\"name\": \"https\", \"href\": \"https://bitbucket.org/kratisai/bitbucket-core.git\"},"
                        + "{\"name\": \"ssh\", \"href\": \"git@bitbucket.org:kratisai/bitbucket-core.git\"}]},"
                        + "\"mainbranch\": {\"name\": \"main\"}}]}";
        private static String mockSourceContent = "mock bitbucket file content";

        public static void setShouldThrow(boolean throwError) {
            shouldThrow = throwError;
        }

        public static void setMockRepositoriesJson(String json) {
            mockRepositoriesJson = json;
        }

        public static void setMockSourceContent(String content) {
            mockSourceContent = content;
        }

        public static void reset() {
            shouldThrow = false;
            mockRepositoriesJson = "{\"values\": [{\"full_name\": \"kratisai/bitbucket-core\", \"links\": {\"clone\": ["
                    + "{\"name\": \"https\", \"href\": \"https://bitbucket.org/kratisai/bitbucket-core.git\"},"
                    + "{\"name\": \"ssh\", \"href\": \"git@bitbucket.org:kratisai/bitbucket-core.git\"}]},"
                    + "\"mainbranch\": {\"name\": \"main\"}}]}";
            mockSourceContent = "mock bitbucket file content";
        }

        @Override
        public ResponseEntity<String> getRepositories(URI uri, String authorization) {
            if (shouldThrow) {
                return ResponseEntity.internalServerError().build();
            }
            return ResponseEntity.ok(mockRepositoriesJson);
        }

        @Override
        public ResponseEntity<String> getSource(
                String workspace, String repo, String ref, String path, String authorization) {
            if (shouldThrow) {
                return ResponseEntity.internalServerError().build();
            }
            return ResponseEntity.ok(mockSourceContent);
        }

        @Override
        public ResponseEntity<String> createPullRequest(
                String workspace, String repo, java.util.Map<String, Object> body, String authorization) {
            if (shouldThrow) {
                return ResponseEntity.internalServerError().build();
            }
            return ResponseEntity.ok(
                    "{\"id\": 5, \"links\": {\"html\": {\"href\": \"https://bitbucket.org/" + workspace + "/" + repo
                            + "/pull-requests/5\"}}, \"source\": {\"branch\": {\"name\": \"feature\"}}, \"destination\": {\"branch\": {\"name\": \"main\"}}, \"state\": \"OPEN\"}");
        }
    }

    @Bean
    @Primary
    public BitbucketApiClient fakeBitbucketApiClient() {
        return new FakeBitbucketApiClient();
    }
}
