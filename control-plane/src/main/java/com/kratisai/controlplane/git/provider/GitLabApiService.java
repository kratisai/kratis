package com.kratisai.controlplane.git.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.api.restdto.PullRequestResultDto;
import com.kratisai.controlplane.api.restdto.RemoteRepositoryDto;
import com.kratisai.controlplane.client.GitLabApiClient;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

@Service
public class GitLabApiService {

    private static final Logger logger = LoggerFactory.getLogger(GitLabApiService.class);
    private static final int MAX_FILE_SIZE_BYTES = 1_000_000;
    private static final Pattern GITLAB_URL_PATTERN =
            Pattern.compile("(?:https?://|git@)([^:/]+)[:/](.+?)(?:\\.git)?$");

    private final GitLabApiClient gitLabApiClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GitLabApiService(GitLabApiClient gitLabApiClient) {
        this.gitLabApiClient = gitLabApiClient;
    }

    public List<RemoteRepositoryDto> listAvailableRepositories(String token, String baseUrl) {
        if (token == null || token.isEmpty()) {
            return List.of();
        }

        String url = baseUrl + "/api/v4/projects?membership=true&simple=true&per_page=100";

        try {
            ResponseEntity<String> response = gitLabApiClient.getRepositories(java.net.URI.create(url), token);

            JsonNode root = objectMapper.readTree(response.getBody());
            List<RemoteRepositoryDto> repos = new ArrayList<>();

            if (root.isArray()) {
                for (JsonNode project : root) {
                    String name = project.has("path_with_namespace")
                            ? project.get("path_with_namespace").asText()
                            : project.get("name").asText();
                    String httpUrl = project.has("http_url_to_repo")
                            ? project.get("http_url_to_repo").asText()
                            : "";
                    String sshUrl = project.has("ssh_url_to_repo")
                            ? project.get("ssh_url_to_repo").asText()
                            : "";
                    String branch = project.has("default_branch")
                                    && !project.get("default_branch").isNull()
                            ? project.get("default_branch").asText()
                            : "main";
                    repos.add(new RemoteRepositoryDto(name, httpUrl, sshUrl, branch));
                }
            }
            return repos;
        } catch (Exception e) {
            logger.error("Failed to fetch repositories from GitLab API", e);
            throw new RuntimeException("Failed to fetch available GitLab repositories: " + e.getMessage(), e);
        }
    }

    /**
     * Reads a single file via the Repository Files API ({@code raw} endpoint). The project is
     * identified by its URL-encoded namespace path (e.g. {@code group%2Fsubgroup%2Frepo}).
     *
     * @param projectPath the full namespace path of the project
     * @param path the file path within the repository
     * @param branch the ref (branch or commit) to read from
     * @param token a GitLab PAT with {@code read_api}/{@code read_repository} scope
     * @param baseUrl the GitLab instance base URL (e.g. {@code https://gitlab.com})
     */
    public String readFile(String projectPath, String path, String branch, String token, String baseUrl) {
        if (token == null || token.isEmpty()) {
            throw new IllegalStateException("GitLab file read requires a token, but no token was provided");
        }
        String encodedProject = URLEncoder.encode(projectPath, StandardCharsets.UTF_8);
        String encodedPath = URLEncoder.encode(path, StandardCharsets.UTF_8);
        String ref = branch != null && !branch.isBlank() ? branch : "HEAD";
        String url = baseUrl + "/api/v4/projects/" + encodedProject + "/repository/files/" + encodedPath + "/raw?ref="
                + URLEncoder.encode(ref, StandardCharsets.UTF_8);

        ResponseEntity<String> response = gitLabApiClient.getRawFile(URI.create(url), token);
        String body = response.getBody();
        if (body == null) {
            throw new IllegalStateException("Empty response from GitLab raw file API for " + path);
        }
        if (body.getBytes(StandardCharsets.UTF_8).length > MAX_FILE_SIZE_BYTES) {
            throw new IllegalStateException("File too large (" + body.getBytes(StandardCharsets.UTF_8).length
                    + " bytes). Maximum is " + MAX_FILE_SIZE_BYTES);
        }
        return body;
    }

    public PullRequestResultDto createPullRequest(
            String projectPath, CreatePullRequestCommand command, String token, String baseUrl) {
        if (token == null || token.isEmpty()) {
            throw new IllegalStateException("GitLab PR creation requires a token, but no token was provided");
        }
        try {
            String encodedProject = URLEncoder.encode(projectPath, StandardCharsets.UTF_8);
            String url = baseUrl + "/api/v4/projects/" + encodedProject + "/merge_requests";

            Map<String, Object> body = new HashMap<>();
            body.put("source_branch", command.headBranch());
            body.put("target_branch", command.baseBranch());
            String title = command.draft() && !command.title().startsWith("Draft:")
                    ? "Draft: " + command.title()
                    : command.title();
            body.put("title", title);
            body.put("description", command.description());

            ResponseEntity<String> response = gitLabApiClient.createMergeRequest(URI.create(url), body, token);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new IllegalStateException("GitLab MR creation failed with status: " + response.getStatusCode());
            }

            JsonNode root = objectMapper.readTree(response.getBody());
            long prNumber = root.path("iid").asLong();
            String prUrl = root.path("web_url").asText("");
            String headBranch = root.path("source_branch").asText(command.headBranch());
            String baseBranch = root.path("target_branch").asText(command.baseBranch());

            return new PullRequestResultDto(prNumber, prUrl, headBranch, baseBranch);
        } catch (Exception e) {
            logger.error("Failed to create GitLab merge request for {}", projectPath, e);
            throw new RuntimeException("Failed to create GitLab merge request: " + e.getMessage(), e);
        }
    }

    /**
     * Extracts the project namespace path from a GitLab repository URL, e.g.
     * {@code https://gitlab.com/group/subgroup/repo.git} → {@code group/subgroup/repo}.
     */
    public static String extractProjectPath(String repoUrl) {
        Matcher matcher = GITLAB_URL_PATTERN.matcher(Objects.requireNonNull(repoUrl, "repoUrl"));
        if (matcher.find()) {
            return matcher.group(2);
        }
        throw new IllegalArgumentException("Unable to extract project path from repository URL: " + repoUrl);
    }

    /**
     * Extracts the GitLab instance base URL from a repository URL, e.g.
     * {@code https://gitlab.com/group/repo.git} → {@code https://gitlab.com} and
     * {@code git@gitlab.mycompany.com:group/repo.git} → {@code https://gitlab.mycompany.com}.
     */
    public static String extractBaseUrl(String repoUrl) {
        Matcher matcher = GITLAB_URL_PATTERN.matcher(Objects.requireNonNull(repoUrl, "repoUrl"));
        if (matcher.find()) {
            return "https://" + matcher.group(1);
        }
        throw new IllegalArgumentException("Unable to extract base URL from repository URL: " + repoUrl);
    }
}
