package com.kratisai.controlplane.planningagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.FakeChatModel;
import com.kratisai.controlplane.model.ModelProvider;
import com.kratisai.controlplane.model.ProviderType;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.User;
import com.kratisai.controlplane.repository.*;
import com.kratisai.controlplane.service.JwtService;
import java.util.UUID;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Shared test harness for agent integration tests. Provides pre-configured beans, mock LLM, and
 * clean database state.
 */
public abstract class AgentTestHarness {

    @LocalServerPort
    protected int port;

    @Autowired
    protected ModelProviderRepository modelProviderRepo;

    @Autowired
    protected TeamRepository teamRepo;

    @Autowired
    protected UserRepository userRepo;

    @Autowired
    protected JwtService jwtService;

    @Autowired
    protected DatabaseCleaner databaseCleaner;

    @Autowired
    protected FakeChatModel fakeChatModel;

    /**
     * Create a test user, team, and model provider, then return a JWT auth token.
     */
    protected void setupTeamAndLogin() {
        String email = "agent-test-" + UUID.randomUUID() + "@example.com";

        User user = new User();
        user.setEmail(email);
        user.setDisplayName("Agent Test");
        user.setPasswordHash("dummy-password-hash");
        user = userRepo.save(user);

        jwtService.generateAccessToken(user.getId(), user.getEmail());
    }

    /**
     * Create a test team with an active model provider.
     */
    protected Team createTeamWithProvider(UUID userId) {
        userRepo.findById(userId).orElseThrow();

        Team team = new Team("Agent Test Team", "Test team for agent integration");
        teamRepo.save(team);

        ModelProvider provider = new ModelProvider("Test Provider", ProviderType.OPENAI, "test-api-key", null);
        provider.setTeam(team);
        provider.setActive(true);
        modelProviderRepo.save(provider);

        return team;
    }

    /** Clean the database state by deleting all test data. */
    protected void cleanDatabase() {
        databaseCleaner.cleanAll();

        // Clear Mock LLM state to prevent cross-test pollution
        fakeChatModel.reset();
    }

    protected void assertToolInvocationReturnedResponse(
            FakeChatModel mockModel, int invocationIndex, String expectedResponseFragment) {

        assertThat(mockModel.getInvocations())
                .as("Expected at least %d invocations", invocationIndex + 1)
                .hasSizeGreaterThan(invocationIndex);

        var invocation = mockModel.getInvocations().get(invocationIndex);
        var prompt = invocation.prompt();
        var toolResponseMessages = prompt.getInstructions().stream()
                .filter(msg -> msg instanceof ToolResponseMessage)
                .map(msg -> (ToolResponseMessage) msg)
                .toList();

        assertThat(toolResponseMessages)
                .as("Expected ToolResponseMessage in invocation %d", invocationIndex)
                .isNotEmpty();

        var toolResponses = toolResponseMessages.getFirst().getResponses();
        assertThat(toolResponses)
                .as("Expected tool responses in invocation %d", invocationIndex)
                .isNotEmpty();

        var response = toolResponses.getFirst();
        assertThat(response.responseData())
                .as("Expected tool response to contain '%s'", expectedResponseFragment)
                .contains(expectedResponseFragment);
    }
}
