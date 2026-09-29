package com.kratisai.controlplane.git.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.api.restdto.PullRequestResultDto;
import com.kratisai.controlplane.api.restdto.RemoteRepositoryDto;
import com.kratisai.controlplane.client.GitLabApiClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.util.DefaultUriBuilderFactory;
import org.springframework.web.util.UriBuilderFactory;

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

        try {
            ResponseEntity<String> response =
                    gitLabApiClient.getProjects(baseUriFactory(baseUrl), true, true, 100, token);

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
        String ref = branch != null && !branch.isBlank() ? branch : "HEAD";

        ResponseEntity<String> response =
                gitLabApiClient.getRawFile(baseUriFactory(baseUrl), projectPath, path, ref, token);
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
            Map<String, Object> body = new HashMap<>();
            body.put("source_branch", command.headBranch());
            body.put("target_branch", command.baseBranch());
            String title = command.draft() && !command.title().startsWith("Draft:")
                    ? "Draft: " + command.title()
                    : command.title();
            body.put("title", title);
            body.put("description", command.description());

            ResponseEntity<String> response =
                    gitLabApiClient.createMergeRequest(baseUriFactory(baseUrl), projectPath, body, token);

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

    /** Resolves the authenticated PAT user's username, used as the personal namespace. */
    public String getAuthenticatedUsername(String token, String baseUrl) {
        ResponseEntity<String> response = gitLabApiClient.getUser(baseUriFactory(baseUrl), token);
        String username = parse(response).path("username").asText("");
        if (username.isEmpty()) {
            throw new IllegalStateException("GitLab user response did not include a username");
        }
        return username;
    }

    public long getGroupId(String group, String token, String baseUrl) {
        ResponseEntity<String> response = gitLabApiClient.getGroup(baseUriFactory(baseUrl), group, token);
        long id = parse(response).path("id").asLong(0);
        if (id == 0) {
            throw new IllegalStateException("GitLab group response did not include an id for " + group);
        }
        return id;
    }

    public Optional<RemoteRepositoryDto> findProject(String projectPath, String token, String baseUrl) {
        ResponseEntity<String> response;
        try {
            response = gitLabApiClient.getProject(baseUriFactory(baseUrl), projectPath, token);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 404) {
                return Optional.empty();
            }
            throw e;
        }
        if (response.getStatusCode().value() == 404) {
            return Optional.empty();
        }
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("GitLab project lookup failed with status: " + response.getStatusCode());
        }
        return Optional.of(toRemoteRepositoryDto(parse(response)));
    }

    public boolean projectHasCommits(String projectPath, String token, String baseUrl) {
        ResponseEntity<String> response = gitLabApiClient.getCommits(baseUriFactory(baseUrl), projectPath, 1, token);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("GitLab commit lookup failed with status: " + response.getStatusCode());
        }
        JsonNode commits = parse(response);
        return commits.isArray() && !commits.isEmpty();
    }

    public RemoteRepositoryDto createRepository(
            Long namespaceId, CreateRepositoryCommand command, String token, String baseUrl) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", command.name());
        body.put("path", command.name());
        body.put("visibility", command.visibility().name().toLowerCase(java.util.Locale.ROOT));
        body.put("default_branch", command.defaultBranch());
        body.put("initialize_with_readme", false);
        if (namespaceId != null) {
            body.put("namespace_id", namespaceId);
        }

        ResponseEntity<String> response = gitLabApiClient.createProject(baseUriFactory(baseUrl), body, token);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("GitLab project creation failed with status: " + response.getStatusCode());
        }
        return toRemoteRepositoryDto(parse(response));
    }

    private static UriBuilderFactory baseUriFactory(String baseUrl) {
        return new DefaultUriBuilderFactory(Objects.requireNonNull(baseUrl, "baseUrl"));
    }

    private RemoteRepositoryDto toRemoteRepositoryDto(JsonNode project) {
        String name = project.has("path_with_namespace")
                ? project.get("path_with_namespace").asText()
                : project.path("name").asText("");
        String httpUrl = project.path("http_url_to_repo").asText("");
        String sshUrl = project.path("ssh_url_to_repo").asText("");
        String branch = project.hasNonNull("default_branch")
                ? project.get("default_branch").asText()
                : "main";
        return new RemoteRepositoryDto(name, httpUrl, sshUrl, branch);
    }

    private JsonNode parse(ResponseEntity<String> response) {
        try {
            if (response.getBody() == null) {
                throw new IllegalStateException("Empty response from GitLab API");
            }
            return objectMapper.readTree(response.getBody());
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse GitLab API response: " + e.getMessage(), e);
        }
    }
}
