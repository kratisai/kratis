package com.kratisai.controlplane.service;

import com.kratisai.controlplane.model.KratisInstallation;
import com.kratisai.controlplane.repository.KratisInstallationRepository;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InstallationService {

    private static final Logger logger = LoggerFactory.getLogger(InstallationService.class);

    private final KratisInstallationRepository installationRepository;

    public InstallationService(KratisInstallationRepository installationRepository) {
        this.installationRepository = installationRepository;
    }

    @Transactional
    public UUID getOrCreateInstallationId() {
        Optional<KratisInstallation> existing = installationRepository.findFirstByOrderByCreatedAtAsc();
        if (existing.isPresent()) {
            return existing.get().getInstallId();
        }
        KratisInstallation installation = new KratisInstallation(UUID.randomUUID());
        try {
            installationRepository.saveAndFlush(installation);
            return installation.getInstallId();
        } catch (DataIntegrityViolationException race) {
            logger.debug("Concurrent installation row insertion detected, fetching persisted instance", race);
            return installationRepository
                    .findFirstByOrderByCreatedAtAsc()
                    .orElseThrow(() -> new IllegalStateException("Installation row lost after concurrent insert", race))
                    .getInstallId();
        }
    }
}
