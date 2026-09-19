package com.kratisai.controlplane.git.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.api.restdto.PullRequestResultDto;
import com.kratisai.controlplane.api.restdto.RemoteRepositoryDto;
import com.kratisai.controlplane.client.GitHubApiClient;
import com.kratisai.controlplane.client.GitHubContentDto;
import com.kratisai.controlplane.config.GitHubAppConfig;
import com.kratisai.controlplane.model.RepositoryVisibility;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

@Service
public class GitHubApiService {

    private static final Logger logger = LoggerFactory.getLogger(GitHubApiService.class);
    private static final int MAX_FILE_SIZE_BYTES = 1_000_000;

    private final GitHubApiClient gitHubApiClient;
    private final GitHubAppConfig gitHubAppConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GitHubApiService(GitHubApiClient gitHubApiClient, GitHubAppConfig gitHubAppConfig) {
        this.gitHubApiClient = gitHubApiClient;
        this.gitHubAppConfig = gitHubAppConfig;
    }

    /**
     * Lists repositories available to a specific GitHub App installation.
     *
     * @param installationId the GitHub App installation ID for this team/credential
     * @return list of repositories accessible by the installation
     */
    public List<RemoteRepositoryDto> listAvailableRepositories(String installationId) {
        try {
            String jwt = generateInstallationJwt(installationId);

            String token = fetchInstallationAccessToken(installationId, jwt);

            return fetchRepositories(token);
        } catch (Exception e) {
            logger.error("Failed to fetch repositories from GitHub App API", e);
            throw new RuntimeException("Failed to fetch available GitHub repositories: " + e.getMessage(), e);
        }
    }

    /**
     * Lists repositories visible to the authenticated user via a {@code PAT} credential.
     *
     * @param token the personal access token
     * @return list of repositories accessible to the token's user
     */
    public List<RemoteRepositoryDto> listUserRepositories(String token) {
        try {
            return fetchUserRepositories(token);
        } catch (Exception e) {
            logger.error("Failed to fetch repositories from GitHub user API", e);
            throw new RuntimeException("Failed to fetch available GitHub repositories: " + e.getMessage(), e);
        }
    }

    /** Gets the private key path from configuration. */
    public Path getPrivateKeyPath() {
        String privateKeyPath = gitHubAppConfig.getPrivateKeyPath();
        if (privateKeyPath == null || privateKeyPath.trim().isEmpty()) {
            throw new IllegalStateException("GitHub App private key path is not configured");
        }
        return Path.of(privateKeyPath);
    }

    /**
     * Reads and parses a PEM private key file, handling both PKCS#8 and PKCS#1 formats. PKCS#8
     * (BEGIN PRIVATE KEY) is used directly. PKCS#1 (BEGIN RSA PRIVATE KEY) is parsed into
     * RSAPrivateCrtKeySpec.
     */
    private PrivateKey loadPrivateKey(Path keyPath) throws Exception {
        String pem = Files.readString(keyPath);

        if (pem.contains("BEGIN RSA PRIVATE KEY")) {
            String base64 = pem.replace("-----BEGIN RSA PRIVATE KEY-----", "")
                    .replace("-----END RSA PRIVATE KEY-----", "")
                    .replaceAll("\\s+", "");
            byte[] derBytes = Base64.getDecoder().decode(base64);
            return parsePkcs1PrivateKey(derBytes);
        } else {
            String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s+", "");
            byte[] keyBytes = Base64.getDecoder().decode(base64);
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            return kf.generatePrivate(spec);
        }
    }

    /**
     * Parses a PKCS#1 RSAPrivateKey DER structure and creates a PrivateKey. The ASN.1 structure is:
     * SEQUENCE { version, modulus, publicExponent, privateExponent, prime1, prime2, exponent1,
     * exponent2, coefficient }
     */
    private PrivateKey parsePkcs1PrivateKey(byte[] derBytes) throws Exception {
        DerParser parser = new DerParser(derBytes);
        parser.readSequence();
        parser.readInteger();
        BigInteger modulus = parser.readInteger();
        BigInteger publicExponent = parser.readInteger();
        BigInteger privateExponent = parser.readInteger();
        BigInteger prime1 = parser.readInteger();
        BigInteger prime2 = parser.readInteger();
        BigInteger exponent1 = parser.readInteger();
        BigInteger exponent2 = parser.readInteger();
        BigInteger coefficient = parser.readInteger();

        RSAPrivateCrtKeySpec keySpec = new RSAPrivateCrtKeySpec(
                modulus, publicExponent, privateExponent, prime1, prime2, exponent1, exponent2, coefficient);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return kf.generatePrivate(keySpec);
    }

    /**
     * Minimal DER/ASN.1 parser for reading PKCS#1 key components. Only implements the subset needed
     * for RSA private keys.
     */
    private static class DerParser {
        private final DataInputStream in;

        DerParser(byte[] data) {
            this.in = new DataInputStream(new ByteArrayInputStream(data));
        }

        void readSequence() throws IOException {
            int tag = in.read();
            if (tag != 0x30) {
                throw new IOException("Expected SEQUENCE tag 0x30, got 0x" + Integer.toHexString(tag));
            }
            readLength();
        }

        BigInteger readInteger() throws IOException {
            int tag = in.read();
            if (tag < 0) {
                throw new IOException("Unexpected end of DER data");
            }
            if (tag != 0x02) {
                throw new IOException("Expected INTEGER tag 0x02, got 0x" + Integer.toHexString(tag));
            }
            int length = readLength();
            byte[] bytes = new byte[length];
            in.readFully(bytes);
            return new BigInteger(1, bytes);
        }

        private int readLength() throws IOException {
            int b = in.read();
            if (b < 0) {
                throw new IOException("Unexpected end of DER data");
            }
            if ((b & 0x80) == 0) {
                return b;
            }
            int numBytes = b & 0x7F;
            int result = 0;
            for (int i = 0; i < numBytes; i++) {
                result = (result << 8) | in.readUnsignedByte();
            }
            return result;
        }
    }

    public String generateJwt(String appId, Path privateKeyPath) throws Exception {
        String headerJson = "{\"alg\":\"RS256\",\"typ\":\"JWT\"}";
        long nowSec = Instant.now().getEpochSecond();
        long expSec = nowSec + 600; // 10 minutes max expiration
        String claimsJson = String.format("{\"iat\":%d,\"exp\":%d,\"iss\":\"%s\"}", nowSec - 60, expSec, appId);

        String encodedHeader =
                Base64.getUrlEncoder().withoutPadding().encodeToString(headerJson.getBytes(StandardCharsets.UTF_8));
        String encodedClaims =
                Base64.getUrlEncoder().withoutPadding().encodeToString(claimsJson.getBytes(StandardCharsets.UTF_8));
        String unsignedToken = encodedHeader + "." + encodedClaims;

        PrivateKey privateKey = loadPrivateKey(privateKeyPath);

        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey);
        signature.update(unsignedToken.getBytes(StandardCharsets.UTF_8));
        byte[] sigBytes = signature.sign();

        String encodedSignature = Base64.getUrlEncoder().withoutPadding().encodeToString(sigBytes);
        return unsignedToken + "." + encodedSignature;
    }

    /**
     * Gets information about a GitHub App installation, including the account that installed it.
     *
     * @param installationId the GitHub App installation ID
     * @return the account login (username or organization name) that owns the installation
     */
    public String getInstallationAccountLogin(String installationId) {
        try {
            String jwt = generateInstallationJwt(installationId);

            return fetchInstallationAccountLogin(installationId, jwt);
        } catch (Exception e) {
            logger.error("Failed to fetch GitHub App installation info", e);
            throw new RuntimeException("Failed to fetch GitHub App installation info: " + e.getMessage(), e);
        }
    }

    /**
     * Obtains an installation access token for a given installation ID. Generates the JWT
     * internally based on app configuration.
     *
     * @param installationId the GitHub App installation ID
     * @return the installation access token
     */
    public String getInstallationAccessToken(String installationId) {
        try {
            String jwt = generateInstallationJwt(installationId);

            return fetchInstallationAccessToken(installationId, jwt);
        } catch (Exception e) {
            logger.error("Failed to fetch GitHub App installation token", e);
            throw new RuntimeException("Failed to fetch GitHub App installation token: " + e.getMessage(), e);
        }
    }

    private String fetchInstallationAccountLogin(String installationId, String jwt) {
        try {
            ResponseEntity<String> response = gitHubApiClient.getInstallation(installationId, "Bearer " + jwt);
            String body = response.getBody();
            if (body == null) {
                throw new RuntimeException("Empty response from GitHub API");
            }
            JsonNode root = objectMapper.readTree(body);
            JsonNode account = root.get("account");
            if (account != null && account.has("login")) {
                return account.get("login").asText();
            }
            throw new RuntimeException("Account login not found in installation response");
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch installation info: " + e.getMessage(), e);
        }
    }

    private String fetchInstallationAccessToken(String installationId, String jwt) {
        try {
            ResponseEntity<String> response =
                    gitHubApiClient.createInstallationAccessToken(installationId, "Bearer " + jwt);
            JsonNode root = objectMapper.readTree(response.getBody());
            if (root.has("token")) {
                return root.get("token").asText();
            }
            throw new RuntimeException("Access token field not found in response");
        } catch (Exception e) {
            throw new RuntimeException("Failed to get installation access token: " + e.getMessage(), e);
        }
    }

    private List<RemoteRepositoryDto> fetchRepositories(String installationToken) {
        List<RemoteRepositoryDto> allRepos = new ArrayList<>();

        try {
            Integer page = 1;
            while (page != null) {
                ResponseEntity<String> response =
                        gitHubApiClient.getInstallationRepositories(100, page, "Bearer " + installationToken);
                JsonNode root = objectMapper.readTree(response.getBody());

                JsonNode reposNode = root.get("repositories");
                if (reposNode != null && reposNode.isArray()) {
                    for (JsonNode repo : reposNode) {
                        allRepos.add(toRemoteRepositoryDto(repo));
                    }
                }

                page = getNextPage(response);
            }
            return allRepos;
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch installations repositories: " + e.getMessage(), e);
        }
    }

    private List<RemoteRepositoryDto> fetchUserRepositories(String token) {
        List<RemoteRepositoryDto> allRepos = new ArrayList<>();

        try {
            Integer page = 1;
            while (page != null) {
                ResponseEntity<String> response = gitHubApiClient.getUserRepositories(
                        100, page, "full_name", "owner,collaborator,organization_member", "Bearer " + token);
                JsonNode root = objectMapper.readTree(response.getBody());

                if (root.isArray()) {
                    for (JsonNode repo : root) {
                        allRepos.add(toRemoteRepositoryDto(repo));
                    }
                }

                page = getNextPage(response);
            }
            return allRepos;
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch user repositories: " + e.getMessage(), e);
        }
    }

    private RemoteRepositoryDto toRemoteRepositoryDto(JsonNode repo) {
        String name = repo.has("full_name")
                ? repo.get("full_name").asText()
                : repo.get("name").asText();
        String cloneUrl = repo.has("clone_url") ? repo.get("clone_url").asText() : "";
        String sshUrl = repo.has("ssh_url") ? repo.get("ssh_url").asText() : "";
        String branch =
                repo.has("default_branch") && !repo.get("default_branch").isNull()
                        ? repo.get("default_branch").asText()
                        : "main";
        return new RemoteRepositoryDto(name, cloneUrl, sshUrl, branch);
    }

    private Integer getNextPage(ResponseEntity<String> response) {
        String linkHeader = response.getHeaders().getFirst("Link");
        if (linkHeader == null) {
            return null;
        }

        // Format: <https://api.github.com/...?page=2>; rel="next", <...>; rel="last"
        for (String link : linkHeader.split(",")) {
            if (link.contains("rel=\"next\"")) {
                int start = link.indexOf('<');
                int end = link.indexOf('>');
                if (start >= 0 && end > start) {
                    return extractPageNumber(link.substring(start + 1, end));
                }
            }
        }
        return null;
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
                    logger.warn("Ignoring unparseable GitHub pagination link: {}", url);
                    return null;
                }
            }
        }
        return null;
    }

    public String readFile(String owner, String repo, String path, String branch, String token) {
        GitHubContentDto content = gitHubApiClient.getFileContents(owner, repo, path, branch, "Bearer " + token);

        if (content.size() > MAX_FILE_SIZE_BYTES) {
            throw new IllegalStateException(
                    "File too large (" + content.size() + " bytes). Maximum is " + MAX_FILE_SIZE_BYTES);
        }

        if (!"base64".equals(content.encoding()) || content.content() == null) {
            throw new IllegalStateException(
                    "Unexpected GitHub Contents API response for " + path + ": encoding=" + content.encoding());
        }

        String base64 = content.content().replaceAll("\\s", "");
        return new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
    }

    private String generateInstallationJwt(String installationId) throws Exception {
        String appId = gitHubAppConfig.getAppId();
        Path privateKeyPath = getPrivateKeyPath();

        if (appId == null || appId.trim().isEmpty()) {
            throw new IllegalStateException("GitHub App ID is not configured");
        }
        if (installationId == null || installationId.trim().isEmpty()) {
            throw new IllegalStateException("GitHub App installation ID is not provided");
        }

        return generateJwt(appId, privateKeyPath);
    }

    public PullRequestResultDto createPullRequest(
            String owner, String repo, CreatePullRequestCommand command, String token) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("title", command.title());
            body.put("head", command.headBranch());
            body.put("base", command.baseBranch());
            body.put("body", command.description());
            if (command.draft()) {
                body.put("draft", true);
            }

            ResponseEntity<String> response = gitHubApiClient.createPullRequest(owner, repo, body, "Bearer " + token);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new IllegalStateException("GitHub PR creation failed with status: " + response.getStatusCode());
            }

            JsonNode root = objectMapper.readTree(response.getBody());
            long prNumber = root.path("number").asLong();
            String prUrl = root.path("html_url").asText("");
            String headBranch = root.path("head").path("ref").asText(command.headBranch());
            String baseBranch = root.path("base").path("ref").asText(command.baseBranch());

            return new PullRequestResultDto(prNumber, prUrl, headBranch, baseBranch);
        } catch (Exception e) {
            logger.error("Failed to create GitHub pull request for {}/{}", owner, repo, e);
            throw new RuntimeException("Failed to create GitHub pull request: " + e.getMessage(), e);
        }
    }

    /** The account a credential creates repositories under. */
    public record GitHubAccount(String login, boolean organization) {}

    /** Resolves the account that installed the GitHub App, used as the repository owner. */
    public GitHubAccount getInstallationAccount(String installationId) {
        try {
            String jwt = generateInstallationJwt(installationId);
            ResponseEntity<String> response = gitHubApiClient.getInstallation(installationId, "Bearer " + jwt);
            JsonNode account = parse(response).path("account");
            String login = account.path("login").asText("");
            if (login.isEmpty()) {
                throw new IllegalStateException("GitHub installation response did not include an account login");
            }
            return new GitHubAccount(
                    login, "Organization".equals(account.path("type").asText("")));
        } catch (Exception e) {
            logger.error("Failed to fetch GitHub App installation account", e);
            throw new RuntimeException("Failed to fetch GitHub App installation account: " + e.getMessage(), e);
        }
    }

    /** Resolves the authenticated PAT user, used as the repository owner. */
    public GitHubAccount getAuthenticatedAccount(String token) {
        ResponseEntity<String> response = gitHubApiClient.getAuthenticatedUser("Bearer " + token);
        String login = parse(response).path("login").asText("");
        if (login.isEmpty()) {
            throw new IllegalStateException("GitHub user response did not include a login");
        }
        return new GitHubAccount(login, false);
    }

    public Optional<RemoteRepositoryDto> findRepository(String owner, String repo, String token) {
        ResponseEntity<String> response = gitHubApiClient.getRepository(owner, repo, "Bearer " + token);
        if (response.getStatusCode().value() == 404) {
            return Optional.empty();
        }
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("GitHub repository lookup failed with status: " + response.getStatusCode());
        }
        return Optional.of(toRemoteRepositoryDto(parse(response)));
    }

    public boolean repositoryHasCommits(String owner, String repo, String token) {
        ResponseEntity<String> response = gitHubApiClient.getBranches(owner, repo, 1, "Bearer " + token);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("GitHub branch lookup failed with status: " + response.getStatusCode());
        }
        JsonNode branches = parse(response);
        return branches.isArray() && !branches.isEmpty();
    }

    public RemoteRepositoryDto createRepository(
            String owner, boolean organization, CreateRepositoryCommand command, String token) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", command.name());
        body.put("private", command.visibility() != RepositoryVisibility.PUBLIC);
        body.put("auto_init", false);

        ResponseEntity<String> response = organization
                ? gitHubApiClient.createOrgRepository(owner, body, "Bearer " + token)
                : gitHubApiClient.createUserRepository(body, "Bearer " + token);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException(
                    "GitHub repository creation failed with status: " + response.getStatusCode());
        }
        return toRemoteRepositoryDto(parse(response));
    }

    private JsonNode parse(ResponseEntity<String> response) {
        try {
            if (response.getBody() == null) {
                throw new IllegalStateException("Empty response from GitHub API");
            }
            return objectMapper.readTree(response.getBody());
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse GitHub API response: " + e.getMessage(), e);
        }
    }
}
