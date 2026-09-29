package com.kratisai.controlplane.config;

import com.kratisai.controlplane.planningagent.PostgresChatMemoryAdapter;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;

@Configuration
@ImportRuntimeHints(AotHints.class)
public class AgentConfig {

    private static final int DEFAULT_MAX_MESSAGES = 20;

    @Bean
    public ChatMemory chatMemory(PostgresChatMemoryAdapter postgresChatMemoryAdapter) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(postgresChatMemoryAdapter)
                .maxMessages(DEFAULT_MAX_MESSAGES)
                .build();
    }
}
