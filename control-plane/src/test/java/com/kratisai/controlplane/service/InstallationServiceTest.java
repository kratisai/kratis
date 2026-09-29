package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.kratisai.controlplane.model.KratisInstallation;
import com.kratisai.controlplane.repository.KratisInstallationRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class InstallationServiceTest {

    private KratisInstallationRepository installationRepository;
    private InstallationService installationService;

    @BeforeEach
    void setUp() {
        installationRepository = mock(KratisInstallationRepository.class);
        installationService = new InstallationService(installationRepository);
    }

    @Test
    void getOrCreateInstallationId_whenExists_returnsExistingInstallId() {
        UUID existingId = UUID.randomUUID();
        KratisInstallation existing = new KratisInstallation(existingId);
        when(installationRepository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(existing));

        UUID result = installationService.getOrCreateInstallationId();

        assertThat(result).isEqualTo(existingId);
        verify(installationRepository, never()).saveAndFlush(any());
    }

    @Test
    void getOrCreateInstallationId_whenEmpty_createsAndSavesNewInstallation() {
        when(installationRepository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.empty());

        UUID result = installationService.getOrCreateInstallationId();

        assertThat(result).isNotNull();
        verify(installationRepository).saveAndFlush(any(KratisInstallation.class));
    }

    @Test
    void getOrCreateInstallationId_whenConcurrentInsertRace_fetchesExisting() {
        UUID raceId = UUID.randomUUID();
        KratisInstallation existingAfterRace = new KratisInstallation(raceId);
        when(installationRepository.findFirstByOrderByCreatedAtAsc())
                .thenReturn(Optional.empty(), Optional.of(existingAfterRace));
        doThrow(new DataIntegrityViolationException("duplicate key"))
                .when(installationRepository)
                .saveAndFlush(any(KratisInstallation.class));

        UUID result = installationService.getOrCreateInstallationId();

        assertThat(result).isEqualTo(raceId);
        verify(installationRepository, times(2)).findFirstByOrderByCreatedAtAsc();
    }
}
