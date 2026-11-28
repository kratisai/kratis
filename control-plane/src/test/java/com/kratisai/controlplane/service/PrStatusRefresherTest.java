package com.kratisai.controlplane.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.git.provider.RepoProviderRegistry;
import com.kratisai.controlplane.model.ChangeSet;
import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.PublishedPrStatus;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.repository.ChangeSetRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class PrStatusRefresherTest {

    private final ChangeSetRepository changeSetRepository = mock(ChangeSetRepository.class);
    private final PrStatusRefresher refresher = new PrStatusRefresher(
            changeSetRepository,
            mock(GitCredentialResolver.class),
            mock(RepoProviderRegistry.class),
            mock(ApplicationEventPublisher.class));

    @Test
    void applyUpdatesStatusWithoutBumpingChatActivity() {
        UUID changeSetId = UUID.randomUUID();
        ChangeSet changeSet = mock(ChangeSet.class);
        SandboxExecution execution = mock(SandboxExecution.class);
        ChatEntity chat = mock(ChatEntity.class);
        Team team = mock(Team.class);

        when(changeSetRepository.findWithPollingGraphById(changeSetId)).thenReturn(Optional.of(changeSet));
        when(changeSet.getExecution()).thenReturn(execution);
        when(execution.getChat()).thenReturn(chat);
        when(chat.getTeam()).thenReturn(team);

        refresher.apply(changeSetId, PublishedPrStatus.OPEN, null);

        verify(changeSet).setPrStatus(PublishedPrStatus.OPEN);
        verify(changeSetRepository).save(changeSet);
        verify(chat, never()).touchUpdatedAt(any());
    }

    @Test
    void applyMergedPersistsProviderMergeTimestamp() {
        UUID changeSetId = UUID.randomUUID();
        ChangeSet changeSet = mock(ChangeSet.class);
        SandboxExecution execution = mock(SandboxExecution.class);
        ChatEntity chat = mock(ChatEntity.class);
        Team team = mock(Team.class);
        Instant mergedAt = Instant.parse("2026-05-01T10:00:00Z");

        when(changeSetRepository.findWithPollingGraphById(changeSetId)).thenReturn(Optional.of(changeSet));
        when(changeSet.getExecution()).thenReturn(execution);
        when(execution.getId()).thenReturn(UUID.randomUUID());
        when(execution.getChat()).thenReturn(chat);
        when(chat.getId()).thenReturn(UUID.randomUUID());
        when(chat.getTeam()).thenReturn(team);
        when(team.getId()).thenReturn(UUID.randomUUID());

        refresher.apply(changeSetId, PublishedPrStatus.MERGED, mergedAt);

        verify(changeSet).setPrStatus(PublishedPrStatus.MERGED);
        verify(changeSet).setPrMergedAt(mergedAt);
        verify(changeSetRepository).save(changeSet);
        verify(chat, never()).touchUpdatedAt(any());
    }
}
