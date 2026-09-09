package com.kratisai.controlplane;

import com.kratisai.controlplane.client.GitHubApiClient;
import com.kratisai.controlplane.client.GitHubContentDto;
import java.net.URI;
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
        private String mockInstallationLogin = "fake-org";
        private String mockAccessToken = "ghs_faketoken";
        private String mockRepositoriesJson =
                "{\"repositories\": [{\"name\": \"repo1\", \"clone_url\": \"https://github.com/fake/repo1.git\", \"default_branch\": \"main\"}]}";
        private String mockUserRepositoriesJson =
                "[{\"name\": \"user-repo1\", \"clone_url\": \"https://github.com/fake/user-repo1.git\", \"default_branch\": \"main\"}]";

        public void setShouldThrow(boolean throwError) {
            shouldThrow = throwError;
        }

        public int createPullRequestInvocations() {
            return createPullRequestInvocations.get();
        }

        public void reset() {
            shouldThrow = false;
            createPullRequestInvocations.set(0);
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
            return ResponseEntity.ok("{\"account\": {\"login\": \"" + mockInstallationLogin + "\"}}");
        }

        @Override
        public ResponseEntity<String> createInstallationAccessToken(String installationId, String authorization) {
            if (shouldThrow) {
                return ResponseEntity.internalServerError().build();
            }
            return ResponseEntity.ok("{\"token\": \"" + mockAccessToken + "\"}");
        }

        @Override
        public ResponseEntity<String> getRepositories(URI uri, String authorization) {
            if (shouldThrow) {
                return ResponseEntity.internalServerError().build();
            }
            if (uri.getPath().startsWith("/installation/repositories")) {
                return ResponseEntity.ok(mockRepositoriesJson);
            }
            return ResponseEntity.ok(mockUserRepositoriesJson);
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
