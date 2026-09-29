package com.kratisai.controlplane;

import com.kratisai.controlplane.client.GitLabApiClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.ResponseEntity;
import org.springframework.web.util.UriBuilderFactory;

@TestConfiguration
public class FakeGitLabApiClientConfig {

    public static class FakeGitLabApiClient implements GitLabApiClient {

        private static boolean shouldThrow;
        private static boolean projectExists;
        private static boolean projectHasCommits;
        private static String mockRepositoriesJson =
                "[{\"path_with_namespace\": \"kratisai/gitlab-core\", \"http_url_to_repo\": \"https://gitlab.com/kratisai/gitlab-core.git\", \"ssh_url_to_repo\": \"git@gitlab.com:kratisai/gitlab-core.git\", \"default_branch\": \"master\"}]";
        private static String mockRawFileContent = "mock gitlab file content";

        public static void reset() {
            shouldThrow = false;
            projectExists = false;
            projectHasCommits = false;
            mockRepositoriesJson =
                    "[{\"path_with_namespace\": \"kratisai/gitlab-core\", \"http_url_to_repo\": \"https://gitlab.com/kratisai/gitlab-core.git\", \"ssh_url_to_repo\": \"git@gitlab.com:kratisai/gitlab-core.git\", \"default_branch\": \"master\"}]";
            mockRawFileContent = "mock gitlab file content";
        }

        @Override
        public ResponseEntity<String> getProjects(
                UriBuilderFactory baseUri, boolean membership, boolean simple, int perPage, String privateToken) {
            if (shouldThrow) {
                return ResponseEntity.internalServerError().build();
            }
            return ResponseEntity.ok(mockRepositoriesJson);
        }

        @Override
        public ResponseEntity<String> getUser(UriBuilderFactory baseUri, String privateToken) {
            if (shouldThrow) {
                return ResponseEntity.internalServerError().build();
            }
            return ResponseEntity.ok("{\"username\": \"fake-user\"}");
        }

        @Override
        public ResponseEntity<String> getGroup(UriBuilderFactory baseUri, String group, String privateToken) {
            if (shouldThrow) {
                return ResponseEntity.internalServerError().build();
            }
            return ResponseEntity.ok("{\"id\": 77, \"path\": \"fake-group\"}");
        }

        @Override
        public ResponseEntity<String> getProject(UriBuilderFactory baseUri, String project, String privateToken) {
            if (shouldThrow) {
                return ResponseEntity.internalServerError().build();
            }
            if (!projectExists) {
                return ResponseEntity.status(404).body("{\"message\": \"404 Project Not Found\"}");
            }
            return ResponseEntity.ok("{\"path_with_namespace\": \"" + project + "\","
                    + "\"http_url_to_repo\": \"https://gitlab.com/" + project
                    + ".git\", \"default_branch\": \"main\"}");
        }

        @Override
        public ResponseEntity<String> getCommits(
                UriBuilderFactory baseUri, String project, int perPage, String privateToken) {
            if (shouldThrow) {
                return ResponseEntity.internalServerError().build();
            }
            return ResponseEntity.ok(projectHasCommits ? "[{\"id\": \"abc\"}]" : "[]");
        }

        @Override
        public ResponseEntity<String> getRawFile(
                UriBuilderFactory baseUri, String project, String path, String ref, String privateToken) {
            if (shouldThrow) {
                return ResponseEntity.internalServerError().build();
            }
            return ResponseEntity.ok(mockRawFileContent);
        }

        @Override
        public ResponseEntity<String> createMergeRequest(
                UriBuilderFactory baseUri, String project, java.util.Map<String, Object> body, String privateToken) {
            if (shouldThrow) {
                return ResponseEntity.internalServerError().build();
            }
            return ResponseEntity.ok(
                    "{\"iid\": 10, \"web_url\": \"https://gitlab.com/kratisai/gitlab-core/-/merge_requests/10\", \"source_branch\": \""
                            + body.get("source_branch") + "\", \"target_branch\": \"" + body.get("target_branch")
                            + "\", \"state\": \"opened\"}");
        }

        @Override
        public ResponseEntity<String> createProject(
                UriBuilderFactory baseUri, java.util.Map<String, Object> body, String privateToken) {
            if (shouldThrow) {
                return ResponseEntity.internalServerError().build();
            }
            String namespace = body.containsKey("namespace_id") ? "fake-group" : "fake-user";
            return ResponseEntity.status(201)
                    .body("{\"path_with_namespace\": \"" + namespace + "/" + body.get("name")
                            + "\", \"http_url_to_repo\": \"https://gitlab.com/" + namespace + "/" + body.get("name")
                            + ".git\", \"default_branch\": \"main\"}");
        }
    }

    @Bean
    @Primary
    public GitLabApiClient fakeGitLabApiClient() {
        return new FakeGitLabApiClient();
    }
}
