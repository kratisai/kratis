package com.kratisai.controlplane.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.SpringIntegrationTest;
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
class ConfigControllerTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    private MockMvc mockMvc;
    private String authToken;

    @BeforeEach
    void setUp() {
        databaseCleaner.cleanAll();
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        User user = new User("admin@kratis.ai", "hash", "Admin User");
        userRepository.saveAndFlush(user);
        authToken = jwtService.generateAccessToken(user.getId(), user.getEmail());
    }

    @Test
    void getInstallationInfo_unauthenticated_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/config/installation")).andExpect(status().isForbidden());
    }

    @Test
    void getInstallationInfo_authenticated_returns200WithInstallId() throws Exception {
        mockMvc.perform(get("/api/v1/config/installation").header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.installId").isString())
                .andExpect(jsonPath("$.version").exists());
    }

    @Test
    void getInstallationInfo_subsequentCalls_returnSameInstallId() throws Exception {
        String firstCall = mockMvc.perform(
                        get("/api/v1/config/installation").header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String secondCall = mockMvc.perform(
                        get("/api/v1/config/installation").header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(firstCall).isEqualTo(secondCall);
    }
}
