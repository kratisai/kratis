package com.kratisai.controlplane.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.TestDataFactory;
import com.kratisai.controlplane.api.restdto.CreateChatRequest;
import com.kratisai.controlplane.api.restdto.CreateChatResponse;
import com.kratisai.controlplane.api.wsdto.MessageRole;
import com.kratisai.controlplane.model.ChatMemoryEntity;
import com.kratisai.controlplane.model.ModelProvider;
import com.kratisai.controlplane.model.ProviderType;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.repository.AgentChatMemoryRepository;
import com.kratisai.controlplane.service.JwtService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringIntegrationTest
class ChatCreationTransactionIntegrationTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private AgentChatMemoryRepository chatMemoryRepository;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private WebApplicationContext wac;

    private String authToken;
    private Team team;

    @BeforeEach
    void setUp() {
        mockMvc =
                MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();

        databaseCleaner.cleanAll();

        var context = testDataFactory.createUserAndTeam();
        team = context.team();
        authToken = jwtService.generateAccessToken(
                context.user().getId(), context.user().getEmail());
    }

    @Test
    void createChat_synchronouslyPersistsUserPromptBeforeReturning202() throws Exception {
        ModelProvider provider = testDataFactory.createModelProviderWithLiteLLM(
                team, "OpenAI", ProviderType.OPENAI, "sk-dummy", List.of("gpt-4o"));

        String userPrompt = "Hello, this is a synchronous persistence test prompt!";
        CreateChatRequest createRequest = new CreateChatRequest(team.getId(), provider.getId(), "gpt-4o", userPrompt);

        MvcResult result = mockMvc.perform(post("/api/v1/chats")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest))
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isAccepted())
                .andReturn();

        CreateChatResponse createChatResponse =
                objectMapper.readValue(result.getResponse().getContentAsString(), CreateChatResponse.class);

        UUID chatId = createChatResponse.id();
        assertThat(chatId).isNotNull();

        // Query chat_memory immediately after REST request returns 202
        List<ChatMemoryEntity> memoryEntities = chatMemoryRepository.findByChatIdOrderByCreatedAtAsc(chatId);
        assertThat(memoryEntities).isNotEmpty();
        ChatMemoryEntity userMsg = memoryEntities.getFirst();
        assertThat(userMsg.getMessageType()).isEqualTo(MessageRole.USER);
        assertThat(userMsg.getMessageText()).isEqualTo(userPrompt);
        assertThat(userMsg.getMessageIndex()).isEqualTo(0);
    }
}
