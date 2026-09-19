package com.kratisai.controlplane.git.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.api.restdto.PullRequestResultDto;
import com.kratisai.controlplane.api.restdto.RemoteRepositoryDto;
import com.kratisai.controlplane.client.BitbucketApiClient;
import com.kratisai.controlplane.model.RepositoryVisibility;
import java.net.URI;
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

@Service
public class BitbucketApiService {

    private static final Logger logger = LoggerFactory.getLogger(BitbucketApiService.class);
    private static final int MAX_FILE_SIZE_BYTES = 1_000_000;
    private static final Pattern BITBUCKET_URL_PATTERN =
            Pattern.compile("(?:https?://|git@)bitbucket\\.org[:/]([^/]+)/([^/.]+)(?:\\.git)?$");

    private final BitbucketApiClient bitbucketApiClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public BitbucketApiService(BitbucketApiClient bitbucketApiClient) {
        this.bitbucketApiClient = bitbucketApiClient;
    }

    /**
     * Lists repositories the authenticated user is a member of, optionally scoped to a workspace.
     * The Bitbucket API pages results via its {@code next} field.
     */
    public List<RemoteRepositoryDto> listRepositories(String workspace, String token) {
        requireToken(token);
        List<RemoteRepositoryDto> repos = new ArrayList<>();
        boolean scoped = workspace != null && !workspace.isBlank();

        try {
            Integer page = 1;
            while (page != null) {
                ResponseEntity<String> response = scoped
                        ? bitbucketApiClient.getWorkspaceRepositories(workspace, "member", 100, page, bearer(token))
                        : bitbucketApiClient.getRepositories("member", 100, page, bearer(token));
                JsonNode root = objectMapper.readTree(response.getBody());
                JsonNode values = root.get("values");
                if (values != null && values.isArray()) {
                    for (JsonNode repo : values) {
                        repos.add(toRemoteRepositoryDto(repo));
                    }
                }
                page = root.hasNonNull("next")
                        ? extractPageNumber(root.get("next").asText())
                        : null;
            }
            return repos;
        } catch (Exception e) {
            logger.error("Failed to fetch repositories from Bitbucket API", e);
            throw new RuntimeException("Failed to fetch available Bitbucket repositories: " + e.getMessage(), e);
        }
    }

    /** The Bitbucket Source API returns the raw file content as the response body. */
    public String readFile(String workspace, String repoSlug, String path, String branch, String token) {
        requireToken(token);
        String ref = branch != null && !branch.isBlank() ? branch : "main";
        ResponseEntity<String> response = bitbucketApiClient.getSource(workspace, repoSlug, ref, path, bearer(token));
        String body = response.getBody();
        if (body == null) {
            throw new IllegalStateException("Empty response from Bitbucket Source API for " + path);
        }
        if (body.getBytes(StandardCharsets.UTF_8).length > MAX_FILE_SIZE_BYTES) {
            throw new IllegalStateException("File too large (" + body.getBytes(StandardCharsets.UTF_8).length
                    + " bytes). Maximum is " + MAX_FILE_SIZE_BYTES);
        }
        return body;
    }

    public static String extractWorkspace(String repoUrl) {
        Matcher matcher = BITBUCKET_URL_PATTERN.matcher(Objects.requireNonNull(repoUrl, "repoUrl"));
        if (matcher.find()) {
            return matcher.group(1);
        }
        throw new IllegalArgumentException("Unable to extract workspace from repository URL: " + repoUrl);
    }

    public static String extractRepoSlug(String repoUrl) {
        Matcher matcher = BITBUCKET_URL_PATTERN.matcher(Objects.requireNonNull(repoUrl, "repoUrl"));
        if (matcher.find()) {
            return matcher.group(2);
        }
        throw new IllegalArgumentException("Unable to extract repo slug from repository URL: " + repoUrl);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static void requireToken(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("Bitbucket access requires a token, but no token was provided");
        }
    }

    private RemoteRepositoryDto toRemoteRepositoryDto(JsonNode repo) {
        String fullName = repo.path("full_name").asText("");
        String name = fullName.isEmpty() ? repo.path("name").asText("") : fullName;
        String httpsUrl = "";
        String sshUrl = "";
        JsonNode clones = repo.path("links").path("clone");
        if (clones.isArray()) {
            for (JsonNode clone : clones) {
                String cloneName = clone.path("name").asText("");
                String href = clone.path("href").asText("");
                if ("https".equals(cloneName)) {
                    httpsUrl = href;
                } else if ("ssh".equals(cloneName)) {
                    sshUrl = href;
                }
            }
        }
        String branch = repo.path("mainbranch").hasNonNull("name")
                ? repo.path("mainbranch").path("name").asText()
                : "main";
        return new RemoteRepositoryDto(name, httpsUrl, sshUrl, branch);
    }

    public PullRequestResultDto createPullRequest(
            String workspace, String repoSlug, CreatePullRequestCommand command, String token) {
        requireToken(token);
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("title", command.title());
            body.put("description", command.description());
            body.put("source", Map.of("branch", Map.of("name", command.headBranch())));
            body.put("destination", Map.of("branch", Map.of("name", command.baseBranch())));

            ResponseEntity<String> response =
                    bitbucketApiClient.createPullRequest(workspace, repoSlug, body, bearer(token));

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new IllegalStateException(
                        "Bitbucket PR creation failed with status: " + response.getStatusCode());
            }

            JsonNode root = objectMapper.readTree(response.getBody());
            long prNumber = root.path("id").asLong();
            String prUrl = root.path("links").path("html").path("href").asText("");
            String headBranch = root.path("source").path("branch").path("name").asText(command.headBranch());
            String baseBranch =
                    root.path("destination").path("branch").path("name").asText(command.baseBranch());

            return new PullRequestResultDto(prNumber, prUrl, headBranch, baseBranch);
        } catch (Exception e) {
            logger.error("Failed to create Bitbucket pull request for {}/{}", workspace, repoSlug, e);
            throw new RuntimeException("Failed to create Bitbucket pull request: " + e.getMessage(), e);
        }
    }

    public Optional<RemoteRepositoryDto> findRepository(String workspace, String repoSlug, String token) {
        requireToken(token);
        ResponseEntity<String> response = bitbucketApiClient.getRepository(workspace, repoSlug, bearer(token));
        if (response.getStatusCode().value() == 404) {
            return Optional.empty();
        }
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException(
                    "Bitbucket repository lookup failed with status: " + response.getStatusCode());
        }
        return Optional.of(toRemoteRepositoryDto(parse(response)));
    }

    public boolean repositoryHasCommits(String workspace, String repoSlug, String token) {
        requireToken(token);
        ResponseEntity<String> response = bitbucketApiClient.getBranches(workspace, repoSlug, 1, bearer(token));
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("Bitbucket branch lookup failed with status: " + response.getStatusCode());
        }
        JsonNode values = parse(response).path("values");
        return values.isArray() && !values.isEmpty();
    }

    public RemoteRepositoryDto createRepository(String workspace, CreateRepositoryCommand command, String token) {
        requireToken(token);
        Map<String, Object> body = new HashMap<>();
        body.put("scm", "git");
        body.put("name", command.name());
        body.put("is_private", command.visibility() != RepositoryVisibility.PUBLIC);
        body.put("mainbranch", Map.of("name", command.defaultBranch()));

        ResponseEntity<String> response =
                bitbucketApiClient.createRepository(workspace, command.name(), body, bearer(token));
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException(
                    "Bitbucket repository creation failed with status: " + response.getStatusCode());
        }
        return toRemoteRepositoryDto(parse(response));
    }

    private Integer extractPageNumber(String url) {
        String query = URI.create(url).getQuery();
        if (query == null) {
            return null;
        }
        for (String param : query.split("&")) {
            if (param.startsWith("page=")) {
                try {
                    return Integer.valueOf(param.substring("page=".length()));
                } catch (NumberFormatException e) {
                    logger.warn("Ignoring unparseable Bitbucket pagination link: {}", url);
                    return null;
                }
            }
        }
        return null;
    }

    private JsonNode parse(ResponseEntity<String> response) {
        try {
            if (response.getBody() == null) {
                throw new IllegalStateException("Empty response from Bitbucket API");
            }
            return objectMapper.readTree(response.getBody());
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Bitbucket API response: " + e.getMessage(), e);
        }
    }
}
