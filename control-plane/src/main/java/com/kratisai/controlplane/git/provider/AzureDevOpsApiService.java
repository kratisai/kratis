package com.kratisai.controlplane.git.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.api.restdto.PullRequestResultDto;
import com.kratisai.controlplane.api.restdto.RemoteRepositoryDto;
import com.kratisai.controlplane.client.AzureDevOpsApiClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
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
public class AzureDevOpsApiService {

    private static final Logger logger = LoggerFactory.getLogger(AzureDevOpsApiService.class);
    private static final int MAX_FILE_SIZE_BYTES = 1_000_000;
    private static final String API_VERSION = "7.1";

    private static final Pattern DEV_AZURE_COM_PATTERN =
            Pattern.compile("https://dev\\.azure\\.com/([^/]+)/([^/]+)/_git/([^/.]+)");
    private static final Pattern VISUALSTUDIO_COM_PATTERN =
            Pattern.compile("https://([^/.]+)\\.visualstudio\\.com/([^/]+)/_git/([^/.]+)");
    private static final Pattern SSH_DEV_AZURE_COM_PATTERN =
            Pattern.compile("git@ssh\\.dev\\.azure\\.com:v3/([^/]+)/([^/]+)/([^/.]+)");
    private static final Pattern SERVER_PATTERN = Pattern.compile("https://([^/]+)/(.+?)/_git/([^/.]+)");

    /** Parsed coordinates of an Azure DevOps repository URL. */
    public record Coordinates(
            /* API base URL, e.g. {@code https://dev.azure.com/myorg} (no trailing slash). */
            String apiBaseUrl, String organization, String project, String repository) {}

    private final AzureDevOpsApiClient azureDevOpsApiClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AzureDevOpsApiService(AzureDevOpsApiClient azureDevOpsApiClient) {
        this.azureDevOpsApiClient = azureDevOpsApiClient;
    }

    /**
     * Lists Git repositories for an organization, optionally scoped to a project.
     *
     * @param apiBaseUrl e.g. {@code https://dev.azure.com/myorg} or
     *     {@code https://myserver/tfs/DefaultCollection}
     */
    public List<RemoteRepositoryDto> listRepositories(String apiBaseUrl, String project, String token) {
        requireToken(token);
        UriBuilderFactory base = baseUriFactory(apiBaseUrl);

        try {
            ResponseEntity<String> response = project != null && !project.isBlank()
                    ? azureDevOpsApiClient.getProjectRepositories(base, project, API_VERSION, basic(token))
                    : azureDevOpsApiClient.getOrganizationRepositories(base, API_VERSION, basic(token));
            JsonNode root = objectMapper.readTree(response.getBody());
            List<RemoteRepositoryDto> repos = new ArrayList<>();
            JsonNode values = root.get("value");
            if (values != null && values.isArray()) {
                for (JsonNode repo : values) {
                    repos.add(toRemoteRepositoryDto(repo));
                }
            }
            return repos;
        } catch (Exception e) {
            logger.error("Failed to fetch repositories from Azure DevOps API", e);
            throw new RuntimeException("Failed to fetch available Azure DevOps repositories: " + e.getMessage(), e);
        }
    }

    public String readFile(Coordinates coords, String path, String branch, String token) {
        requireToken(token);
        String ref = branch != null && !branch.isBlank() ? branch : "main";

        ResponseEntity<String> response = azureDevOpsApiClient.getItem(
                baseUriFactory(coords.apiBaseUrl()),
                coords.project(),
                coords.repository(),
                path,
                ref,
                true,
                API_VERSION,
                basic(token));
        String body = response.getBody();
        if (body == null) {
            throw new IllegalStateException("Empty response from Azure DevOps Items API for " + path);
        }
        if (body.getBytes(StandardCharsets.UTF_8).length > MAX_FILE_SIZE_BYTES) {
            throw new IllegalStateException("File too large (" + body.getBytes(StandardCharsets.UTF_8).length
                    + " bytes). Maximum is " + MAX_FILE_SIZE_BYTES);
        }
        return body;
    }

    public PullRequestResultDto createPullRequest(Coordinates coords, CreatePullRequestCommand command, String token) {
        requireToken(token);
        try {
            String headRef = command.headBranch().startsWith("refs/heads/")
                    ? command.headBranch()
                    : "refs/heads/" + command.headBranch();
            String baseRef = command.baseBranch().startsWith("refs/heads/")
                    ? command.baseBranch()
                    : "refs/heads/" + command.baseBranch();

            Map<String, Object> body = new HashMap<>();
            body.put("sourceRefName", headRef);
            body.put("targetRefName", baseRef);
            body.put("title", command.title());
            body.put("description", command.description());
            if (command.draft()) {
                body.put("isDraft", true);
            }

            ResponseEntity<String> response = azureDevOpsApiClient.createPullRequest(
                    baseUriFactory(coords.apiBaseUrl()),
                    coords.project(),
                    coords.repository(),
                    API_VERSION,
                    body,
                    basic(token));

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new IllegalStateException(
                        "Azure DevOps PR creation failed with status: " + response.getStatusCode());
            }

            JsonNode root = objectMapper.readTree(response.getBody());
            long prNumber = root.path("pullRequestId").asLong();
            String prUrl = root.path("_links")
                    .path("web")
                    .path("href")
                    .asText(root.path("url").asText(""));
            String headBranch = root.path("sourceRefName").asText(headRef);
            if (headBranch.startsWith("refs/heads/")) {
                headBranch = headBranch.substring("refs/heads/".length());
            }
            String baseBranch = root.path("targetRefName").asText(baseRef);
            if (baseBranch.startsWith("refs/heads/")) {
                baseBranch = baseBranch.substring("refs/heads/".length());
            }

            return new PullRequestResultDto(prNumber, prUrl, headBranch, baseBranch);
        } catch (Exception e) {
            logger.error(
                    "Failed to create Azure DevOps pull request for {}/{}", coords.project(), coords.repository(), e);
            throw new RuntimeException("Failed to create Azure DevOps pull request: " + e.getMessage(), e);
        }
    }

    public Optional<RemoteRepositoryDto> findRepository(String apiBaseUrl, String project, String name, String token) {
        requireToken(token);
        ResponseEntity<String> response;
        try {
            response = azureDevOpsApiClient.getRepository(
                    baseUriFactory(apiBaseUrl), project, name, API_VERSION, basic(token));
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
            throw new IllegalStateException(
                    "Azure DevOps repository lookup failed with status: " + response.getStatusCode());
        }
        return Optional.of(toRemoteRepositoryDto(parseBody(response)));
    }

    public boolean repositoryHasCommits(String apiBaseUrl, String project, String name, String token) {
        requireToken(token);
        ResponseEntity<String> response = azureDevOpsApiClient.getCommits(
                baseUriFactory(apiBaseUrl), project, name, 1, API_VERSION, basic(token));
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException(
                    "Azure DevOps commit lookup failed with status: " + response.getStatusCode());
        }
        return parseBody(response).path("count").asInt(0) > 0;
    }

    public RemoteRepositoryDto createRepository(
            String apiBaseUrl, String project, CreateRepositoryCommand command, String token) {
        requireToken(token);
        Map<String, Object> body = new HashMap<>();
        body.put("name", command.name());
        body.put("project", Map.of("name", project));

        ResponseEntity<String> response = azureDevOpsApiClient.createRepository(
                baseUriFactory(apiBaseUrl), project, API_VERSION, body, basic(token));
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException(
                    "Azure DevOps repository creation failed with status: " + response.getStatusCode());
        }
        return toRemoteRepositoryDto(parseBody(response));
    }

    private static UriBuilderFactory baseUriFactory(String apiBaseUrl) {
        return new DefaultUriBuilderFactory(stripTrailingSlash(apiBaseUrl));
    }

    private JsonNode parseBody(ResponseEntity<String> response) {
        try {
            if (response.getBody() == null) {
                throw new IllegalStateException("Empty response from Azure DevOps API");
            }
            return objectMapper.readTree(response.getBody());
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Azure DevOps API response: " + e.getMessage(), e);
        }
    }

    /** Parses an Azure DevOps repository URL (dev.azure.com, *.visualstudio.com, SSH, or server). */
    public static Coordinates parse(String repoUrl) {
        Objects.requireNonNull(repoUrl, "repoUrl");
        Matcher devAzureCom = DEV_AZURE_COM_PATTERN.matcher(repoUrl);
        if (devAzureCom.find()) {
            return new Coordinates(
                    "https://dev.azure.com/" + devAzureCom.group(1),
                    devAzureCom.group(1),
                    devAzureCom.group(2),
                    devAzureCom.group(3));
        }
        Matcher visualStudio = VISUALSTUDIO_COM_PATTERN.matcher(repoUrl);
        if (visualStudio.find()) {
            return new Coordinates(
                    "https://" + visualStudio.group(1) + ".visualstudio.com",
                    visualStudio.group(1),
                    visualStudio.group(2),
                    visualStudio.group(3));
        }
        Matcher ssh = SSH_DEV_AZURE_COM_PATTERN.matcher(repoUrl);
        if (ssh.find()) {
            return new Coordinates("https://dev.azure.com/" + ssh.group(1), ssh.group(1), ssh.group(2), ssh.group(3));
        }
        Matcher server = SERVER_PATTERN.matcher(repoUrl);
        if (server.find()) {
            String host = server.group(1);
            // rest is e.g. "DefaultCollection/myproject" or "tfs/DefaultCollection/myproject"
            String rest = server.group(2);
            int lastSlash = rest.lastIndexOf('/');
            String collectionPath = lastSlash >= 0 ? rest.substring(0, lastSlash) : rest;
            String project = lastSlash >= 0 ? rest.substring(lastSlash + 1) : rest;
            String organization = collectionPath.substring(collectionPath.lastIndexOf('/') + 1);
            return new Coordinates("https://" + host + "/" + collectionPath, organization, project, server.group(3));
        }
        throw new IllegalArgumentException("Unable to parse Azure DevOps repository URL: " + repoUrl);
    }

    private static String basic(String token) {
        return "Basic " + Base64.getEncoder().encodeToString((":" + token).getBytes(StandardCharsets.UTF_8));
    }

    private static void requireToken(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("Azure DevOps access requires a token, but no token was provided");
        }
    }

    private static String stripTrailingSlash(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Azure DevOps API base URL must not be blank");
        }
        String trimmed = url.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private RemoteRepositoryDto toRemoteRepositoryDto(JsonNode repo) {
        String name = repo.path("name").asText("");
        String project = repo.path("project").path("name").asText("");
        String displayName = project.isEmpty() ? name : project + "/" + name;
        String cloneUrl = repo.path("remoteUrl").asText("");
        String sshUrl = repo.path("sshUrl").asText("");
        String branch = "main";
        JsonNode defaultBranch = repo.path("defaultBranch");
        if (!defaultBranch.isMissingNode()
                && !defaultBranch.isNull()
                && !defaultBranch.asText("").isBlank()) {
            String ref = defaultBranch.asText();
            branch = ref.startsWith("refs/heads/") ? ref.substring("refs/heads/".length()) : ref;
        }
        return new RemoteRepositoryDto(displayName, cloneUrl, sshUrl, branch);
    }
}
