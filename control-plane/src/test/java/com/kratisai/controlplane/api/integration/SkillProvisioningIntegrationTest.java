package com.kratisai.controlplane.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.kratisai.controlplane.api.restdto.TeamSkillResponse;
import com.kratisai.controlplane.api.wsdto.EnvironmentConnectorResult;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.api.wsdto.ExecStatus;
import com.kratisai.controlplane.config.InstanceProperties;
import com.kratisai.controlplane.config.SandboxProperties;
import com.kratisai.controlplane.e2e.AbstractAgentExecutionRealTest;
import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.EnvironmentProvider;
import com.kratisai.controlplane.model.EnvironmentStatus;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.ExecutionEnvironmentType;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.repository.EnvironmentProviderRepository;
import com.kratisai.controlplane.repository.ExecutionEnvironmentRepository;
import com.kratisai.controlplane.service.ActiveTaskPrompt;
import com.kratisai.controlplane.service.EnvironmentRpcClient;
import com.kratisai.controlplane.service.LocalDockerSandboxProvider;
import com.kratisai.controlplane.service.SkillArchiveService;
import com.kratisai.controlplane.service.SkillDefinition;
import com.kratisai.controlplane.service.SkillProvisioningService;
import com.kratisai.controlplane.service.TeamSkillService;
import com.kratisai.controlplane.testsupport.DatabaseCleaner;
import com.kratisai.controlplane.testsupport.SlowTest;
import com.kratisai.controlplane.testsupport.SpringIntegrationTest;
import com.kratisai.controlplane.testsupport.TestDataFactory;
import com.kratisai.controlplane.testsupport.TestIsolationScope;
import com.kratisai.controlplane.testsupport.UseRealNativeBinaries;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.mock.web.MockMultipartFile;

@SpringIntegrationTest
@SlowTest(slotGroup = "skills")
@UseRealNativeBinaries
class SkillProvisioningIntegrationTest {

    @LocalServerPort
    private int port;

    @TempDir
    private Path directory;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private EnvironmentProviderRepository environmentProviderRepository;

    @Autowired
    private ExecutionEnvironmentRepository executionEnvironmentRepository;

    @Autowired
    private TeamSkillService teamSkillService;

    @Autowired
    private SkillArchiveService archiveService;

    @Autowired
    private SkillProvisioningService skillProvisioningService;

    @Autowired
    private EnvironmentRpcClient environmentRpcClient;

    @Autowired
    private LocalDockerSandboxProvider localDockerSandboxProvider;

    @Autowired
    private InstanceProperties instanceProperties;

    @Autowired
    private SandboxProperties sandboxProperties;

    private TestDataFactory.TestContext testContext;
    private ExecutionEnvironment environment;
    private String originalSkillsDirectory;

    @BeforeAll
    static void ensureSandboxImageAndConnectorBuilt() throws Exception {
        AbstractAgentExecutionRealTest.ensureE2eImagesBuilt();
    }

    @BeforeEach
    void setUp() throws Exception {
        databaseCleaner.cleanAll();
        testContext = testDataFactory.createUserAndTeam();
        originalSkillsDirectory = instanceProperties.getSkillsDirectory();
        Path installationDirectory = directory.resolve("installation-skills");
        instanceProperties.setSkillsDirectory(installationDirectory.toString());
        writeSkill(
                installationDirectory.resolve("e2e-team-skill"),
                "e2e-team-skill",
                "Installation team skill",
                "Installation body for team skill.");
        writeSkill(
                installationDirectory.resolve("e2e-duplicate-skill"),
                "e2e-duplicate-skill",
                "Installation duplicate skill",
                "Installation-owned duplicate skill body.");

        uploadTeamSkill("e2e-team-skill", "Team skill", "Team-owned skill body.");
        uploadTeamSkill("e2e-duplicate-skill", "Team duplicate skill", "Team-owned duplicate skill body.");

        EnvironmentProvider provider = environmentProviderRepository
                .findByTeamId(testContext.team().getId())
                .stream()
                .filter(candidate -> "Default Docker Provider".equals(candidate.getName()))
                .findFirst()
                .orElseThrow();
        provider.setDockerImage(TestIsolationScope.runnerImageTag());
        provider = environmentProviderRepository.save(provider);

        ExecutionEnvironment sandbox = new ExecutionEnvironment();
        sandbox.setTeam(testContext.team());
        sandbox.setName("Skills provisioning integration sandbox");
        sandbox.setType(ExecutionEnvironmentType.SANDBOX);
        sandbox.setStatus(EnvironmentStatus.PENDING_CONNECT);
        sandbox.setProvider(provider);
        sandbox.setAuthToken(UUID.randomUUID().toString());
        environment = executionEnvironmentRepository.save(sandbox);

        localDockerSandboxProvider.setConnectUrl("ws://host.docker.internal:" + port + "/ws/env");
        localDockerSandboxProvider.spawnSandbox(environment);
        localDockerSandboxProvider.initializeWorkspace(environment.getId(), environment.getAuthToken());
        await("connector registration")
                .atMost(Duration.ofMinutes(2))
                .pollDelay(Duration.ZERO)
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> assertThat(executionEnvironmentRepository
                                .findById(environment.getId())
                                .orElseThrow()
                                .getStatus())
                        .isEqualTo(EnvironmentStatus.CONNECTED));
    }

    @AfterEach
    void tearDown() {
        try {
            if (environment != null) {
                localDockerSandboxProvider.destroy(environment.getId());
            }
        } finally {
            localDockerSandboxProvider.setConnectUrl(
                    sandboxProperties.getDocker().getConnectUrl());
            if (originalSkillsDirectory != null) {
                instanceProperties.setSkillsDirectory(originalSkillsDirectory);
            }
            databaseCleaner.cleanAll();
        }
    }

    @Test
    void provisionsResolvedSkillsIntoConnectedSandboxAndActiveTask() throws Exception {
        SandboxExecution execution = new SandboxExecution();
        execution.setId(UUID.randomUUID());
        execution.setEnvironment(environment);
        ChatEntity chat = new ChatEntity();
        chat.setTeam(testContext.team());
        execution.setChat(chat);

        List<SkillDefinition> skills = skillProvisioningService.provisionSkills(execution);

        assertThat(skills).extracting(SkillDefinition::name).containsExactly("e2e-duplicate-skill", "e2e-team-skill");
        assertFileExists(".kratis/skills/e2e-team-skill/SKILL.md");
        assertFileExists(".kratis/skills/e2e-duplicate-skill/SKILL.md");
        assertThat(String.join("\n", readSandboxFile(".kratis/skills/e2e-duplicate-skill/SKILL.md")))
                .contains("Team-owned duplicate skill body.")
                .doesNotContain("Installation-owned duplicate skill body.");
        runOnConnector("test ! -e '/tmp/kratis-skill-e2e-team-skill.tar.gz'");
        assertOwnedBySandboxUser(".kratis/skills/e2e-team-skill/SKILL.md");
        assertOwnedBySandboxUser(".kratis/skills/e2e-duplicate-skill/SKILL.md");

        String document = ActiveTaskPrompt.buildActiveTaskDocument(
                "## Task\n\nVerify the available skills.", skills, sandboxProperties.getSkillsDir());
        for (String command : ActiveTaskPrompt.buildWriteCommands(
                document, sandboxProperties.getActiveTaskFile(), sandboxProperties.getPlatformDir())) {
            runOnConnector(command);
        }

        String activeTask = String.join("\n", readSandboxFile(".kratis/ACTIVE_TASK.md"));
        int skillsSectionStart = activeTask.indexOf("## Available Skills");
        int nextSectionStart = activeTask.indexOf("## DOCKER", skillsSectionStart);
        assertThat(skillsSectionStart).isGreaterThanOrEqualTo(0);
        assertThat(nextSectionStart).isGreaterThan(skillsSectionStart);
        String availableSkills = activeTask.substring(skillsSectionStart, nextSectionStart);
        assertThat(availableSkills)
                .contains("| Skill Name | Path | When to Use |")
                .contains("e2e-team-skill")
                .contains(sandboxProperties.getSkillsDir() + "/e2e-team-skill/SKILL.md")
                .contains("e2e-duplicate-skill")
                .contains(sandboxProperties.getSkillsDir() + "/e2e-duplicate-skill/SKILL.md");
    }

    private void uploadTeamSkill(String name, String description, String body) throws Exception {
        Path source = directory.resolve("team-skills").resolve(name);
        writeSkill(source, name, description, body);
        byte[] archive = archiveService.createArchive(source);
        TeamSkillResponse response = teamSkillService.uploadSkill(
                testContext.user().getId(),
                testContext.team().getId(),
                new MockMultipartFile("file", name + ".tar.gz", "application/gzip", archive));
        assertThat(response.name()).isEqualTo(name);
    }

    private static void writeSkill(Path skillDirectory, String name, String description, String body) throws Exception {
        Files.createDirectories(skillDirectory);
        Files.writeString(
                skillDirectory.resolve("SKILL.md"),
                "---\nname: " + name + "\ndescription: " + description + "\n---\n\n" + body + "\n");
    }

    private void assertFileExists(String path) throws Exception {
        runOnConnector("test -f '" + path + "'");
    }

    private void assertOwnedBySandboxUser(String path) throws Exception {
        runOnConnector("test \"$(stat -c %u '" + path + "')\" = 1000");
    }

    private List<String> readSandboxFile(String path) throws Exception {
        EnvironmentConnectorResult.ReadFileSlice result = environmentRpcClient.request(
                environment.getId(),
                new EnvironmentRpcPayload.ReadFileSlice(
                        path, 1, 500, UUID.randomUUID().toString()));
        assertThat(result.path()).isEqualTo(path);
        return result.lines();
    }

    private void runOnConnector(String command) throws Exception {
        EnvironmentConnectorResult.Exec result = environmentRpcClient.request(
                environment.getId(),
                new EnvironmentRpcPayload.Exec(command, false, UUID.randomUUID().toString()));
        assertThat(result.status()).as(String.valueOf(result.error())).isEqualTo(ExecStatus.COMPLETED);
        assertThat(result.exitCode()).isZero();
    }
}
