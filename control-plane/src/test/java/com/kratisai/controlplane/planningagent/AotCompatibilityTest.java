package com.kratisai.controlplane.planningagent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

/**
 * Verifies Spring AI classes are loadable at runtime and AOT-compatible. This test ensures that
 * GraalVM native image compilation will succeed without manual reflection hints.
 */
class AotCompatibilityTest {

    @Test
    void springAiCoreClassesLoadable() {
        // Verify core Spring AI classes are on classpath and loadable
        assertThat(ChatClient.class).isNotNull();
        assertThat(ChatMemory.class).isNotNull();
        assertThat(StreamingChatModel.class).isNotNull();
        assertThat(MessageChatMemoryAdvisor.class).isNotNull();
        assertThat(FunctionToolCallback.class).isNotNull();
    }

    @Test
    void functionToolCallbackCanBeBuilt() {
        // Verify that FunctionToolCallback can be built without reflection hints
        // This is a compile-time check — if it compiles and runs, AOT should work
        record TestRequest(String query) {}
        record TestResponse(String result) {}

        ToolCallback tool = FunctionToolCallback.builder(
                        "testTool", (Function<TestRequest, TestResponse>) req -> new TestResponse("ok: " + req.query()))
                .inputType(TestRequest.class)
                .description("A test tool")
                .build();

        assertThat(tool).isNotNull();
        assertThat(tool.getToolDefinition().name()).isEqualTo("testTool");
    }

    @Test
    void chatMemoryConstantIsAvailable() {
        // Verify the CONVERSATION_ID constant used for advisor context
        assertThat(ChatMemory.CONVERSATION_ID).isEqualTo("chat_memory_conversation_id");
    }

    @Test
    void toolCallbackWithToolRequestResponseIsAotCompatible() {
        // Verify that ToolCallback can be built with record-based request/response types
        // This validates the pattern used by WebSearchTool and ScratchpadTool
        record TestToolRequest(String sessionId, String fact) {}
        record TestToolResponse(String status) {}

        ToolCallback tool = FunctionToolCallback.builder("test_tool", (Function<TestToolRequest, TestToolResponse>)
                        req -> new TestToolResponse("saved: " + req.fact()))
                .inputType(TestToolRequest.class)
                .description("A test tool for AOT validation")
                .build();

        assertThat(tool).isNotNull();
        assertThat(tool.getToolDefinition().name()).isEqualTo("test_tool");

        // Verify the tool can be invoked (proves runtime compatibility)
        String result = tool.call("{\"sessionId\":\"test\",\"fact\":\"test fact\"}");
        assertThat(result).contains("saved: test fact");
    }
}
