package com.kratisai.controlplane.api.rest;

import com.kratisai.controlplane.api.restdto.*;
import com.kratisai.controlplane.config.GitHubAppConfig;
import com.kratisai.controlplane.config.SecurityUtil;
import com.kratisai.controlplane.git.credential.GitAuthMaterial;
import com.kratisai.controlplane.git.provider.GitHubApiService;
import com.kratisai.controlplane.git.provider.RepoProvider;
import com.kratisai.controlplane.git.provider.RepoProviderRegistry;
import com.kratisai.controlplane.model.CredentialType;
import com.kratisai.controlplane.model.RepoCredential;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.model.RepositoryType;
import com.kratisai.controlplane.repository.RepositoryRepository;
import com.kratisai.controlplane.repository.TeamMemberRepository;
import com.kratisai.controlplane.service.CredentialService;
import com.kratisai.controlplane.service.GitCredentialResolver;
import com.kratisai.controlplane.service.RepositoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/teams/{teamId}/credentials")
@Tag(name = "Repo Credentials", description = "Git repository credential management scoped to teams")
@SecurityRequirement(name = "bearerAuth")
public class RepoCredentialController {

    private final CredentialService credentialService;
    private final TeamMemberRepository teamMemberRepository;
    private final RepositoryRepository repositoryRepository;
    private final GitHubApiService gitHubApiService;
    private final RepositoryService repositoryService;
    private final RepoProviderRegistry providerRegistry;
    private final GitCredentialResolver credentialResolver;
    private final GitHubAppConfig gitHubAppConfig;

    public RepoCredentialController(
            CredentialService credentialService,
            TeamMemberRepository teamMemberRepository,
            RepositoryRepository repositoryRepository,
            GitHubApiService gitHubApiService,
            RepositoryService repositoryService,
            RepoProviderRegistry providerRegistry,
            GitCredentialResolver credentialResolver,
            GitHubAppConfig gitHubAppConfig) {
        this.credentialService = credentialService;
        this.teamMemberRepository = teamMemberRepository;
        this.repositoryRepository = repositoryRepository;
        this.gitHubApiService = gitHubApiService;
        this.repositoryService = repositoryService;
        this.providerRegistry = providerRegistry;
        this.credentialResolver = credentialResolver;
        this.gitHubAppConfig = gitHubAppConfig;
    }

    private void requireGitHubAppEnabled(CredentialType type) {
        if (type == CredentialType.GITHUB_APP && !gitHubAppConfig.isEnabled()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "GitHub App authentication is not enabled on this deployment");
        }
    }

    private void requireTeamMembership(UUID userId, UUID teamId) {
        boolean isMember = teamMemberRepository.existsByTeamIdAndUserId(teamId, userId);
        if (!isMember) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this team");
        }
    }

    private RepoCredentialDto toDto(RepoCredential cred) {
        return new RepoCredentialDto(
                cred.getId(),
                cred.getName(),
                cred.getType() != null ? cred.getType().name() : null,
                cred.getPublicKey(),
                cred.getProviderMetadata(),
                cred.getCreatedAt());
    }

    private RepositoryType resolveProviderType(RepoCredential credential) {
        // Prefer the provider declared on the credential metadata so a credential can be queried
        // before (or without) any repository being linked to it.
        Optional<RepositoryType> metadataType = credential.getMetadata().getProviderType();
        if (metadataType.isPresent()) {
            return metadataType.get();
        }

        List<RepositoryType> types = repositoryRepository.findByCredential(credential).stream()
                .map(Repository::getRepositoryType)
                .distinct()
                .toList();
        if (types.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Credential does not declare a provider and is not linked to any repository");
        }
        if (types.size() != 1) {
            throw new IllegalStateException(
                    "Credential %s somehow shared across multiple repo providers.".formatted(credential));
        }
        return types.getFirst();
    }

    @PostMapping
    @Operation(summary = "Save a repository credential", description = "Creates a new Git credential for a team")
    @ApiResponses({
        @ApiResponse(
                responseCode = "201",
                description = "Credential created",
                content = @Content(schema = @Schema(implementation = RepoCredentialDto.class))),
        @ApiResponse(
                responseCode = "400",
                description = "GitHub App authentication is not enabled on this deployment",
                content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden membership check failure", content = @Content)
    })
    public ResponseEntity<RepoCredentialDto> saveCredential(
            @PathVariable UUID teamId, @Valid @RequestBody SaveRepoCredentialRequest request) {
        UUID userId = SecurityUtil.getCurrentUserId();
        requireTeamMembership(userId, teamId);

        CredentialType type = CredentialType.valueOf(request.type().toUpperCase());
        requireGitHubAppEnabled(type);

        RepoCredential cred = credentialService.saveCredential(
                teamId, request.name(), type, request.secret(), request.publicKey(), request.providerMetadata());

        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(cred));
    }

    @GetMapping
    @Operation(summary = "List team repository credentials", description = "Lists all saved credentials for a team")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "List of credentials",
                content = @Content(schema = @Schema(implementation = RepoCredentialDto.class)))
    })
    public ResponseEntity<List<RepoCredentialDto>> listCredentials(@PathVariable UUID teamId) {
        UUID userId = SecurityUtil.getCurrentUserId();
        requireTeamMembership(userId, teamId);

        List<RepoCredentialDto> list = credentialService.listCredentials(teamId).stream()
                .map(this::toDto)
                .toList();

        return ResponseEntity.ok(list);
    }

    @PutMapping("/{credentialId}")
    @Operation(
            summary = "Update a repository credential",
            description = "Updates metadata/token for an existing credential")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Credential updated",
                content = @Content(schema = @Schema(implementation = RepoCredentialDto.class))),
        @ApiResponse(responseCode = "404", description = "Credential not found", content = @Content)
    })
    public ResponseEntity<RepoCredentialDto> updateCredential(
            @PathVariable UUID teamId,
            @PathVariable UUID credentialId,
            @Valid @RequestBody SaveRepoCredentialRequest request) {
        UUID userId = SecurityUtil.getCurrentUserId();
        requireTeamMembership(userId, teamId);

        RepoCredential cred = credentialService.updateCredential(
                teamId,
                credentialId,
                request.name(),
                request.secret(),
                request.publicKey(),
                request.providerMetadata());

        return ResponseEntity.ok(toDto(cred));
    }

    @GetMapping("/{credentialId}/affected-repositories")
    @Operation(
            summary = "List repositories using a credential",
            description = "Returns repositories that would be affected by deleting this credential")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "List of affected repositories",
                content = @Content(schema = @Schema(implementation = RepositoryDto.class))),
        @ApiResponse(responseCode = "404", description = "Credential not found", content = @Content)
    })
    public ResponseEntity<List<RepositoryDto>> getAffectedRepositories(
            @PathVariable UUID teamId, @PathVariable UUID credentialId) {
        UUID userId = SecurityUtil.getCurrentUserId();
        requireTeamMembership(userId, teamId);

        List<Repository> repos = credentialService.findRepositoriesUsingCredential(teamId, credentialId);
        List<RepositoryDto> dtos = repos.stream().map(repositoryService::toDto).toList();
        return ResponseEntity.ok(dtos);
    }

    @DeleteMapping("/{credentialId}")
    @Operation(
            summary = "Delete a repository credential",
            description = "Deletes a repository credential and any repositories using it from the team")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Credential deleted"),
        @ApiResponse(responseCode = "404", description = "Credential not found")
    })
    public ResponseEntity<Void> deleteCredential(@PathVariable UUID teamId, @PathVariable UUID credentialId) {
        UUID userId = SecurityUtil.getCurrentUserId();
        requireTeamMembership(userId, teamId);

        credentialService.deleteCredential(teamId, credentialId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/validate-github-app-installation")
    @Operation(
            summary = "Validate a GitHub App installation ID",
            description = "Validates the installation ID and returns the account login that owns the" + " installation")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Installation validated",
                content = @Content(schema = @Schema(implementation = GitHubInstallationInfoDto.class))),
        @ApiResponse(
                responseCode = "400",
                description = "Invalid installation ID or GitHub App authentication is not enabled",
                content = @Content)
    })
    public ResponseEntity<GitHubInstallationInfoDto> validateGitHubAppInstallation(
            @PathVariable UUID teamId, @RequestParam String installationId) {
        UUID userId = SecurityUtil.getCurrentUserId();
        requireTeamMembership(userId, teamId);
        requireGitHubAppEnabled(CredentialType.GITHUB_APP);

        String accountLogin = gitHubApiService.getInstallationAccountLogin(installationId);
        return ResponseEntity.ok(GitHubInstallationInfoDto.of(installationId, accountLogin));
    }

    @GetMapping("/{credentialId}/available-repos")
    @Operation(
            summary = "List available remote repositories",
            description = "Queries the credential's provider strategy using the saved credential")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "List of remote repositories",
                content = @Content(schema = @Schema(implementation = RemoteRepositoryDto.class))),
        @ApiResponse(responseCode = "404", description = "Credential not found", content = @Content)
    })
    public ResponseEntity<List<RemoteRepositoryDto>> listAvailableRemoteRepositories(
            @PathVariable UUID teamId, @PathVariable UUID credentialId) {
        UUID userId = SecurityUtil.getCurrentUserId();
        requireTeamMembership(userId, teamId);

        RepoCredential credential = credentialService
                .getCredential(teamId, credentialId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Credential not found"));

        RepositoryType providerType = resolveProviderType(credential);
        RepoProvider provider = providerRegistry.getProvider(providerType);
        GitAuthMaterial auth = credentialResolver.resolve(credential);

        try {
            return ResponseEntity.ok(provider.listRepositories(credential, auth));
        } catch (UnsupportedOperationException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Repository listing is not supported for provider " + providerType + ": " + e.getMessage());
        }
    }

    public record GenerateSshKeyRequest(@NotBlank String name) {}

    @PostMapping("/generate-ssh-key")
    @Operation(
            summary = "Generate and save a new SSH key pair credential",
            description =
                    "Generates a secure RSA key pair, saves it on the server, and returns the credential metadata including the public key")
    @ApiResponses({
        @ApiResponse(
                responseCode = "201",
                description = "Credential created",
                content = @Content(schema = @Schema(implementation = RepoCredentialDto.class))),
        @ApiResponse(responseCode = "403", description = "Forbidden membership check failure", content = @Content)
    })
    public ResponseEntity<RepoCredentialDto> generateSshKey(
            @PathVariable UUID teamId, @Valid @RequestBody GenerateSshKeyRequest request) {
        UUID userId = SecurityUtil.getCurrentUserId();
        requireTeamMembership(userId, teamId);

        try {
            java.security.KeyPairGenerator kpg = java.security.KeyPairGenerator.getInstance("RSA");
            kpg.initialize(4096);
            java.security.KeyPair kp = kpg.generateKeyPair();

            String privateKeyPem = encodePEMPrivateKey((java.security.interfaces.RSAPrivateKey) kp.getPrivate());
            String publicKeyOpenSSH = encodeOpenSSHPublicKey((java.security.interfaces.RSAPublicKey) kp.getPublic());

            RepoCredential cred = credentialService.saveCredential(
                    teamId, request.name(), CredentialType.SSH_KEY, privateKeyPem, publicKeyOpenSSH, "{}");

            return ResponseEntity.status(HttpStatus.CREATED).body(toDto(cred));
        } catch (Exception e) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Failed to generate and save SSH key pair", e);
        }
    }

    private String encodeOpenSSHPublicKey(java.security.interfaces.RSAPublicKey rsaPublicKey) throws IOException {
        java.io.ByteArrayOutputStream byteStream = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream dataStream = new java.io.DataOutputStream(byteStream);

        byte[] typeBytes = "ssh-rsa".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        dataStream.writeInt(typeBytes.length);
        dataStream.write(typeBytes);

        byte[] exponentBytes = rsaPublicKey.getPublicExponent().toByteArray();
        dataStream.writeInt(exponentBytes.length);
        dataStream.write(exponentBytes);

        byte[] modulusBytes = rsaPublicKey.getModulus().toByteArray();
        dataStream.writeInt(modulusBytes.length);
        dataStream.write(modulusBytes);

        dataStream.close();
        return "ssh-rsa "
                + java.util.Base64.getEncoder().encodeToString(byteStream.toByteArray())
                + " kratis-generated-key";
    }

    private String encodePEMPrivateKey(java.security.interfaces.RSAPrivateKey rsaPrivateKey) {
        String base64 = java.util.Base64.getEncoder().encodeToString(rsaPrivateKey.getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + insertNewlines(base64) + "\n-----END PRIVATE KEY-----";
    }

    private String insertNewlines(String base64) {
        StringBuilder sb = new StringBuilder();
        int index = 0;
        while (index < base64.length()) {
            sb.append(base64, index, Math.min(index + 64, base64.length()));
            if (index + 64 < base64.length()) {
                sb.append('\n');
            }
            index += 64;
        }
        return sb.toString();
    }
}
