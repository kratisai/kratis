package com.kratisai.controlplane;

import com.kratisai.controlplane.client.BitbucketApiClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.ResponseEntity;

@TestConfiguration
public class FakeBitbucketApiClientConfig {

    public static class FakeBitbucketApiClient implements BitbucketApiClient {

        private static boolean shouldThrow;
        private static boolean repositoryExists;
        private static boolean repositoryHasCommits;
        private static String mockRepositoriesJson =
                "{\"values\": [{\"full_name\": \"kratisai/bitbucket-core\", \"links\": {\"clone\": ["
                        + "{\"name\": \"https\", \"href\": \"https://bitbucket.org/kratisai/bitbucket-core.git\"},"
                        + "{\"name\": \"ssh\", \"href\": \"git@bitbucket.org:kratisai/bitbucket-core.git\"}]},"
                        + "\"mainbranch\": {\"name\": \"main\"}}]}";
        private static String mockSourceContent = "mock bitbucket file content";

        public static void reset() {
            shouldThrow = false;
            repositoryExists = false;
            repositoryHasCommits = false;
            mockRepositoriesJson = "{\"values\": [{\"full_name\": \"kratisai/bitbucket-core\", \"links\": {\"clone\": ["
                    + "{\"name\": \"https\", \"href\": \"https://bitbucket.org/kratisai/bitbucket-core.git\"},"
                    + "{\"name\": \"ssh\", \"href\": \"git@bitbucket.org:kratisai/bitbucket-core.git\"}]},"
                    + "\"mainbranch\": {\"name\": \"main\"}}]}";
            mockSourceContent = "mock bitbucket file content";
        }

        @Override
        public ResponseEntity<String> getRepositories(String role, int pageLength, int page, String authorization) {
            if (shouldThrow) {
                return ResponseEntity.internalServerError().build();
            }
            return ResponseEntity.ok(mockRepositoriesJson);
        }

        @Override
        public ResponseEntity<String> getWorkspaceRepositories(
                String workspace, String role, int pageLength, int page, String authorization) {
            if (shouldThrow) {
                return ResponseEntity.internalServerError().build();
            }
            return ResponseEntity.ok(mockRepositoriesJson);
        }

        @Override
        public ResponseEntity<String> getRepository(String workspace, String repo, String authorization) {
            if (shouldThrow) {
                return ResponseEntity.internalServerError().build();
            }
            if (!repositoryExists) {
                return ResponseEntity.status(404).body("{\"error\": \"Not Found\"}");
            }
            return ResponseEntity.ok(repositoryJson(workspace, repo));
        }

        @Override
        public ResponseEntity<String> getBranches(String workspace, String repo, int pageLength, String authorization) {
            if (shouldThrow) {
                return ResponseEntity.internalServerError().build();
            }
            return ResponseEntity.ok(
                    repositoryHasCommits ? "{\"values\": [{\"name\": \"main\"}]}" : "{\"values\": []}");
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
        public ResponseEntity<String> createRepository(
                String workspace, String repo, java.util.Map<String, Object> body, String authorization) {
            if (shouldThrow) {
                return ResponseEntity.internalServerError().build();
            }
            return ResponseEntity.status(201).body(repositoryJson(workspace, repo));
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

        private static String repositoryJson(String workspace, String name) {
            return "{\"full_name\": \"" + workspace + "/" + name + "\", \"links\": {\"clone\": ["
                    + "{\"name\": \"https\", \"href\": \"https://bitbucket.org/" + workspace + "/" + name + ".git\"}]},"
                    + "\"mainbranch\": {\"name\": \"main\"}}";
        }
    }

    @Bean
    @Primary
    public BitbucketApiClient fakeBitbucketApiClient() {
        return new FakeBitbucketApiClient();
    }
}
