package com.kratisai.controlplane.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.api.rest.GlobalExceptionHandler;
import com.kratisai.controlplane.api.rest.HitlRuleController;
import com.kratisai.controlplane.api.restdto.CreateHitlRuleRequest;
import com.kratisai.controlplane.api.restdto.HitlRuleDto;
import com.kratisai.controlplane.model.HitlRuleAction;
import com.kratisai.controlplane.model.HitlRuleType;
import com.kratisai.controlplane.service.HitlRuleService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class HitlRuleControllerUnitTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private HitlRuleService permissionService;

    private final UUID userId = UUID.randomUUID();
    private final UUID teamId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        HitlRuleController controller = new HitlRuleController(permissionService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listRules_nonMember_returns403() throws Exception {
        when(permissionService.listRules(userId, teamId))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this team"));

        mockMvc.perform(get("/api/v1/teams/{teamId}/hitl-rules", teamId)).andExpect(status().isForbidden());
    }

    @Test
    void listRules_member_returnsRules() throws Exception {
        UUID ruleId = UUID.randomUUID();
        HitlRuleDto dto = new HitlRuleDto(
                ruleId, teamId, "npm test", HitlRuleType.EXACT, HitlRuleAction.ALLOW, userId, "Alice", Instant.now());

        when(permissionService.listRules(userId, teamId)).thenReturn(List.of(dto));

        mockMvc.perform(get("/api/v1/teams/{teamId}/hitl-rules", teamId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(ruleId.toString()))
                .andExpect(jsonPath("$[0].commandRoot").value("npm test"))
                .andExpect(jsonPath("$[0].ruleType").value("EXACT"))
                .andExpect(jsonPath("$[0].action").value("ALLOW"))
                .andExpect(jsonPath("$[0].createdByName").value("Alice"));
    }

    @Test
    void createRule_nonMember_returns403() throws Exception {
        CreateHitlRuleRequest request = new CreateHitlRuleRequest("npm test", HitlRuleType.EXACT, HitlRuleAction.ALLOW);

        when(permissionService.createRule(eq(userId), eq(teamId), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this team"));

        mockMvc.perform(post("/api/v1/teams/{teamId}/hitl-rules", teamId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void createRule_duplicate_returns409() throws Exception {
        CreateHitlRuleRequest request = new CreateHitlRuleRequest("npm test", HitlRuleType.EXACT, HitlRuleAction.ALLOW);

        when(permissionService.createRule(eq(userId), eq(teamId), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Rule already exists"));

        mockMvc.perform(post("/api/v1/teams/{teamId}/hitl-rules", teamId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());
    }

    @Test
    void createRule_success_returns201() throws Exception {
        CreateHitlRuleRequest request =
                new CreateHitlRuleRequest("cargo build", HitlRuleType.PREFIX_WILD, HitlRuleAction.DENY);

        UUID ruleId = UUID.randomUUID();
        HitlRuleDto responseDto = new HitlRuleDto(
                ruleId,
                teamId,
                "cargo build",
                HitlRuleType.PREFIX_WILD,
                HitlRuleAction.DENY,
                userId,
                "Bob",
                Instant.now());

        when(permissionService.createRule(eq(userId), eq(teamId), any())).thenReturn(responseDto);

        mockMvc.perform(post("/api/v1/teams/{teamId}/hitl-rules", teamId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(ruleId.toString()))
                .andExpect(jsonPath("$.commandRoot").value("cargo build"))
                .andExpect(jsonPath("$.ruleType").value("PREFIX_WILD"))
                .andExpect(jsonPath("$.action").value("DENY"))
                .andExpect(jsonPath("$.createdByName").value("Bob"));
    }

    @Test
    void deleteRule_nonMember_returns403() throws Exception {
        UUID ruleId = UUID.randomUUID();
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this team"))
                .when(permissionService)
                .deleteRule(userId, teamId, ruleId);

        mockMvc.perform(delete("/api/v1/teams/{teamId}/hitl-rules/{ruleId}", teamId, ruleId))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteRule_notFound_returns404() throws Exception {
        UUID ruleId = UUID.randomUUID();
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "HITL rule not found"))
                .when(permissionService)
                .deleteRule(userId, teamId, ruleId);

        mockMvc.perform(delete("/api/v1/teams/{teamId}/hitl-rules/{ruleId}", teamId, ruleId))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteRule_success_returns204() throws Exception {
        UUID ruleId = UUID.randomUUID();
        doNothing().when(permissionService).deleteRule(userId, teamId, ruleId);

        mockMvc.perform(delete("/api/v1/teams/{teamId}/hitl-rules/{ruleId}", teamId, ruleId))
                .andExpect(status().isNoContent());

        verify(permissionService).deleteRule(userId, teamId, ruleId);
    }
}
