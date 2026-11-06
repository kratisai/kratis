package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.api.wsdto.EnvironmentConnectorResult;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.api.wsdto.ExecStatus;
import com.kratisai.controlplane.config.InstanceProperties;
import com.kratisai.controlplane.config.SandboxProperties;
import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.TeamSkill;
import com.kratisai.controlplane.repository.TeamSkillRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class SkillProvisioningServiceTest {

    private static final UUID TEAM_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID ENVIRONMENT_ID = UUID.fromString("22222222-3333-4444-5555-666666666666");
    private static final UUID EXECUTION_ID = UUID.fromString("abcdef12-3456-7890-abcd-ef1234567890");

    @TempDir
    Path directory;

    private SkillArchiveService archiveService;
    private BlobStorageService blobStorageService;
    private EnvironmentRpcClient environmentRpcClient;
    private TeamSkillRepository teamSkillRepository;
    private SandboxProperties sandboxProperties;
    private SkillProvisioningService service;

    @BeforeEach
    void setUp() throws Exception {
        archiveService = new SkillArchiveService();
        blobStorageService = mock(BlobStorageService.class);
        environmentRpcClient = mock(EnvironmentRpcClient.class);
        teamSkillRepository = mock(TeamSkillRepository.class);
        when(teamSkillRepository.findByTeamIdAndEnabledTrue(TEAM_ID)).thenReturn(List.of());
        InstanceProperties properties = new InstanceProperties();
        properties.setSkillsDirectory(directory.resolve("installation").toString());
        SkillResolverService resolver =
                new SkillResolverService(properties, teamSkillRepository, new SkillFrontmatterParser());
        sandboxProperties = new SandboxProperties();
        sandboxProperties.setWorkspace(directory.resolve("workspace").toString());
        service = new SkillProvisioningService(
                resolver, archiveService, blobStorageService, environmentRpcClient, sandboxProperties);
        when(environmentRpcClient.request(eq(ENVIRONMENT_ID), any(EnvironmentRpcPayload.Exec.class)))
                .thenReturn(new EnvironmentConnectorResult.Exec(ExecStatus.COMPLETED, 0));
    }

    @Test
    void platformSkillsAreTransferredAndExtracted() throws Exception {
        writeSkill(directory.resolve("installation/install-skill"), "install-skill", "Install skill");
        when(teamSkillRepository.findByTeamIdAndEnabledTrue(TEAM_ID)).thenReturn(List.of(teamSkill("team-skill")));
        when(blobStorageService.getObjectBytes("skills/teams/" + TEAM_ID + "/team-skill.tar.gz"))
                .thenReturn(skillArchive("team-skill", "Team skill"));

        List<SkillDefinition> catalog = service.provisionSkills(execution());

        assertThat(catalog).extracting(SkillDefinition::name).containsExactly("install-skill", "team-skill");
        List<String> commands = capturedCommands();
        assertThat(commands).anySatisfy(command -> assertThat(command)
                .contains("mkdir -p '" + sandboxProperties.getSkillsDir() + "/install-skill'"));
        assertThat(commands).anySatisfy(command -> assertThat(command)
                .contains("tar -xzf '/tmp/kratis-skill-install-skill.tar.gz' -C '" + sandboxProperties.getSkillsDir()
                        + "/install-skill'"));
        assertThat(commands).anySatisfy(command -> assertThat(command)
                .contains("chown -R 1000:1000 '" + sandboxProperties.getSkillsDir() + "/install-skill'"));
        assertThat(commands).anySatisfy(command -> assertThat(command)
                .contains("tar -xzf '/tmp/kratis-skill-team-skill.tar.gz' -C '" + sandboxProperties.getSkillsDir()
                        + "/team-skill'"));
    }

    @Test
    void provisioningNeverReadsRepositoryContentFromSandbox() throws Exception {
        when(teamSkillRepository.findByTeamIdAndEnabledTrue(TEAM_ID)).thenReturn(List.of());

        assertThat(service.provisionSkills(execution())).isEmpty();

        verify(environmentRpcClient, never()).request(any(), any(EnvironmentRpcPayload.ReadFileSlice.class));
        assertThat(capturedCommands()).allSatisfy(command -> assertThat(command).doesNotContain(".skills"));
    }

    private List<String> capturedCommands() throws Exception {
        ArgumentCaptor<EnvironmentRpcPayload.Exec> captor = ArgumentCaptor.forClass(EnvironmentRpcPayload.Exec.class);
        verify(environmentRpcClient, org.mockito.Mockito.atLeast(0)).request(eq(ENVIRONMENT_ID), captor.capture());
        return captor.getAllValues().stream()
                .map(EnvironmentRpcPayload.Exec::command)
                .toList();
    }

    private SandboxExecution execution() {
        Team team = new Team();
        team.setId(TEAM_ID);
        ChatEntity chat = new ChatEntity();
        chat.setTeam(team);
        ExecutionEnvironment environment = new ExecutionEnvironment();
        environment.setId(ENVIRONMENT_ID);
        SandboxExecution execution = new SandboxExecution();
        execution.setId(EXECUTION_ID);
        execution.setChat(chat);
        execution.setEnvironment(environment);
        return execution;
    }

    private static TeamSkill teamSkill(String name) {
        TeamSkill skill = new TeamSkill();
        skill.setName(name);
        skill.setDescription("Team skill");
        skill.setBlobPath("skills/teams/" + TEAM_ID + "/" + name + ".tar.gz");
        skill.setChecksumSha256("a".repeat(64));
        skill.setEnabled(true);
        return skill;
    }

    private byte[] skillArchive(String name, String description) throws Exception {
        Path skill = Files.createDirectories(directory.resolve("archives").resolve(name));
        writeSkill(skill, name, description);
        return archiveService.createArchive(skill);
    }

    private static void writeSkill(Path skill, String name, String description) throws Exception {
        Files.createDirectories(skill);
        Files.writeString(skill.resolve("SKILL.md"), """
                ---
                name: %s
                description: %s
                ---
                """.formatted(name, description));
    }
}
