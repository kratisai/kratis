package com.kratisai.controlplane.api.integration;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.api.restdto.SaveRepoCredentialRequest;
import com.kratisai.controlplane.config.GitHubAppConfig;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.service.JwtService;
import com.kratisai.controlplane.testsupport.DatabaseCleaner;
import com.kratisai.controlplane.testsupport.SpringIntegrationTest;
import com.kratisai.controlplane.testsupport.TestDataFactory;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Verifies that the GitHub App auth method is fully disabled when a deployment does not provide the
 * KRATIS_GITHUB_APP_* configuration (the distributable default).
 */
@SpringIntegrationTest
class GitHubAppDisabledIntegrationTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private GitHubAppConfig gitHubAppConfig;

    private String authToken;
    private UUID teamId;
    private String previousAppId;
    private String previousAppName;
    private String previousPrivateKeyPath;

    @BeforeEach
    void setUp() {
        previousAppId = gitHubAppConfig.getAppId();
        previousAppName = gitHubAppConfig.getAppName();
        previousPrivateKeyPath = gitHubAppConfig.getPrivateKeyPath();
        gitHubAppConfig.setAppId("");
        gitHubAppConfig.setAppName("");
        gitHubAppConfig.setPrivateKeyPath("");

        mockMvc = MockMvcBuilders.webAppContextSetup(wac)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        databaseCleaner.cleanAll();

        TestDataFactory.TestContext ctx = testDataFactory.createUserAndTeam();
        Team team = ctx.team();
        teamId = team.getId();
        authToken =
                jwtService.generateAccessToken(ctx.user().getId(), ctx.user().getEmail());
    }

    @AfterEach
    void tearDown() {
        gitHubAppConfig.setAppId(previousAppId);
        gitHubAppConfig.setAppName(previousAppName);
        gitHubAppConfig.setPrivateKeyPath(previousPrivateKeyPath);
    }

    @Test
    void config_githubApp_shouldReportDisabled() throws Exception {
        mockMvc.perform(get("/api/v1/config/github-app").header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.appId").value(nullValue()))
                .andExpect(jsonPath("$.appName").value(nullValue()))
                .andExpect(jsonPath("$.installationUrl").value(nullValue()));
    }

    @Test
    void saveCredential_githubApp_shouldReturn400() throws Exception {
        SaveRepoCredentialRequest request = new SaveRepoCredentialRequest(
                "GitHub App Install", "GITHUB_APP", null, null, "{\"installationId\":\"456\",\"provider\":\"github\"}");

        mockMvc.perform(post("/api/v1/teams/{teamId}/credentials", teamId)
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("GitHub App authentication is not enabled on this deployment"));
    }

    @Test
    void saveCredential_pat_shouldStillSucceed() throws Exception {
        SaveRepoCredentialRequest request =
                new SaveRepoCredentialRequest("GitHub PAT", "PAT", "ghp_test", null, "{\"provider\":\"github\"}");

        mockMvc.perform(post("/api/v1/teams/{teamId}/credentials", teamId)
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
    }

    @Test
    void validateGitHubAppInstallation_whenDisabled_shouldReturn400() throws Exception {
        mockMvc.perform(post("/api/v1/teams/{teamId}/credentials/validate-github-app-installation", teamId)
                        .param("installationId", "456")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("GitHub App authentication is not enabled on this deployment"));
    }
}
