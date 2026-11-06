package com.kratisai.controlplane.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.api.restdto.UpdateTeamSkillRequest;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.TeamSkill;
import com.kratisai.controlplane.model.User;
import com.kratisai.controlplane.repository.TeamSkillRepository;
import com.kratisai.controlplane.service.BlobStorageService;
import com.kratisai.controlplane.service.JwtService;
import com.kratisai.controlplane.service.SkillArchiveService;
import com.kratisai.controlplane.testsupport.DatabaseCleaner;
import com.kratisai.controlplane.testsupport.SpringIntegrationTest;
import com.kratisai.controlplane.testsupport.TestDataFactory;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringIntegrationTest
class TeamSkillControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private TeamSkillRepository teamSkillRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private SkillArchiveService archiveService;

    @Autowired
    private BlobStorageService blobStorageService;

    @TempDir
    Path directory;

    private String memberToken;
    private String nonMemberToken;
    private Team team;
    private Team otherTeam;
    private User memberUser;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        databaseCleaner.cleanAll();

        TestDataFactory.TestContext memberCtx = testDataFactory.createUserAndTeam();
        team = memberCtx.team();
        memberUser = memberCtx.user();
        memberToken = jwtService.generateAccessToken(memberUser.getId(), memberUser.getEmail());

        TestDataFactory.TestContext otherCtx = testDataFactory.createUserAndTeam();
        otherTeam = otherCtx.team();
        User nonMemberUser = otherCtx.user();
        nonMemberToken = jwtService.generateAccessToken(nonMemberUser.getId(), nonMemberUser.getEmail());
    }

    @Test
    void listSkills_member_returnsSkills() throws Exception {
        TeamSkill skill = skill(team, "lint-" + shortId());
        teamSkillRepository.save(skill);

        mockMvc.perform(get("/api/v1/teams/{teamId}/skills", team.getId())
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value(skill.getName()))
                .andExpect(jsonPath("$[0].enabled").value(true));
    }

    @Test
    void listSkills_nonMember_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/teams/{teamId}/skills", team.getId())
                        .header("Authorization", "Bearer " + nonMemberToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void getSkill_returnsSkillAndMissingReturns404() throws Exception {
        TeamSkill skill = teamSkillRepository.save(skill(team, "get-" + shortId()));

        mockMvc.perform(get("/api/v1/teams/{teamId}/skills/{skillId}", team.getId(), skill.getId())
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(skill.getName()));

        mockMvc.perform(get("/api/v1/teams/{teamId}/skills/{skillId}", team.getId(), UUID.randomUUID())
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void uploadSkill_createsSkillAndBlob() throws Exception {
        String name = "upload-" + shortId();
        MockMultipartFile file = new MockMultipartFile("file", name + ".tar.gz", "application/gzip", archive(name));

        mockMvc.perform(multipart("/api/v1/teams/{teamId}/skills", team.getId())
                        .file(file)
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(name))
                .andExpect(jsonPath("$.description").value("Uploaded skill"));

        assertThat(blobStorageService.exists("skills/teams/" + team.getId() + "/" + name + ".tar.gz"))
                .isTrue();
    }

    @Test
    void uploadSkill_duplicateNameReturns409() throws Exception {
        String name = "duplicate-" + shortId();
        MockMultipartFile file = new MockMultipartFile("file", name + ".tar.gz", "application/gzip", archive(name));

        mockMvc.perform(multipart("/api/v1/teams/{teamId}/skills", team.getId())
                        .file(file)
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isCreated());

        mockMvc.perform(multipart("/api/v1/teams/{teamId}/skills", team.getId())
                        .file(file)
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isConflict());
    }

    @Test
    void uploadSkill_missingDescriptionReturns400() throws Exception {
        String name = "nodesc-" + shortId();
        Path skillRoot = Files.createDirectories(directory.resolve(name));
        Files.writeString(skillRoot.resolve("SKILL.md"), "---\nname: %s\n---\n".formatted(name));
        MockMultipartFile file = new MockMultipartFile(
                "file", name + ".tar.gz", "application/gzip", archiveService.createArchive(skillRoot));

        mockMvc.perform(multipart("/api/v1/teams/{teamId}/skills", team.getId())
                        .file(file)
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void uploadSkill_invalidArchiveReturns400() throws Exception {
        MockMultipartFile file =
                new MockMultipartFile("file", "bad.tar.gz", "application/gzip", "not-an-archive".getBytes());

        mockMvc.perform(multipart("/api/v1/teams/{teamId}/skills", team.getId())
                        .file(file)
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void uploadSkill_pathTraversalReturns400() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "evil.tar.gz", "application/gzip", maliciousArchive());

        mockMvc.perform(multipart("/api/v1/teams/{teamId}/skills", team.getId())
                        .file(file)
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void uploadSkill_nonMemberReturns403() throws Exception {
        MockMultipartFile file =
                new MockMultipartFile("file", "x.tar.gz", "application/gzip", archive("x-" + shortId()));

        mockMvc.perform(multipart("/api/v1/teams/{teamId}/skills", team.getId())
                        .file(file)
                        .header("Authorization", "Bearer " + nonMemberToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateSkill_togglesEnabledAndKeepsFrontmatterDescription() throws Exception {
        TeamSkill skill = teamSkillRepository.save(skill(team, "update-" + shortId()));

        mockMvc.perform(put("/api/v1/teams/{teamId}/skills/{skillId}", team.getId(), skill.getId())
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateTeamSkillRequest(false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Run lint"))
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void updateSkill_missingReturns404() throws Exception {
        mockMvc.perform(put("/api/v1/teams/{teamId}/skills/{skillId}", team.getId(), UUID.randomUUID())
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateTeamSkillRequest(true))))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteSkill_removesRecordAndBlob() throws Exception {
        String name = "delete-" + shortId();
        TeamSkill skill = teamSkillRepository.save(skill(team, name));
        blobStorageService.putObject(skill.getBlobPath(), "archive".getBytes(), "application/gzip");

        mockMvc.perform(delete("/api/v1/teams/{teamId}/skills/{skillId}", team.getId(), skill.getId())
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isNoContent());

        assertThat(teamSkillRepository.findById(skill.getId())).isEmpty();
        assertThat(blobStorageService.exists(skill.getBlobPath())).isFalse();

        mockMvc.perform(delete("/api/v1/teams/{teamId}/skills/{skillId}", team.getId(), skill.getId())
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteSkill_nonMemberReturns403() throws Exception {
        TeamSkill skill = teamSkillRepository.save(skill(team, "forbidden-" + shortId()));

        mockMvc.perform(delete("/api/v1/teams/{teamId}/skills/{skillId}", team.getId(), skill.getId())
                        .header("Authorization", "Bearer " + nonMemberToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void tenantIsolationCannotReadAnotherTeamsSkill() throws Exception {
        TeamSkill otherSkill = teamSkillRepository.save(skill(otherTeam, "other-" + shortId()));

        mockMvc.perform(get("/api/v1/teams/{teamId}/skills/{skillId}", team.getId(), otherSkill.getId())
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isNotFound());
    }

    private byte[] archive(String name) throws Exception {
        Path skillRoot = Files.createDirectories(directory.resolve(name));
        Files.writeString(skillRoot.resolve("SKILL.md"), """
                ---
                name: %s
                description: Uploaded skill
                ---
                """.formatted(name));
        return archiveService.createArchive(skillRoot);
    }

    private static byte[] maliciousArchive() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(bytes);
                TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
            byte[] payload = "root".getBytes(StandardCharsets.UTF_8);
            TarArchiveEntry entry = new TarArchiveEntry("../../etc/passwd");
            entry.setSize(payload.length);
            tar.putArchiveEntry(entry);
            tar.write(payload);
            tar.closeArchiveEntry();
        }
        return bytes.toByteArray();
    }

    private static TeamSkill skill(Team team, String name) {
        TeamSkill skill = new TeamSkill();
        skill.setTeam(team);
        skill.setName(name);
        skill.setDescription("Run lint");
        skill.setBlobPath("skills/teams/" + team.getId() + "/" + name + ".tar.gz");
        skill.setChecksumSha256("a".repeat(64));
        skill.setEnabled(true);
        skill.setCreatedAt(Instant.now());
        skill.setUpdatedAt(Instant.now());
        return skill;
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
