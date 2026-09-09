package com.kratisai.controlplane.service;

import com.kratisai.controlplane.model.CredentialType;
import com.kratisai.controlplane.model.RepoCredential;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.event.TeamEntityChangedEvent;
import com.kratisai.controlplane.model.event.TeamEntityType;
import com.kratisai.controlplane.repository.RepoCredentialRepository;
import com.kratisai.controlplane.repository.RepositoryRepository;
import com.kratisai.controlplane.repository.TeamRepository;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CredentialService {

    private static final Logger logger = LoggerFactory.getLogger(CredentialService.class);

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128; // in bits

    private final RepoCredentialRepository credentialRepository;
    private final TeamRepository teamRepository;
    private final RepositoryRepository repositoryRepository;
    private final SecretKeySpec secretKey;
    private final ApplicationEventPublisher eventPublisher;

    public CredentialService(
            RepoCredentialRepository credentialRepository,
            TeamRepository teamRepository,
            RepositoryRepository repositoryRepository,
            EncryptionKeyManager encryptionKeyManager,
            ApplicationEventPublisher eventPublisher) {
        this.credentialRepository = credentialRepository;
        this.teamRepository = teamRepository;
        this.repositoryRepository = repositoryRepository;
        this.secretKey = deriveSecretKey(encryptionKeyManager.getEncryptionKey());
        this.eventPublisher = eventPublisher;
    }

    private SecretKeySpec deriveSecretKey(@NotNull String rawKey) {
        try {
            byte[] keyBytes = new byte[32]; // 256 bits
            byte[] rawKeyBytes = rawKey.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(rawKeyBytes, 0, keyBytes, 0, Math.min(rawKeyBytes.length, 32));
            return new SecretKeySpec(keyBytes, "AES");
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize cryptographic secret key", e);
        }
    }

    public String encrypt(String plainText) {
        if (plainText == null) {
            return null;
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            SecureRandom random = new SecureRandom();
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec);

            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            byte[] encryptedCombined = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, encryptedCombined, 0, iv.length);
            System.arraycopy(cipherText, 0, encryptedCombined, iv.length, cipherText.length);

            return Base64.getEncoder().encodeToString(encryptedCombined);
        } catch (Exception e) {
            logger.error("Failed to encrypt secret", e);
            throw new RuntimeException("Encryption failure", e);
        }
    }

    public String decrypt(String encryptedBase64) {
        if (encryptedBase64 == null) {
            return null;
        }
        try {
            byte[] encryptedCombined = Base64.getDecoder().decode(encryptedBase64);
            if (encryptedCombined.length < GCM_IV_LENGTH) {
                throw new IllegalArgumentException("Invalid encrypted payload size");
            }

            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(encryptedCombined, 0, iv, 0, iv.length);

            byte[] cipherText = new byte[encryptedCombined.length - GCM_IV_LENGTH];
            System.arraycopy(encryptedCombined, iv.length, cipherText, 0, cipherText.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);

            byte[] decryptedBytes = cipher.doFinal(cipherText);
            return new String(decryptedBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            logger.error("Failed to decrypt secret", e);
            throw new RuntimeException("Decryption failure", e);
        }
    }

    @Transactional(readOnly = true)
    public List<RepoCredential> listCredentials(UUID teamId) {
        return credentialRepository.findByTeamId(teamId);
    }

    @Transactional(readOnly = true)
    public Optional<RepoCredential> getCredential(UUID teamId, UUID id) {
        return credentialRepository.findByTeamIdAndId(teamId, id);
    }

    @Transactional
    public RepoCredential saveCredential(
            UUID teamId, String name, CredentialType type, String secret, String publicKey, String providerMetadata) {
        Team team = teamRepository
                .findById(teamId)
                .orElseThrow(() -> new IllegalArgumentException("Team not found: " + teamId));

        RepoCredential credential = new RepoCredential();
        credential.setTeam(team);
        credential.setName(name);
        credential.setType(type);
        credential.setEncryptedSecret(encrypt(secret));
        credential.setPublicKey(publicKey);
        credential.setProviderMetadata(providerMetadata);

        RepoCredential saved = credentialRepository.save(credential);
        eventPublisher.publishEvent(new TeamEntityChangedEvent(teamId, TeamEntityType.CREDENTIALS));
        return saved;
    }

    @Transactional
    public RepoCredential updateCredential(
            UUID teamId, UUID id, String name, String secret, String publicKey, String providerMetadata) {
        RepoCredential credential = credentialRepository
                .findByTeamIdAndId(teamId, id)
                .orElseThrow(() -> new IllegalArgumentException("Credential not found for team"));

        credential.setName(name);
        if (secret != null && !secret.trim().isEmpty()) {
            credential.setEncryptedSecret(encrypt(secret));
        }
        if (publicKey != null) {
            credential.setPublicKey(publicKey);
        }
        if (providerMetadata != null) {
            credential.setProviderMetadata(providerMetadata);
        }

        RepoCredential saved = credentialRepository.save(credential);
        eventPublisher.publishEvent(new TeamEntityChangedEvent(teamId, TeamEntityType.CREDENTIALS));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<Repository> findRepositoriesUsingCredential(UUID teamId, UUID credentialId) {
        RepoCredential credential = credentialRepository
                .findByTeamIdAndId(teamId, credentialId)
                .orElseThrow(() -> new IllegalArgumentException("Credential not found for team"));
        return repositoryRepository.findByCredential(credential);
    }

    @Transactional
    public void deleteCredential(UUID teamId, UUID id) {
        RepoCredential credential = credentialRepository
                .findByTeamIdAndId(teamId, id)
                .orElseThrow(() -> new IllegalArgumentException("Credential not found for team"));
        // Cascade delete: remove all repositories using this credential
        List<Repository> repos = repositoryRepository.findByCredential(credential);
        if (!repos.isEmpty()) {
            repositoryRepository.deleteAll(repos);
            eventPublisher.publishEvent(new TeamEntityChangedEvent(teamId, TeamEntityType.REPOSITORIES));
        }
        credentialRepository.delete(credential);
        eventPublisher.publishEvent(new TeamEntityChangedEvent(teamId, TeamEntityType.CREDENTIALS));
    }

    public String decryptSecret(RepoCredential credential) {
        if (credential == null) {
            return null;
        }
        return decrypt(credential.getEncryptedSecret());
    }
}
