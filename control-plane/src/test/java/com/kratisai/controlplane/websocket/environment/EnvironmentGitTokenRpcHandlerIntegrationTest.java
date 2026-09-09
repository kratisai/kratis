package com.kratisai.controlplane.websocket.environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.SandboxExecutionScenarioFactory;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.TestDataFactory;
import com.kratisai.controlplane.api.wsdto.EnvironmentResponsePayload.GitTokenResult;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.CredentialType;
import com.kratisai.controlplane.model.RepoCredential;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.service.EnvironmentSessionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.WebSocketSession;

@SpringIntegrationTest
class EnvironmentGitTokenRpcHandlerIntegrationTest {

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private SandboxExecutionScenarioFactory scenarioFactory;

    @Autowired
    private EnvironmentGitTokenRpcHandler handler;

    @Autowired
    private EnvironmentSessionRegistry sessionRegistry;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TestDataFactory.AuthContext auth;
    private ChatEntity chat;
    private String sessionId;

    @BeforeEach
    void setUp() {
        databaseCleaner.cleanAll();
        sessionRegistry.clearAll();
        auth = testDataFactory.createProvisionedContext();
        chat = testDataFactory.createChat(auth.team(), auth.user(), "Git Token Integration Test");
        sessionId = "git-token-session";
    }

    @AfterEach
    void tearDown() {
        sessionRegistry.removeSession(sessionId);
        databaseCleaner.cleanAll();
    }

    @Test
    void handle_resolvesPatTokenAcrossLazyAssociations() {
        // The execution/repository/credential are created and committed in their own
        // transactions, leaving the execution's repository and the repository's credential as
        // uninitialized lazy proxies. The handler must open its own transaction to initialize
        // them, mirroring the real env.git_token request from a sandbox credential server.
        var scenario = new TransactionTemplate(transactionManager).execute(status -> {
            RepoCredential credential =
                    testDataFactory.createCredential(auth.team(), "Fixture PAT", CredentialType.PAT, "plain-pat-token");
            Repository repository = testDataFactory.createRepository(
                    auth.team(), "git-token-repo", "https://github.com/org/repo.git", "main", credential);
            return scenarioFactory.startOnConnector(auth, chat, "Git Token Connector", "git-token-canvas", repository);
        });

        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(sessionId);
        sessionRegistry.registerEnvironmentSession(session, scenario.envId());

        GitTokenResult result = handler.handle(sessionId, 1, new EnvironmentRpcPayload.GitToken())
                .blockLast();

        assertThat(result).isNotNull();
        assertThat(result.token()).isEqualTo("plain-pat-token");
    }
}
