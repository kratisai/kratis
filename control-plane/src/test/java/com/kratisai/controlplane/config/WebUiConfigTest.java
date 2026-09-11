package com.kratisai.controlplane.config;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kratisai.controlplane.SpringIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringIntegrationTest
@DisplayName("Web UI")
class WebUiConfigTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("serves the SPA shell at the root without authentication")
    void servesSpaShellAtRoot() throws Exception {
        mockMvc.perform(get("/")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("serves the SPA shell and static assets without authentication")
    void servesStaticAssets() throws Exception {
        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("kratis-spa-shell-marker")));
        mockMvc.perform(get("/assets/app.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("kratis-spa-asset-marker")));
    }

    @Test
    @DisplayName("forwards client-side routes to the SPA shell")
    void forwardsClientRoutesToSpaShell() throws Exception {
        mockMvc.perform(get("/settings")).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
        mockMvc.perform(get("/repos/42")).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
        mockMvc.perform(get("/wiki/1/getting-started"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
    }

    @Test
    @DisplayName("requires authentication for API routes")
    void requiresAuthenticationForApiRoutes() throws Exception {
        mockMvc.perform(get("/api/v1/config/installation")).andExpect(status().isForbidden());
    }
}
