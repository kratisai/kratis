package com.kratisai.controlplane.api.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kratisai.controlplane.model.CanvasEntity;
import com.kratisai.controlplane.model.CanvasType;
import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.ExecutionEnvironmentType;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.SandboxExecutionStatus;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.User;
import com.kratisai.controlplane.repository.CanvasRepository;
import com.kratisai.controlplane.repository.ChatRepository;
import com.kratisai.controlplane.repository.ExecutionEnvironmentRepository;
import com.kratisai.controlplane.repository.SandboxExecutionRepository;
import com.kratisai.controlplane.service.JwtService;
import com.kratisai.controlplane.testsupport.DatabaseCleaner;
import com.kratisai.controlplane.testsupport.SpringIntegrationTest;
import com.kratisai.controlplane.testsupport.TestDataFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringIntegrationTest
class UsageLogControllerTest {

    private MockMvc mockMvc;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private ChatRepository chatRepository;

    @Autowired
    private ExecutionEnvironmentRepository executionEnvironmentRepository;

    @Autowired
    private CanvasRepository canvasRepository;

    @Autowired
    private SandboxExecutionRepository sandboxExecutionRepository;

    private Team team;
    private User user;
    private String authToken;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        databaseCleaner.cleanAll();

        var context = testDataFactory.createUserAndTeam();
        team = context.team();
        user = context.user();
        authToken = jwtService.generateAccessToken(user.getId(), user.getEmail());
    }

    @Test
    void getUsageLogs_executionTitleUsesSpecCanvasTitle() throws Exception {
        ChatEntity chat = chatRepository.save(new ChatEntity(team, user, "Chat title does not name the work"));
        ExecutionEnvironment environment = new ExecutionEnvironment();
        environment.setName("Usage Test Environment");
        environment.setType(ExecutionEnvironmentType.SANDBOX);
        environment.setTeam(team);
        environment.setUser(user);
        environment = executionEnvironmentRepository.save(environment);

        CanvasEntity canvas = new CanvasEntity(chat.getId(), "usage-spec", "Fix: Usage Log Execution Names", "content");
        canvas.setCanvasType(CanvasType.SPEC);
        canvas.setNewRepoName("Test Repository");
        canvas = canvasRepository.save(canvas);

        SandboxExecution execution = new SandboxExecution();
        execution.setChat(chat);
        execution.setEnvironment(environment);
        execution.setCanvas(canvas);
        execution.setTaskPrompt("You are running as a non-root user inside a sandbox...");
        execution.setStatus(SandboxExecutionStatus.COMPLETED);
        sandboxExecutionRepository.save(execution);

        mockMvc.perform(get("/api/v1/teams/" + team.getId() + "/usage-logs?page=0&size=10")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].activityTitle").value("Fix: Usage Log Execution Names"));
    }

    @Test
    void getUsageSummary_shouldReturnMetrics() throws Exception {
        mockMvc.perform(get("/api/v1/teams/" + team.getId() + "/usage-summary")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCost").exists())
                .andExpect(jsonPath("$.totalTokens").exists())
                .andExpect(jsonPath("$.totalOperations").exists());
    }

    @Test
    void getUsageLogs_shouldReturnPaginatedEntries() throws Exception {
        mockMvc.perform(get("/api/v1/teams/" + team.getId() + "/usage-logs?page=0&size=10")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").exists())
                .andExpect(jsonPath("$.number").value(0))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.totalElements").exists())
                .andExpect(jsonPath("$.totalPages").exists());
    }

    @Test
    void getUsageSummary_whenNotMember_shouldReturnForbidden() throws Exception {
        var otherContext = testDataFactory.createUserAndTeam();
        User otherUser = otherContext.user();
        String otherToken = jwtService.generateAccessToken(otherUser.getId(), otherUser.getEmail());

        mockMvc.perform(get("/api/v1/teams/" + team.getId() + "/usage-summary")
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isForbidden());
    }
}
