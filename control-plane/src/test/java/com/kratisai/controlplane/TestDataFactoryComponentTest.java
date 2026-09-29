package com.kratisai.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.ExecutionEnvironmentType;
import com.kratisai.controlplane.model.ModelKind;
import com.kratisai.controlplane.service.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@SpringIntegrationTest
class TestDataFactoryComponentTest {

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        databaseCleaner.cleanAll();
    }

    @AfterEach
    void tearDown() {
        databaseCleaner.cleanAll();
    }

    @Test
    void createAuthenticatedContext_issuesJwtAndChatModel() {
        TestDataFactory.AuthContext ctx = testDataFactory.createAuthenticatedContext();

        assertThat(ctx.user().getId()).isNotNull();
        assertThat(ctx.team().getId()).isNotNull();
        assertThat(ctx.accessToken()).isNotBlank();
        assertThat(jwtService.isTokenValid(ctx.accessToken())).isTrue();
        assertThat(jwtService.getUserIdFromToken(ctx.accessToken()))
                .isEqualTo(ctx.user().getId());
        assertThat(ctx.provider().getModels())
                .anyMatch(model -> TestDataFactory.DEFAULT_CHAT_MODEL.equals(model.getModelName())
                        && model.getKind() == ModelKind.CHAT);
        assertThat(ctx.defaultSandbox().getType()).isEqualTo(ExecutionEnvironmentType.SANDBOX);
        assertThat(ctx.defaultSandbox().getId()).isNotNull();
    }

    @Test
    void createProvisionedContext_returnsSameShapeAsAuthenticated() {
        TestDataFactory.AuthContext ctx = testDataFactory.createProvisionedContext();

        assertThat(jwtService.isTokenValid(ctx.accessToken())).isTrue();
        assertThat(ctx.provider().getId()).isNotNull();
        assertThat(ctx.defaultSandbox().getId()).isNotNull();
    }

    @Test
    void createChat_persistsForTeamAndUser() {
        TestDataFactory.AuthContext ctx = testDataFactory.createAuthenticatedContext();
        ChatEntity chat = testDataFactory.createChat(ctx.team(), ctx.user(), "Factory Chat");

        assertThat(chat.getId()).isNotNull();
        assertThat(chat.getTitle()).isEqualTo("Factory Chat");
    }
}
