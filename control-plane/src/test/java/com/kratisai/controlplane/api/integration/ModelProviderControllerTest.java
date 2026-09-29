package com.kratisai.controlplane.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.TestDataFactory;
import com.kratisai.controlplane.api.restdto.CreateModelProviderRequest;
import com.kratisai.controlplane.api.restdto.ModelEntryDto;
import com.kratisai.controlplane.api.restdto.SupportedProviderTypeDto;
import com.kratisai.controlplane.api.restdto.TestConnectionRequest;
import com.kratisai.controlplane.api.restdto.UpdateModelProviderRequest;
import com.kratisai.controlplane.model.ModelKind;
import com.kratisai.controlplane.model.ModelProvider;
import com.kratisai.controlplane.model.ProviderType;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.TeamMember;
import com.kratisai.controlplane.model.User;
import com.kratisai.controlplane.repository.TeamMemberRepository;
import com.kratisai.controlplane.repository.TeamRepository;
import com.kratisai.controlplane.repository.UserRepository;
import com.kratisai.controlplane.service.JwtService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.WebApplicationContext;

@SpringIntegrationTest
class ModelProviderControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private TeamMemberRepository teamMemberRepository;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private JwtService jwtService;

    @Autowired
    @Qualifier("modelDiscoveryRestTemplate")
    private RestTemplate modelDiscoveryRestTemplate;

    private MockRestServiceServer modelDiscoveryServer;
    private String authToken;
    private UUID teamId;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        modelDiscoveryServer =
                MockRestServiceServer.bindTo(modelDiscoveryRestTemplate).build();
        databaseCleaner.cleanAll();

        TestDataFactory.TestContext ctx = testDataFactory.createUserAndTeam(false);
        teamId = ctx.team().getId();
        authToken =
                jwtService.generateAccessToken(ctx.user().getId(), ctx.user().getEmail());
    }

    @Test
    void createModelProvider_validRequest_shouldReturn201() throws Exception {
        CreateModelProviderRequest request = new CreateModelProviderRequest(
                "My OpenAI Provider", ProviderType.OPENAI, "sk-test-key", "https://api.openai.com/v1", null);

        mockMvc.perform(post("/api/v1/model-providers/teams/{teamId}", teamId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + authToken)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.displayName").value("My OpenAI Provider"))
                .andExpect(jsonPath("$.providerType").value("OPENAI"))
                .andExpect(jsonPath("$.baseUrl").value("https://api.openai.com/v1"))
                .andExpect(jsonPath("$.isActive").value(true));
    }

    @Test
    void createModelProvider_longBedrockApiKey_shouldReturn201() throws Exception {
        String longKey = "k".repeat(2000);
        CreateModelProviderRequest request = new CreateModelProviderRequest(
                "Bedrock Provider",
                ProviderType.BEDROCK,
                longKey,
                "https://bedrock-runtime.us-east-1.amazonaws.com",
                null);

        mockMvc.perform(post("/api/v1/model-providers/teams/{teamId}", teamId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + authToken)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.providerType").value("BEDROCK"));
    }

    @Test
    void createModelProvider_apiKeyTooLong_shouldReturn400WithFieldError() throws Exception {
        String tooLongKey = "k".repeat(ModelProvider.API_KEY_MAX_LENGTH + 1);
        CreateModelProviderRequest request =
                new CreateModelProviderRequest("Long Key Provider", ProviderType.OPENAI, tooLongKey, null, null);

        mockMvc.perform(post("/api/v1/model-providers/teams/{teamId}", teamId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + authToken)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.errors.apiKey")
                        .value("API key must be at most " + ModelProvider.API_KEY_MAX_LENGTH + " characters"));
    }

    @Test
    void createModelProvider_withOtherType_shouldReturn201() throws Exception {
        CreateModelProviderRequest request = new CreateModelProviderRequest(
                "Custom Provider", ProviderType.OTHER, "custom-api-key", "https://custom-api.example.com/v1", null);

        mockMvc.perform(post("/api/v1/model-providers/teams/{teamId}", teamId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + authToken)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.displayName").value("Custom Provider"))
                .andExpect(jsonPath("$.providerType").value("OTHER"))
                .andExpect(jsonPath("$.baseUrl").value("https://custom-api.example.com/v1"));
    }

    @Test
    void createModelProvider_missingName_shouldReturn400() throws Exception {
        CreateModelProviderRequest request =
                new CreateModelProviderRequest("", ProviderType.OPENAI, "sk-test-key", null, null);

        mockMvc.perform(post("/api/v1/model-providers/teams/{teamId}", teamId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + authToken)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createModelProvider_withoutApiKey_shouldReturn201() throws Exception {
        // Ollama is keyless, so an empty API key is valid
        CreateModelProviderRequest request =
                new CreateModelProviderRequest("Local Ollama", ProviderType.OLLAMA, "", null, null);

        mockMvc.perform(post("/api/v1/model-providers/teams/{teamId}", teamId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + authToken)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.displayName").value("Local Ollama"))
                .andExpect(jsonPath("$.providerType").value("OLLAMA"));
    }

    @Test
    void createModelProvider_duplicateName_shouldReturn409() throws Exception {
        CreateModelProviderRequest request =
                new CreateModelProviderRequest("Duplicate Provider", ProviderType.OPENAI, "sk-key-1", null, null);

        // Create first provider
        mockMvc.perform(post("/api/v1/model-providers/teams/{teamId}", teamId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + authToken)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        // Try to create with same name
        CreateModelProviderRequest duplicateRequest =
                new CreateModelProviderRequest("Duplicate Provider", ProviderType.ANTHROPIC, "sk-key-2", null, null);

        mockMvc.perform(post("/api/v1/model-providers/teams/{teamId}", teamId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + authToken)
                        .content(objectMapper.writeValueAsString(duplicateRequest)))
                .andExpect(status().isConflict());
    }

    @Test
    void listModelProviders_shouldReturnProvidersForTeam() throws Exception {
        // Create a provider first
        CreateModelProviderRequest request =
                new CreateModelProviderRequest("My OpenAI Provider", ProviderType.OPENAI, "sk-test-key", null, null);
        mockMvc.perform(post("/api/v1/model-providers/teams/{teamId}", teamId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + authToken)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        // List providers
        mockMvc.perform(get("/api/v1/model-providers/teams/{teamId}", teamId)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].displayName").value("My OpenAI Provider"));
    }

    @Test
    void listModelProviders_emptyTeam_shouldReturnEmptyArray() throws Exception {
        mockMvc.perform(get("/api/v1/model-providers/teams/{teamId}", teamId)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void getModelProvider_validRequest_shouldReturn200() throws Exception {
        // Create a provider first
        CreateModelProviderRequest request =
                new CreateModelProviderRequest("My OpenAI Provider", ProviderType.OPENAI, "sk-test-key", null, null);
        String providerId = objectMapper
                .readTree(mockMvc.perform(post("/api/v1/model-providers/teams/{teamId}", teamId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Authorization", "Bearer " + authToken)
                                .content(objectMapper.writeValueAsString(request)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("id")
                .asText();

        // Get provider details
        mockMvc.perform(get("/api/v1/model-providers/teams/{teamId}/{providerId}", teamId, providerId)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("My OpenAI Provider"))
                .andExpect(jsonPath("$.id").value(providerId));
    }

    @Test
    void getModelProvider_nonExistent_shouldReturn404() throws Exception {
        UUID nonExistentId = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/model-providers/teams/{teamId}/{providerId}", teamId, nonExistentId)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateModelProvider_validRequest_shouldReturn200() throws Exception {
        // Create a provider first
        CreateModelProviderRequest createRequest =
                new CreateModelProviderRequest("My OpenAI Provider", ProviderType.OPENAI, "sk-test-key", null, null);
        String providerId = objectMapper
                .readTree(mockMvc.perform(post("/api/v1/model-providers/teams/{teamId}", teamId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Authorization", "Bearer " + authToken)
                                .content(objectMapper.writeValueAsString(createRequest)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("id")
                .asText();

        // Update provider
        UpdateModelProviderRequest updateRequest =
                new UpdateModelProviderRequest("Updated Provider", "new-api-key", "https://new-url.com", false, null);
        mockMvc.perform(put("/api/v1/model-providers/teams/{teamId}/{providerId}", teamId, providerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + authToken)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Updated Provider"))
                .andExpect(jsonPath("$.isActive").value(false));
    }

    @Test
    void updateModelProvider_partialUpdate_shouldReturn200() throws Exception {
        // Create a provider first
        CreateModelProviderRequest createRequest =
                new CreateModelProviderRequest("My OpenAI Provider", ProviderType.OPENAI, "sk-test-key", null, null);
        String providerId = objectMapper
                .readTree(mockMvc.perform(post("/api/v1/model-providers/teams/{teamId}", teamId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Authorization", "Bearer " + authToken)
                                .content(objectMapper.writeValueAsString(createRequest)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("id")
                .asText();

        // Partial update - only name
        UpdateModelProviderRequest updateRequest =
                new UpdateModelProviderRequest("Renamed Provider", null, null, null, null);
        mockMvc.perform(put("/api/v1/model-providers/teams/{teamId}/{providerId}", teamId, providerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + authToken)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Renamed Provider"));
    }

    @Test
    void updateModelProvider_withModels_shouldReturn200() throws Exception {
        // Create a provider with an initial model
        CreateModelProviderRequest createRequest = new CreateModelProviderRequest(
                "My OpenAI Provider",
                ProviderType.OPENAI,
                "sk-test-key",
                null,
                List.of(new ModelEntryDto("gpt-4o", ModelKind.CHAT, null, null)));
        String providerId = objectMapper
                .readTree(mockMvc.perform(post("/api/v1/model-providers/teams/{teamId}", teamId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Authorization", "Bearer " + authToken)
                                .content(objectMapper.writeValueAsString(createRequest)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("id")
                .asText();

        // Update the model list — exercises the collection merge path
        UpdateModelProviderRequest updateRequest = new UpdateModelProviderRequest(
                null,
                null,
                null,
                null,
                List.of(
                        new ModelEntryDto("gpt-4o", ModelKind.CHAT, null, null),
                        new ModelEntryDto("text-embedding-ada-002", ModelKind.EMBEDDING, null, null)));
        mockMvc.perform(put("/api/v1/model-providers/teams/{teamId}/{providerId}", teamId, providerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + authToken)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.models", hasSize(2)))
                .andExpect(jsonPath("$.models[0].modelName").value("gpt-4o"))
                .andExpect(jsonPath("$.models[1].kind").value("EMBEDDING"));
    }

    @Test
    void updateModelProvider_nonExistent_shouldReturn404() throws Exception {
        UUID nonExistentId = UUID.randomUUID();
        UpdateModelProviderRequest updateRequest = new UpdateModelProviderRequest("New Name", null, null, null, null);
        mockMvc.perform(put("/api/v1/model-providers/teams/{teamId}/{providerId}", teamId, nonExistentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + authToken)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteModelProvider_validRequest_shouldReturn204() throws Exception {
        // Create a provider first
        CreateModelProviderRequest request =
                new CreateModelProviderRequest("My OpenAI Provider", ProviderType.OPENAI, "sk-test-key", null, null);
        String providerId = objectMapper
                .readTree(mockMvc.perform(post("/api/v1/model-providers/teams/{teamId}", teamId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Authorization", "Bearer " + authToken)
                                .content(objectMapper.writeValueAsString(request)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("id")
                .asText();

        // Delete provider
        mockMvc.perform(delete("/api/v1/model-providers/teams/{teamId}/{providerId}", teamId, providerId)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNoContent());

        // Verify it's gone
        mockMvc.perform(get("/api/v1/model-providers/teams/{teamId}/{providerId}", teamId, providerId)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteModelProvider_nonExistent_shouldReturn404() throws Exception {
        UUID nonExistentId = UUID.randomUUID();
        mockMvc.perform(delete("/api/v1/model-providers/teams/{teamId}/{providerId}", teamId, nonExistentId)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void createModelProvider_nonMember_shouldReturn403() throws Exception {
        // Create another user who is not a member of the team
        User otherUser = new User();
        otherUser.setEmail("otheruser@example.com");
        otherUser.setDisplayName("Other User");
        otherUser.setPasswordHash("dummy");
        otherUser = userRepository.save(otherUser);

        String otherAuthToken = jwtService.generateAccessToken(otherUser.getId(), otherUser.getEmail());

        CreateModelProviderRequest request =
                new CreateModelProviderRequest("My OpenAI Provider", ProviderType.OPENAI, "sk-test-key", null, null);

        mockMvc.perform(post("/api/v1/model-providers/teams/{teamId}", teamId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + otherAuthToken)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateModelProvider_nonOwner_shouldReturn403() throws Exception {
        // Create a member (non-owner) user
        User memberUser = new User();
        memberUser.setEmail("member@example.com");
        memberUser.setDisplayName("Team Member");
        memberUser.setPasswordHash("dummy");
        memberUser = userRepository.save(memberUser);

        // Add as member to the team
        Team team = teamRepository.findById(teamId).orElseThrow();
        teamMemberRepository.save(new TeamMember(memberUser, team, "member"));

        String memberAuthToken = jwtService.generateAccessToken(memberUser.getId(), memberUser.getEmail());

        // Create a provider as owner first
        CreateModelProviderRequest createRequest =
                new CreateModelProviderRequest("My OpenAI Provider", ProviderType.OPENAI, "sk-test-key", null, null);
        String providerId = objectMapper
                .readTree(mockMvc.perform(post("/api/v1/model-providers/teams/{teamId}", teamId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Authorization", "Bearer " + authToken)
                                .content(objectMapper.writeValueAsString(createRequest)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("id")
                .asText();

        // Try to update as member (should fail)
        UpdateModelProviderRequest updateRequest =
                new UpdateModelProviderRequest("Updated Name", null, null, null, null);
        mockMvc.perform(put("/api/v1/model-providers/teams/{teamId}/{providerId}", teamId, providerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + memberAuthToken)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteModelProvider_nonOwner_shouldReturn403() throws Exception {
        // Create a member (non-owner) user
        User memberUser = new User();
        memberUser.setEmail("member2@example.com");
        memberUser.setDisplayName("Team Member 2");
        memberUser.setPasswordHash("dummy");
        memberUser = userRepository.save(memberUser);

        // Add as member to the team
        Team team = teamRepository.findById(teamId).orElseThrow();
        teamMemberRepository.save(new TeamMember(memberUser, team, "member"));

        String memberAuthToken = jwtService.generateAccessToken(memberUser.getId(), memberUser.getEmail());

        // Create a provider as owner first
        CreateModelProviderRequest createRequest =
                new CreateModelProviderRequest("My OpenAI Provider", ProviderType.OPENAI, "sk-test-key", null, null);
        String providerId = objectMapper
                .readTree(mockMvc.perform(post("/api/v1/model-providers/teams/{teamId}", teamId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Authorization", "Bearer " + authToken)
                                .content(objectMapper.writeValueAsString(createRequest)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("id")
                .asText();

        // Try to delete as member (should fail)
        mockMvc.perform(delete("/api/v1/model-providers/teams/{teamId}/{providerId}", teamId, providerId)
                        .header("Authorization", "Bearer " + memberAuthToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void getSupportedTypes_shouldReturnAllProviderTypes() throws Exception {
        MvcResult result = mockMvc.perform(
                        get("/api/v1/model-providers/supported-types").header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$", hasSize(10)))
                .andExpect(jsonPath("$[0].supportsModelDiscovery").doesNotExist())
                .andReturn();

        List<SupportedProviderTypeDto> types = objectMapper.readValue(
                result.getResponse().getContentAsString(),
                objectMapper.getTypeFactory().constructCollectionType(List.class, SupportedProviderTypeDto.class));

        assertThat(types)
                .extracting(SupportedProviderTypeDto::type)
                .containsExactlyInAnyOrder(
                        "ANTHROPIC",
                        "AZURE_OPENAI",
                        "BEDROCK",
                        "DEEPSEEK",
                        "GOOGLE",
                        "GROQ",
                        "MISTRAL",
                        "OLLAMA",
                        "OPENAI",
                        "OTHER");

        SupportedProviderTypeDto ollama = types.stream()
                .filter(t -> t.type().equals("OLLAMA"))
                .findFirst()
                .orElseThrow();
        assertThat(ollama.requiresApiKey()).isFalse();
        assertThat(ollama.requiresBaseUrl()).isTrue();

        SupportedProviderTypeDto openAi = types.stream()
                .filter(t -> t.type().equals("OPENAI"))
                .findFirst()
                .orElseThrow();
        assertThat(openAi.requiresApiKey()).isTrue();

        // Bedrock uses a standard bearer API key against its OpenAI-compatible endpoint; the base
        // URL carries the region
        SupportedProviderTypeDto bedrock = types.stream()
                .filter(t -> t.type().equals("BEDROCK"))
                .findFirst()
                .orElseThrow();
        assertThat(bedrock.requiresApiKey()).isTrue();
        assertThat(bedrock.requiresBaseUrl()).isTrue();
    }

    @Test
    void testConnection_success_shouldReturnDiscoveredModels() throws Exception {
        modelDiscoveryServer
                .expect(requestTo("https://api.openai.com/v1/models"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer sk-test-key"))
                .andRespond(withSuccess(
                        "{\"data\": [{\"id\": \"gpt-4o\"}, {\"id\": \"text-embedding-3-small\"}]}",
                        MediaType.APPLICATION_JSON));

        TestConnectionRequest request = new TestConnectionRequest(ProviderType.OPENAI, "sk-test-key", null);

        mockMvc.perform(post("/api/v1/model-providers/test-connection")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + authToken)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.models[0].modelName").value("gpt-4o"))
                .andExpect(jsonPath("$.models[0].kind").value("CHAT"))
                .andExpect(jsonPath("$.models[1].modelName").value("text-embedding-3-small"))
                .andExpect(jsonPath("$.models[1].kind").value("EMBEDDING"));

        modelDiscoveryServer.verify();
    }

    @Test
    void testConnection_reportsDiscoveredContextWindow() throws Exception {
        modelDiscoveryServer
                .expect(requestTo("https://generativelanguage.googleapis.com/v1beta/models?key=test-key"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"models\": [{\"name\": \"models/gemini-2.0-flash\", \"inputTokenLimit\": 1048576}]}",
                        MediaType.APPLICATION_JSON));

        TestConnectionRequest request = new TestConnectionRequest(ProviderType.GOOGLE, "test-key", null);

        mockMvc.perform(post("/api/v1/model-providers/test-connection")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + authToken)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.models[0].modelName").value("gemini-2.0-flash"))
                .andExpect(jsonPath("$.models[0].contextWindowTokens").value(1048576));

        modelDiscoveryServer.verify();
    }

    @Test
    void testConnection_keylessProvider_shouldNotRequireApiKey() throws Exception {
        modelDiscoveryServer
                .expect(requestTo("http://localhost:11434/api/tags"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"models\": [{\"name\": \"llama3:latest\"}]}", MediaType.APPLICATION_JSON));

        TestConnectionRequest request = new TestConnectionRequest(ProviderType.OLLAMA, null, null);

        mockMvc.perform(post("/api/v1/model-providers/test-connection")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + authToken)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.models[0].modelName").value("llama3:latest"));

        modelDiscoveryServer.verify();
    }

    @Test
    void testConnection_invalidCredentials_shouldReturnFailure() throws Exception {
        modelDiscoveryServer
                .expect(requestTo("http://localhost:1/models"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withUnauthorizedRequest());

        TestConnectionRequest request = new TestConnectionRequest(ProviderType.OPENAI, "bad-key", "http://localhost:1");

        mockMvc.perform(post("/api/v1/model-providers/test-connection")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + authToken)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").isNotEmpty());

        modelDiscoveryServer.verify();
    }

    @Test
    void testConnection_missingProviderType_shouldReturn400() throws Exception {
        mockMvc.perform(post("/api/v1/model-providers/test-connection")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + authToken)
                        .content("{\"apiKey\":\"sk-test-key\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testConnection_longBedrockApiKey_shouldNotBeRejectedByValidation() throws Exception {
        String longKey = "k".repeat(2000);
        modelDiscoveryServer
                .expect(requestTo("https://bedrock-runtime.us-east-1.amazonaws.com/models"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + longKey))
                .andRespond(withSuccess("{\"data\": [{\"id\": \"anthropic.claude-3\"}]}", MediaType.APPLICATION_JSON));

        TestConnectionRequest request = new TestConnectionRequest(
                ProviderType.BEDROCK, longKey, "https://bedrock-runtime.us-east-1.amazonaws.com");

        mockMvc.perform(post("/api/v1/model-providers/test-connection")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + authToken)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.models[0].modelName").value("anthropic.claude-3"));

        modelDiscoveryServer.verify();
    }

    @Test
    void testConnection_apiKeyTooLong_shouldReturn400WithFieldError() throws Exception {
        TestConnectionRequest request =
                new TestConnectionRequest(ProviderType.OPENAI, "k".repeat(ModelProvider.API_KEY_MAX_LENGTH + 1), null);

        mockMvc.perform(post("/api/v1/model-providers/test-connection")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + authToken)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.apiKey")
                        .value("API key must be at most " + ModelProvider.API_KEY_MAX_LENGTH + " characters"));
    }

    @Test
    void discoverModels_validProvider_shouldReturnModels() throws Exception {
        modelDiscoveryServer
                .expect(requestTo("https://api.openai.com/v1/models"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer sk-test-key"))
                .andRespond(withSuccess(
                        "{\"data\": [{\"id\": \"gpt-4o\"}, {\"id\": \"text-embedding-3-small\"}]}",
                        MediaType.APPLICATION_JSON));

        CreateModelProviderRequest createRequest =
                new CreateModelProviderRequest("My OpenAI Provider", ProviderType.OPENAI, "sk-test-key", null, null);
        String providerId = objectMapper
                .readTree(mockMvc.perform(post("/api/v1/model-providers/teams/{teamId}", teamId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Authorization", "Bearer " + authToken)
                                .content(objectMapper.writeValueAsString(createRequest)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("id")
                .asText();

        mockMvc.perform(get("/api/v1/model-providers/{providerId}/discover-models", providerId)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].modelName").value("gpt-4o"))
                .andExpect(jsonPath("$[0].kind").value("CHAT"))
                .andExpect(jsonPath("$[1].modelName").value("text-embedding-3-small"))
                .andExpect(jsonPath("$[1].kind").value("EMBEDDING"));

        modelDiscoveryServer.verify();
    }

    @Test
    void discoverModels_anthropicProvider_shouldReturnModels() throws Exception {
        modelDiscoveryServer
                .expect(requestTo("https://api.anthropic.com/v1/models"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("x-api-key", "sk-test-key"))
                .andExpect(header("anthropic-version", "2023-06-01"))
                .andRespond(withSuccess(
                        "{\"data\": [{\"id\": \"claude-3-5-sonnet-20241022\"}]}", MediaType.APPLICATION_JSON));

        CreateModelProviderRequest createRequest = new CreateModelProviderRequest(
                "My Anthropic Provider", ProviderType.ANTHROPIC, "sk-test-key", null, null);
        String providerId = objectMapper
                .readTree(mockMvc.perform(post("/api/v1/model-providers/teams/{teamId}", teamId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Authorization", "Bearer " + authToken)
                                .content(objectMapper.writeValueAsString(createRequest)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("id")
                .asText();

        mockMvc.perform(get("/api/v1/model-providers/{providerId}/discover-models", providerId)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].modelName").value("claude-3-5-sonnet-20241022"))
                .andExpect(jsonPath("$[0].kind").value("CHAT"));

        modelDiscoveryServer.verify();
    }

    @Test
    void discoverModels_nonMember_shouldReturn403() throws Exception {
        // Create another user who is not a member of the team
        User otherUser = new User();
        otherUser.setEmail("outsider@example.com");
        otherUser.setDisplayName("Outsider");
        otherUser.setPasswordHash("dummy");
        otherUser = userRepository.save(otherUser);

        String otherAuthToken = jwtService.generateAccessToken(otherUser.getId(), otherUser.getEmail());

        CreateModelProviderRequest createRequest =
                new CreateModelProviderRequest("My OpenAI Provider", ProviderType.OPENAI, "sk-test-key", null, null);
        String providerId = objectMapper
                .readTree(mockMvc.perform(post("/api/v1/model-providers/teams/{teamId}", teamId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Authorization", "Bearer " + authToken)
                                .content(objectMapper.writeValueAsString(createRequest)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("id")
                .asText();

        mockMvc.perform(get("/api/v1/model-providers/{providerId}/discover-models", providerId)
                        .header("Authorization", "Bearer " + otherAuthToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void discoverModels_nonExistentProvider_shouldReturn404() throws Exception {
        UUID nonExistentId = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/model-providers/{providerId}/discover-models", nonExistentId)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNotFound());
    }
}
