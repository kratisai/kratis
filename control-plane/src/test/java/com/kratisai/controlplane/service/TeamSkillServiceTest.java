package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.api.restdto.TeamSkillResponse;
import com.kratisai.controlplane.api.restdto.UpdateTeamSkillRequest;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.TeamSkill;
import com.kratisai.controlplane.repository.TeamRepository;
import com.kratisai.controlplane.repository.TeamSkillRepository;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

class TeamSkillServiceTest {

    private static final UUID TEAM_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID USER_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    @TempDir
    Path directory;

    private TeamSkillRepository teamSkillRepository;
    private TeamRepository teamRepository;
    private TeamService teamService;
    private BlobStorageService blobStorageService;
    private SkillArchiveService archiveService;
    private TeamSkillService service;

    @BeforeEach
    void setUp() {
        teamSkillRepository = mock(TeamSkillRepository.class);
        teamRepository = mock(TeamRepository.class);
        teamService = mock(TeamService.class);
        blobStorageService = mock(BlobStorageService.class);
        archiveService = new SkillArchiveService();
        service = new TeamSkillService(
                teamSkillRepository,
                teamRepository,
                teamService,
                blobStorageService,
                archiveService,
                new SkillFrontmatterParser());
    }

    @Test
    void uploadValidatesFrontmatterStoresArchiveAndRecord() throws Exception {
        byte[] archive = skillArchive("lint", "Run lint");
        Team team = team();
        when(teamSkillRepository.findByTeamIdAndName(TEAM_ID, "lint")).thenReturn(Optional.empty());
        when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(team));
        when(teamSkillRepository.save(any(TeamSkill.class))).thenAnswer(invocation -> {
            TeamSkill skill = invocation.getArgument(0);
            skill.setId(UUID.randomUUID());
            skill.setCreatedAt(Instant.now());
            skill.setUpdatedAt(Instant.now());
            return skill;
        });

        TeamSkillResponse response = service.uploadSkill(
                USER_ID, TEAM_ID, new MockMultipartFile("file", "lint.tar.gz", "application/gzip", archive));

        assertThat(response.name()).isEqualTo("lint");
        assertThat(response.description()).isEqualTo("Run lint");
        assertThat(response.enabled()).isTrue();
        verify(teamService).validateMembership(USER_ID, TEAM_ID);

        ArgumentCaptor<byte[]> content = ArgumentCaptor.forClass(byte[].class);
        verify(blobStorageService)
                .putObject(eq("skills/teams/" + TEAM_ID + "/lint.tar.gz"), content.capture(), eq("application/gzip"));
        assertThat(content.getValue()).isNotEmpty();
    }

    @Test
    void uploadRejectsPathTraversalArchive() throws Exception {
        byte[] archive = maliciousArchive("../../etc/passwd");
        MockMultipartFile file = new MockMultipartFile("file", "bad.tar.gz", "application/gzip", archive);

        assertThatThrownBy(() -> service.uploadSkill(USER_ID, TEAM_ID, file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("path traversal");
        verify(blobStorageService, never()).putObject(any(), any(byte[].class), any());
    }

    @Test
    void uploadRejectsDuplicateName() throws Exception {
        when(teamSkillRepository.findByTeamIdAndName(TEAM_ID, "lint")).thenReturn(Optional.of(skill(TEAM_ID, "lint")));
        MockMultipartFile file =
                new MockMultipartFile("file", "lint.tar.gz", "application/gzip", skillArchive("lint", "Lint"));

        assertThatThrownBy(() -> service.uploadSkill(USER_ID, TEAM_ID, file))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(
                                ((ResponseStatusException) e).getStatusCode().value())
                        .isEqualTo(409));
    }

    @Test
    void updateAppliesPartialChanges() {
        TeamSkill skill = skill(TEAM_ID, "lint");
        when(teamSkillRepository.findByTeamIdAndId(TEAM_ID, skill.getId())).thenReturn(Optional.of(skill));
        when(teamSkillRepository.save(skill)).thenReturn(skill);

        TeamSkillResponse response =
                service.updateSkill(USER_ID, TEAM_ID, skill.getId(), new UpdateTeamSkillRequest(false));

        assertThat(response.enabled()).isFalse();
        assertThat(response.description()).isEqualTo("Run lint");
    }

    @Test
    void uploadRejectsMissingDescription() throws Exception {
        MockMultipartFile file =
                new MockMultipartFile("file", "lint.tar.gz", "application/gzip", rawSkillArchive(null, "name: lint\n"));

        assertThatThrownBy(() -> service.uploadSkill(USER_ID, TEAM_ID, file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires a description");
        verify(blobStorageService, never()).putObject(any(), any(byte[].class), any());
    }

    @Test
    void uploadRejectsNameThatDoesNotMatchWrapperDirectory() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "lint.tar.gz",
                "application/gzip",
                rawSkillArchive("wrapper", "name: lint\ndescription: Lint\n"));

        assertThatThrownBy(() -> service.uploadSkill(USER_ID, TEAM_ID, file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must match its location 'wrapper'");
        verify(blobStorageService, never()).putObject(any(), any(byte[].class), any());
    }

    @Test
    void deleteRemovesArchiveAndRecord() {
        TeamSkill skill = skill(TEAM_ID, "lint");
        when(teamSkillRepository.findByTeamIdAndId(TEAM_ID, skill.getId())).thenReturn(Optional.of(skill));

        service.deleteSkill(USER_ID, TEAM_ID, skill.getId());

        verify(blobStorageService).deleteObject(skill.getBlobPath());
        verify(teamSkillRepository).delete(skill);
    }

    @Test
    void tenantIsolationCannotAccessAnotherTeamsSkill() {
        when(teamSkillRepository.findByTeamIdAndId(eq(TEAM_ID), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getSkill(USER_ID, TEAM_ID, UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(
                                ((ResponseStatusException) e).getStatusCode().value())
                        .isEqualTo(404));
    }

    private Team team() {
        Team team = new Team();
        team.setId(TEAM_ID);
        return team;
    }

    private static TeamSkill skill(UUID teamId, String name) {
        TeamSkill skill = new TeamSkill();
        skill.setId(UUID.randomUUID());
        skill.setTeam(new Team());
        skill.setName(name);
        skill.setDescription("Run lint");
        skill.setBlobPath("skills/teams/" + teamId + "/" + name + ".tar.gz");
        skill.setChecksumSha256("a".repeat(64));
        skill.setEnabled(true);
        skill.setCreatedAt(Instant.now());
        skill.setUpdatedAt(Instant.now());
        return skill;
    }

    private byte[] skillArchive(String name, String description) throws Exception {
        Path skillRoot = Files.createDirectories(directory.resolve("source-" + name));
        Files.writeString(skillRoot.resolve("SKILL.md"), """
                ---
                name: %s
                description: %s
                ---
                """.formatted(name, description));
        return archiveService.createArchive(skillRoot);
    }

    private byte[] rawSkillArchive(String wrapper, String frontmatterBody) throws Exception {
        Path source = Files.createDirectories(directory.resolve("raw-" + System.nanoTime()));
        Path skillRoot = wrapper == null ? source : Files.createDirectories(source.resolve(wrapper));
        Files.writeString(skillRoot.resolve("SKILL.md"), "---\n" + frontmatterBody + "---\n");
        return archiveService.createArchive(source);
    }

    private static byte[] maliciousArchive(String entryName) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(bytes);
                TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
            byte[] payload = "root".getBytes(StandardCharsets.UTF_8);
            TarArchiveEntry entry = new TarArchiveEntry(entryName);
            entry.setSize(payload.length);
            tar.putArchiveEntry(entry);
            tar.write(payload);
            tar.closeArchiveEntry();
        }
        return bytes.toByteArray();
    }
}
