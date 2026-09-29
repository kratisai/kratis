package com.kratisai.controlplane;

import com.kratisai.controlplane.client.GitHubApiClient;
import com.kratisai.controlplane.client.GitHubContentDto;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.ResponseEntity;

@TestConfiguration
public class FakeGitHubApiClientConfig {

    public static class FakeGitHubApiClient implements GitHubApiClient {

        private volatile boolean shouldThrow;
        private final AtomicInteger createPullRequestInvocations = new AtomicInteger();
        private final AtomicInteger createRepositoryInvocations = new AtomicInteger();
        private String mockInstallationLogin = "fake-org";
        private String mockAccessToken = "ghs_faketoken";
        private String mockRepositoriesJson =
                "{\"repositories\": [{\"name\": \"repo1\", \"clone_url\": \"https://github.com/fake/repo1.git\", \"default_branch\": \"main\"}]}";
        private String mockUserRepositoriesJson =
                "[{\"name\": \"user-repo1\", \"clone_url\": \"https://github.com/fake/user-repo1.git\", \"default_branch\": \"main\"}]";
        private volatile boolean repositoryExists;
        private volatile boolean repositoryHasCommits;

        public void setShouldThrow(boolean throwError) {
            shouldThrow = throwError;
        }

        public int createPullRequestInvocations() {
            return createPullRequestInvocations.get();
        }

        public void reset() {
            shouldThrow = false;
            createPullRequestInvocations.set(0);
            createRepositoryInvocations.set(0);
            repositoryExists = false;
            repositoryHasCommits = false;
            mockInstallationLogin = "fake-org";
            mockAccessToken = "ghs_faketoken";
            mockRepositoriesJson =
                    "{\"repositories\": [{\"name\": \"repo1\", \"clone_url\": \"https://github.com/fake/repo1.git\", \"default_branch\": \"main\"}]}";
            mockUserRepositoriesJson =
                    "[{\"name\": \"user-repo1\", \"clone_url\": \"https://github.com/fake/user-repo1.git\", \"default_branch\": \"main\"}]";
        }

        @Override
        public ResponseEntity<String> getInstallation(String installationId, String authorization) {
            if (shouldThrow) {
                return ResponseEntity.internalServerError().build();
            }
            return ResponseEntity.ok(
                    "{\"account\": {\"login\": \"" + mockInstallationLogin + "\", \"type\": \"Organization\"}}");
        }

        @Override
        public ResponseEntity<String> createInstallationAccessToken(String installationId, String authorization) {
            if (shouldThrow) {
                return ResponseEntity.internalServerError().build();
            }
            return ResponseEntity.ok("{\"token\": \"" + mockAccessToken + "\"}");
        }

        @Override
        public ResponseEntity<String> getInstallationRepositories(int perPage, int page, String authorization) {
            if (shouldThrow) {
                return ResponseEntity.internalServerError().build();
            }
            return ResponseEntity.ok(mockRepositoriesJson);
        }

        @Override
        public ResponseEntity<String> getUserRepositories(
                int perPage, int page, String sort, String affiliation, String authorization) {
            if (shouldThrow) {
                return ResponseEntity.internalServerError().build();
            }
            return ResponseEntity.ok(mockUserRepositoriesJson);
        }

        @Override
        public ResponseEntity<String> getAuthenticatedUser(String authorization) {
            if (shouldThrow) {
                return ResponseEntity.internalServerError().build();
            }
            return ResponseEntity.ok("{\"login\": \"fake-user\", \"type\": \"User\"}");
        }

        @Override
        public ResponseEntity<String> getRepository(String owner, String repo, String authorization) {
            if (shouldThrow) {
                return ResponseEntity.internalServerError().build();
            }
            if (!repositoryExists) {
                return ResponseEntity.status(404).body("{\"message\": \"Not Found\"}");
            }
            return ResponseEntity.ok("{\"name\": \"" + repo + "\", \"full_name\": \"" + owner + "/" + repo + "\","
                    + "\"clone_url\": \"https://github.com/" + owner + "/" + repo
                    + ".git\", \"default_branch\": \"main\"}");
        }

        @Override
        public ResponseEntity<String> getBranches(String owner, String repo, int perPage, String authorization) {
            if (shouldThrow) {
                return ResponseEntity.internalServerError().build();
            }
            return ResponseEntity.ok(repositoryHasCommits ? "[{\"name\": \"main\"}]" : "[]");
        }

        @Override
        public ResponseEntity<String> createUserRepository(java.util.Map<String, Object> body, String authorization) {
            return createRepositoryResponse("fake-user", body);
        }

        @Override
        public ResponseEntity<String> createOrgRepository(
                String org, java.util.Map<String, Object> body, String authorization) {
            return createRepositoryResponse(org, body);
        }

        private ResponseEntity<String> createRepositoryResponse(String owner, java.util.Map<String, Object> body) {
            if (shouldThrow) {
                return ResponseEntity.internalServerError().build();
            }
            createRepositoryInvocations.incrementAndGet();
            return ResponseEntity.status(201)
                    .body("{\"name\": \"" + body.get("name") + "\", \"full_name\": \"" + owner + "/" + body.get("name")
                            + "\", \"clone_url\": \"https://github.com/" + owner + "/" + body.get("name")
                            + ".git\", \"default_branch\": \"main\"}");
        }

        @Override
        public GitHubContentDto getFileContents(
                String owner, String repo, String path, String ref, String authorization) {
            if (shouldThrow) {
                throw new IllegalStateException("Simulated GitHub API failure");
            }
            String name = path.substring(path.lastIndexOf('/') + 1);
            String body = "mock file content";
            String encoded = Base64.getEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8));
            return new GitHubContentDto("file", "base64", body.length(), name, path, encoded, null);
        }

        @Override
        public ResponseEntity<String> createPullRequest(
                String owner, String repo, java.util.Map<String, Object> body, String authorization) {
            if (shouldThrow) {
                return ResponseEntity.internalServerError().build();
            }
            createPullRequestInvocations.incrementAndGet();
            return ResponseEntity.ok("{\"number\": 42, \"html_url\": \"https://github.com/" + owner + "/" + repo
                    + "/pull/42\", \"head\": {\"ref\": \"" + body.get("head") + "\"}, \"base\": {\"ref\": \""
                    + body.get("base") + "\"}, \"state\": \"open\"}");
        }
    }

    @Bean
    @Primary
    public FakeGitHubApiClient fakeGitHubApiClient() {
        return new FakeGitHubApiClient();
    }
}
