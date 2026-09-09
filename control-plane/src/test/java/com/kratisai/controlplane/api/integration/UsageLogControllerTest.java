package com.kratisai.controlplane.api.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.TestDataFactory;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.User;
import com.kratisai.controlplane.repository.UserRepository;
import com.kratisai.controlplane.service.JwtService;
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
    private UserRepository userRepository;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private WebApplicationContext wac;

    private Team team;
    private String authToken;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        databaseCleaner.cleanAll();

        var context = testDataFactory.createUserAndTeam();
        team = context.team();
        User user = context.user();
        authToken = jwtService.generateAccessToken(user.getId(), user.getEmail());
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
                .andExpect(jsonPath("$.totalElements").exists());
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
