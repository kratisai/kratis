package com.kratisai.controlplane;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.UUID;

/**
 * Standalone utility class for building LLM API response bodies in various formats. Extracted from
 * {@link WireMockLlmServer} so that response builders can be used independently of the WireMock
 * server configuration.
 *
 * <p>Each method produces a complete response body string (JSON or SSE) suitable for use with
 * {@link HttpRequestMatcher.Builder#jsonResponse(String)} or {@link
 * HttpRequestMatcher.Builder#sseResponse(String)}.
 */
public final class LlmResponseBuilders {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private LlmResponseBuilders() {
        // Utility class
    }

    // --- OpenAI Chat Completions (non-streaming JSON) ---

    /**
     * Builds an OpenAI Chat Completions JSON response with a single text assistant message.
     *
     * @param content the assistant message content
     * @return JSON string
     */
    public static String openAiText(String content) {
        try {
            ObjectNode root = createCompletionRoot();
            ArrayNode choices = objectMapper.createArrayNode();
            choices.add(createChoice(0, createTextMessage(content), "stop"));
            root.set("choices", choices);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build OpenAI text response", e);
        }
    }

    /**
     * Builds an OpenAI Chat Completions JSON response with a single tool call.
     *
     * @param functionName the function name to call
     * @param argumentsJson the JSON arguments string
     * @return JSON string
     */
    public static String openAiToolCall(String functionName, String argumentsJson) {
        try {
            String toolCallId = "call_" + UUID.randomUUID().toString().substring(0, 8);

            ObjectNode root = createCompletionRoot();
            ArrayNode choices = objectMapper.createArrayNode();
            choices.add(createChoice(0, createToolCallMessage(toolCallId, functionName, argumentsJson), "tool_calls"));
            root.set("choices", choices);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build OpenAI tool call response", e);
        }
    }

    // --- OpenAI Chat Completions (SSE streaming) ---

    /**
     * Builds an OpenAI-compatible SSE streaming response with a text message. Returns chunks in the
     * standard OpenAI streaming format with {@code data:} prefixed lines and {@code [DONE]}
     * terminator.
     *
     * @param content the text content
     * @return SSE-formatted string
     */
    public static String openAiSseText(String content) {
        try {
            String chunkId = "chatcmpl-" + UUID.randomUUID().toString().substring(0, 8);

            // Content chunk with the text
            ObjectNode delta = objectMapper.createObjectNode();
            delta.put("role", "assistant");
            delta.put("content", content);

            ObjectNode choice = objectMapper.createObjectNode();
            choice.put("index", 0);
            choice.set("delta", delta);
            choice.putNull("finish_reason");

            ObjectNode chunk = objectMapper.createObjectNode();
            chunk.put("id", chunkId);
            chunk.put("object", "chat.completion.chunk");
            chunk.put("model", "gpt-4o");
            chunk.put("created", System.currentTimeMillis() / 1000);
            ArrayNode choices = objectMapper.createArrayNode();
            choices.add(choice);
            chunk.set("choices", choices);

            String dataLine = "data: " + objectMapper.writeValueAsString(chunk) + "\n\n";

            // Final chunk with finish_reason
            ObjectNode finalChoice = objectMapper.createObjectNode();
            finalChoice.put("index", 0);
            finalChoice.set("delta", objectMapper.createObjectNode());
            finalChoice.put("finish_reason", "stop");
            ObjectNode finalChunk = objectMapper.createObjectNode();
            finalChunk.put("id", chunkId);
            finalChunk.put("object", "chat.completion.chunk");
            finalChunk.put("model", "gpt-4o");
            finalChunk.put("created", System.currentTimeMillis() / 1000);
            ArrayNode finalChoices = objectMapper.createArrayNode();
            finalChoices.add(finalChoice);
            finalChunk.set("choices", finalChoices);

            String finalLine = "data: " + objectMapper.writeValueAsString(finalChunk) + "\n\n";

            // Usage chunk (required when stream_options.include_usage is true)
            String usageLine = buildUsageChunk(chunkId, System.currentTimeMillis() / 1000);

            return dataLine + finalLine + usageLine + "data: [DONE]\n\n";
        } catch (Exception e) {
            throw new RuntimeException("Failed to build OpenAI SSE text response", e);
        }
    }

    /**
     * Builds an OpenAI-compatible SSE streaming response with a tool call. Returns chunks in the
     * standard OpenAI streaming format with {@code data:} prefixed lines and {@code [DONE]}
     * terminator.
     *
     * @param functionName the function name
     * @param argumentsJson the JSON arguments string
     * @return SSE-formatted string
     */
    public static String openAiSseToolCall(String functionName, String argumentsJson) {
        try {
            String chunkId = "chatcmpl-" + UUID.randomUUID().toString().substring(0, 8);
            String toolCallId = "call_" + UUID.randomUUID().toString().substring(0, 8);
            long created = System.currentTimeMillis() / 1000;

            ObjectNode toolCall1 = objectMapper.createObjectNode();
            toolCall1.put("index", 0);
            toolCall1.put("id", toolCallId);
            toolCall1.put("type", "function");
            ObjectNode function1 = objectMapper.createObjectNode();
            function1.put("name", functionName);
            function1.put("arguments", argumentsJson);
            toolCall1.set("function", function1);

            ObjectNode delta1 = objectMapper.createObjectNode();
            delta1.put("role", "assistant");
            ArrayNode toolCalls1 = objectMapper.createArrayNode();
            toolCalls1.add(toolCall1);
            delta1.set("tool_calls", toolCalls1);

            String chunk1 = buildSseChunk(chunkId, created, delta1, null);

            ObjectNode delta2 = objectMapper.createObjectNode();
            String chunk2 = buildSseChunk(chunkId, created, delta2, "tool_calls");

            // Usage chunk (required when stream_options.include_usage is true)
            String usageLine = buildUsageChunk(chunkId, created);

            return chunk1 + chunk2 + usageLine + "data: [DONE]\n\n";
        } catch (Exception e) {
            throw new RuntimeException("Failed to build OpenAI SSE tool call response", e);
        }
    }

    /**
     * Builds a usage chunk for OpenAI SSE streaming responses. This chunk is required when
     * stream_options.include_usage is true in the request.
     *
     * @param chunkId the chunk ID
     * @param created the created timestamp
     * @return SSE-formatted string for the usage chunk
     */
    private static String buildUsageChunk(String chunkId, long created) {
        try {
            ObjectNode usage = objectMapper.createObjectNode();
            usage.put("prompt_tokens", 100);
            usage.put("completion_tokens", 50);
            usage.put("total_tokens", 150);

            ObjectNode chunk = objectMapper.createObjectNode();
            chunk.put("id", chunkId);
            chunk.put("object", "chat.completion.chunk");
            chunk.put("model", "gpt-4o");
            chunk.put("created", created);
            chunk.set("choices", objectMapper.createArrayNode());
            chunk.set("usage", usage);

            return "data: " + objectMapper.writeValueAsString(chunk) + "\n\n";
        } catch (Exception e) {
            throw new RuntimeException("Failed to build usage chunk", e);
        }
    }

    /**
     * Builds a single SSE chunk line in the OpenAI streaming format.
     *
     * @param chunkId the chunk ID
     * @param created the created timestamp
     * @param delta the delta object
     * @param finishReason the finish reason (null if not finished)
     * @return SSE-formatted string for one chunk
     */
    private static String buildSseChunk(String chunkId, long created, ObjectNode delta, String finishReason) {
        try {
            ObjectNode choice = objectMapper.createObjectNode();
            choice.put("index", 0);
            choice.set("delta", delta);
            if (finishReason != null) {
                choice.put("finish_reason", finishReason);
            } else {
                choice.putNull("finish_reason");
            }

            ObjectNode chunk = objectMapper.createObjectNode();
            chunk.put("id", chunkId);
            chunk.put("object", "chat.completion.chunk");
            chunk.put("model", "gpt-4o");
            chunk.put("created", created);
            ArrayNode choices = objectMapper.createArrayNode();
            choices.add(choice);
            chunk.set("choices", choices);

            return "data: " + objectMapper.writeValueAsString(chunk) + "\n\n";
        } catch (Exception e) {
            throw new RuntimeException("Failed to build SSE chunk", e);
        }
    }

    // --- OpenAI Responses API (SSE streaming) ---

    /**
     * Builds an SSE streaming response for the OpenAI Responses API with a text message. Emits
     * response.created, output_item.added, content_part.added, output_text.delta, output_text.done,
     * output_item.done, and response.completed events.
     *
     * @param content the text content
     * @return SSE-formatted string
     */
    public static String responsesSseText(String content) {
        try {
            String respId = "resp_" + UUID.randomUUID().toString().substring(0, 8);
            String msgId = "msg_" + UUID.randomUUID().toString().substring(0, 8);

            ObjectNode responseObj = objectMapper.createObjectNode();
            responseObj.put("id", respId);
            responseObj.put("object", "response");
            responseObj.put("status", "completed");
            ArrayNode output = objectMapper.createArrayNode();
            ObjectNode messageItem = objectMapper.createObjectNode();
            messageItem.put("type", "message");
            messageItem.put("id", msgId);
            messageItem.put("role", "assistant");
            ArrayNode contentArray = objectMapper.createArrayNode();
            ObjectNode textItem = objectMapper.createObjectNode();
            textItem.put("type", "output_text");
            textItem.put("text", content);
            contentArray.add(textItem);
            messageItem.set("content", contentArray);
            output.add(messageItem);
            responseObj.set("output", output);
            ObjectNode usage = objectMapper.createObjectNode();
            usage.put("input_tokens", 100);
            usage.put("output_tokens", 50);
            usage.put("total_tokens", 150);
            responseObj.set("usage", usage);

            String responseJson = objectMapper.writeValueAsString(responseObj);

            ObjectNode createdEvent = objectMapper.createObjectNode();
            createdEvent.put("type", "response.created");
            createdEvent.set("response", objectMapper.readTree(responseJson));

            ObjectNode itemAdded = objectMapper.createObjectNode();
            itemAdded.put("type", "response.output_item.added");
            itemAdded.put("output_index", 0);
            itemAdded.set("item", objectMapper.readTree(objectMapper.writeValueAsString(messageItem)));

            ObjectNode partAdded = objectMapper.createObjectNode();
            partAdded.put("type", "response.content_part.added");
            partAdded.put("output_index", 0);
            partAdded.put("content_index", 0);
            partAdded.set("part", objectMapper.readTree(objectMapper.writeValueAsString(textItem)));

            ObjectNode textDelta = objectMapper.createObjectNode();
            textDelta.put("type", "response.output_text.delta");
            textDelta.put("output_index", 0);
            textDelta.put("content_index", 0);
            textDelta.put("delta", content);

            ObjectNode textDone = objectMapper.createObjectNode();
            textDone.put("type", "response.output_text.done");
            textDone.put("output_index", 0);
            textDone.put("content_index", 0);
            textDone.put("text", content);

            ObjectNode itemDone = objectMapper.createObjectNode();
            itemDone.put("type", "response.output_item.done");
            itemDone.put("output_index", 0);
            itemDone.set("item", objectMapper.readTree(objectMapper.writeValueAsString(messageItem)));

            ObjectNode completedEvent = objectMapper.createObjectNode();
            completedEvent.put("type", "response.completed");
            completedEvent.set("response", objectMapper.readTree(responseJson));

            return formatSseEvent("response.created", objectMapper.writeValueAsString(createdEvent))
                    + formatSseEvent("response.output_item.added", objectMapper.writeValueAsString(itemAdded))
                    + formatSseEvent("response.content_part.added", objectMapper.writeValueAsString(partAdded))
                    + formatSseEvent("response.output_text.delta", objectMapper.writeValueAsString(textDelta))
                    + formatSseEvent("response.output_text.done", objectMapper.writeValueAsString(textDone))
                    + formatSseEvent("response.output_item.done", objectMapper.writeValueAsString(itemDone))
                    + formatSseEvent("response.completed", objectMapper.writeValueAsString(completedEvent));
        } catch (Exception e) {
            throw new RuntimeException("Failed to build Responses SSE text response", e);
        }
    }

    /**
     * Builds an SSE streaming response for the OpenAI Responses API with a function tool call.
     *
     * @param functionName the function name
     * @param argumentsJson the JSON arguments string
     * @return SSE-formatted string
     */
    public static String responsesSseToolCall(String functionName, String argumentsJson) {
        try {
            String respId = "resp_" + UUID.randomUUID().toString().substring(0, 8);
            String callId = "call_" + UUID.randomUUID().toString().substring(0, 8);

            ObjectNode responseObj = objectMapper.createObjectNode();
            responseObj.put("id", respId);
            responseObj.put("object", "response");
            responseObj.put("status", "completed");

            ArrayNode output = objectMapper.createArrayNode();
            ObjectNode toolCallItem = objectMapper.createObjectNode();
            toolCallItem.put("type", "function_call");
            toolCallItem.put("id", callId);
            toolCallItem.put("name", functionName);
            toolCallItem.put("arguments", argumentsJson);
            toolCallItem.put("call_id", callId);
            output.add(toolCallItem);
            responseObj.set("output", output);

            ObjectNode usage = objectMapper.createObjectNode();
            usage.put("input_tokens", 100);
            usage.put("output_tokens", 50);
            usage.put("total_tokens", 150);
            responseObj.set("usage", usage);

            String responseJson = objectMapper.writeValueAsString(responseObj);

            ObjectNode createdEvent = objectMapper.createObjectNode();
            createdEvent.put("type", "response.created");
            createdEvent.set("response", objectMapper.readTree(responseJson));

            ObjectNode itemAdded = objectMapper.createObjectNode();
            itemAdded.put("type", "response.output_item.added");
            itemAdded.put("output_index", 0);
            itemAdded.set("item", objectMapper.readTree(objectMapper.writeValueAsString(toolCallItem)));

            ObjectNode argsDelta = objectMapper.createObjectNode();
            argsDelta.put("type", "response.function_call_arguments.delta");
            argsDelta.put("output_index", 0);
            argsDelta.put("delta", argumentsJson);

            ObjectNode argsDone = objectMapper.createObjectNode();
            argsDone.put("type", "response.function_call_arguments.done");
            argsDone.put("output_index", 0);
            argsDone.put("arguments", argumentsJson);

            ObjectNode itemDone = objectMapper.createObjectNode();
            itemDone.put("type", "response.output_item.done");
            itemDone.put("output_index", 0);
            itemDone.set("item", objectMapper.readTree(objectMapper.writeValueAsString(toolCallItem)));

            ObjectNode completedEvent = objectMapper.createObjectNode();
            completedEvent.put("type", "response.completed");
            completedEvent.set("response", objectMapper.readTree(responseJson));

            return formatSseEvent("response.created", objectMapper.writeValueAsString(createdEvent))
                    + formatSseEvent("response.output_item.added", objectMapper.writeValueAsString(itemAdded))
                    + formatSseEvent(
                            "response.function_call_arguments.delta", objectMapper.writeValueAsString(argsDelta))
                    + formatSseEvent("response.function_call_arguments.done", objectMapper.writeValueAsString(argsDone))
                    + formatSseEvent("response.output_item.done", objectMapper.writeValueAsString(itemDone))
                    + formatSseEvent("response.completed", objectMapper.writeValueAsString(completedEvent));
        } catch (Exception e) {
            throw new RuntimeException("Failed to build Responses SSE tool call response", e);
        }
    }

    // --- Anthropic Messages API ---

    /**
     * Builds an Anthropic Messages API response with a single text block.
     *
     * @param content the assistant message content
     * @return JSON string in Anthropic Messages API format
     */
    public static String anthropicText(String content) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("id", "msg_" + UUID.randomUUID().toString().substring(0, 8));
            root.put("type", "message");
            root.put("role", "assistant");
            ArrayNode contentArray = objectMapper.createArrayNode();
            ObjectNode textBlock = objectMapper.createObjectNode();
            textBlock.put("type", "text");
            textBlock.put("text", content);
            contentArray.add(textBlock);
            root.set("content", contentArray);
            root.put("model", "claude-opus-4-8");
            root.put("stop_reason", "end_turn");
            ObjectNode usage = objectMapper.createObjectNode();
            usage.put("input_tokens", 100);
            usage.put("output_tokens", 50);
            root.set("usage", usage);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build Anthropic text response", e);
        }
    }

    /**
     * Builds an Anthropic Messages API response with a single tool_use block.
     *
     * @param functionName the tool name
     * @param argumentsJson the JSON arguments string
     * @return JSON string in Anthropic Messages API format
     */
    public static String anthropicToolCall(String functionName, String argumentsJson) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("id", "msg_" + UUID.randomUUID().toString().substring(0, 8));
            root.put("type", "message");
            root.put("role", "assistant");
            ArrayNode contentArray = objectMapper.createArrayNode();
            ObjectNode toolUseBlock = objectMapper.createObjectNode();
            toolUseBlock.put("type", "tool_use");
            toolUseBlock.put("id", "toolu_" + UUID.randomUUID().toString().substring(0, 8));
            toolUseBlock.put("name", functionName);
            toolUseBlock.set("input", objectMapper.readTree(argumentsJson));
            contentArray.add(toolUseBlock);
            root.set("content", contentArray);
            root.put("model", "claude-opus-4-8");
            root.put("stop_reason", "tool_use");
            ObjectNode usage = objectMapper.createObjectNode();
            usage.put("input_tokens", 100);
            usage.put("output_tokens", 50);
            root.set("usage", usage);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build Anthropic tool call response", e);
        }
    }

    // --- Gemini API ---

    /**
     * Builds a Gemini API response with a single text part.
     *
     * @param content the assistant message content
     * @return JSON string in Gemini API format
     */
    public static String geminiText(String content) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            ArrayNode candidates = objectMapper.createArrayNode();
            ObjectNode candidate = objectMapper.createObjectNode();
            ObjectNode contentNode = objectMapper.createObjectNode();
            ArrayNode parts = objectMapper.createArrayNode();
            ObjectNode textPart = objectMapper.createObjectNode();
            textPart.put("text", content);
            parts.add(textPart);
            contentNode.set("parts", parts);
            contentNode.put("role", "model");
            candidate.set("content", contentNode);
            candidate.put("finishReason", "STOP");
            candidates.add(candidate);
            root.set("candidates", candidates);

            ObjectNode usageMetadata = objectMapper.createObjectNode();
            usageMetadata.put("promptTokenCount", 100);
            usageMetadata.put("candidatesTokenCount", 50);
            usageMetadata.put("totalTokenCount", 150);
            root.set("usageMetadata", usageMetadata);

            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build Gemini text response", e);
        }
    }

    /**
     * Builds a Gemini API response with a single function call.
     *
     * @param functionName the function name to call
     * @param argumentsJson the JSON arguments string
     * @return JSON string in Gemini API format
     */
    public static String geminiToolCall(String functionName, String argumentsJson) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            ArrayNode candidates = objectMapper.createArrayNode();
            ObjectNode candidate = objectMapper.createObjectNode();
            ObjectNode contentNode = objectMapper.createObjectNode();
            ArrayNode parts = objectMapper.createArrayNode();
            ObjectNode functionCallPart = objectMapper.createObjectNode();
            ObjectNode functionCall = objectMapper.createObjectNode();
            functionCall.put("name", functionName);
            functionCall.set("args", objectMapper.readTree(argumentsJson));
            functionCallPart.set("functionCall", functionCall);
            parts.add(functionCallPart);
            contentNode.set("parts", parts);
            contentNode.put("role", "model");
            candidate.set("content", contentNode);
            candidate.put("finishReason", "STOP");
            candidates.add(candidate);
            root.set("candidates", candidates);

            ObjectNode usageMetadata = objectMapper.createObjectNode();
            usageMetadata.put("promptTokenCount", 100);
            usageMetadata.put("candidatesTokenCount", 50);
            usageMetadata.put("totalTokenCount", 150);
            root.set("usageMetadata", usageMetadata);

            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build Gemini tool call response", e);
        }
    }

    /**
     * Builds a Gemini API SSE streaming response with a single text part. The Gemini SSE format
     * wraps each JSON chunk in a {@code data: } prefix, similar to OpenAI's SSE format.
     *
     * @param content the assistant message content
     * @return SSE-formatted string in Gemini API format
     */
    public static String geminiSseText(String content) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            ArrayNode candidates = objectMapper.createArrayNode();
            ObjectNode candidate = objectMapper.createObjectNode();
            ObjectNode contentNode = objectMapper.createObjectNode();
            ArrayNode parts = objectMapper.createArrayNode();
            ObjectNode textPart = objectMapper.createObjectNode();
            textPart.put("text", content);
            parts.add(textPart);
            contentNode.set("parts", parts);
            contentNode.put("role", "model");
            candidate.set("content", contentNode);
            candidate.put("finishReason", "STOP");
            candidates.add(candidate);
            root.set("candidates", candidates);

            ObjectNode usageMetadata = objectMapper.createObjectNode();
            usageMetadata.put("promptTokenCount", 100);
            usageMetadata.put("candidatesTokenCount", 50);
            usageMetadata.put("totalTokenCount", 150);
            root.set("usageMetadata", usageMetadata);

            return "data: " + objectMapper.writeValueAsString(root) + "\n\n";
        } catch (Exception e) {
            throw new RuntimeException("Failed to build Gemini SSE text response", e);
        }
    }

    /**
     * Builds a Gemini API SSE streaming response with a single function call. The Gemini SSE format
     * wraps each JSON chunk in a {@code data: } prefix.
     *
     * @param functionName the function name to call
     * @param argumentsJson the JSON arguments string
     * @return SSE-formatted string in Gemini API format
     */
    public static String geminiSseToolCall(String functionName, String argumentsJson) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            ArrayNode candidates = objectMapper.createArrayNode();
            ObjectNode candidate = objectMapper.createObjectNode();
            ObjectNode contentNode = objectMapper.createObjectNode();
            ArrayNode parts = objectMapper.createArrayNode();
            ObjectNode functionCallPart = objectMapper.createObjectNode();
            ObjectNode functionCall = objectMapper.createObjectNode();
            functionCall.put("name", functionName);
            functionCall.set("args", objectMapper.readTree(argumentsJson));
            functionCallPart.set("functionCall", functionCall);
            parts.add(functionCallPart);
            contentNode.set("parts", parts);
            contentNode.put("role", "model");
            candidate.set("content", contentNode);
            candidate.put("finishReason", "STOP");
            candidates.add(candidate);
            root.set("candidates", candidates);

            ObjectNode usageMetadata = objectMapper.createObjectNode();
            usageMetadata.put("promptTokenCount", 100);
            usageMetadata.put("candidatesTokenCount", 50);
            usageMetadata.put("totalTokenCount", 150);
            root.set("usageMetadata", usageMetadata);

            return "data: " + objectMapper.writeValueAsString(root) + "\n\n";
        } catch (Exception e) {
            throw new RuntimeException("Failed to build Gemini SSE tool call response", e);
        }
    }

    // --- Mistral API (SSE streaming) ---

    /**
     * Builds an SSE streaming response for the Mistral API with a text message. The Mistral SSE
     * format is similar to OpenAI's SSE format with {@code data:} prefixed lines and {@code [DONE]}
     * terminator.
     *
     * @param content the text content
     * @return SSE-formatted string
     */
    public static String mistralSseText(String content) {
        try {
            String chunkId = "chatcmpl-" + UUID.randomUUID().toString().substring(0, 8);

            ObjectNode delta = objectMapper.createObjectNode();
            delta.put("role", "assistant");
            delta.put("content", content);

            ObjectNode choice = objectMapper.createObjectNode();
            choice.put("index", 0);
            choice.set("delta", delta);
            // Mistral SDK requires finish_reason to be a valid enum value in every chunk
            choice.put("finish_reason", "stop");

            ObjectNode chunk = objectMapper.createObjectNode();
            chunk.put("id", chunkId);
            chunk.put("object", "chat.completion.chunk");
            chunk.put("model", "mistral-vibe-cli-latest");
            ArrayNode choices = objectMapper.createArrayNode();
            choices.add(choice);
            chunk.set("choices", choices);

            String dataLine = "data: " + objectMapper.writeValueAsString(chunk) + "\n\n";

            // Final chunk with finish_reason
            ObjectNode finalChoice = objectMapper.createObjectNode();
            finalChoice.put("index", 0);
            finalChoice.set("delta", objectMapper.createObjectNode());
            finalChoice.put("finish_reason", "stop");
            ObjectNode finalChunk = objectMapper.createObjectNode();
            finalChunk.put("id", chunkId);
            finalChunk.put("object", "chat.completion.chunk");
            finalChunk.put("model", "mistral-vibe-cli-latest");
            ArrayNode finalChoices = objectMapper.createArrayNode();
            finalChoices.add(finalChoice);
            finalChunk.set("choices", finalChoices);

            return dataLine + "data: " + objectMapper.writeValueAsString(finalChunk) + "\n\ndata: [DONE]\n\n";
        } catch (Exception e) {
            throw new RuntimeException("Failed to build Mistral SSE text response", e);
        }
    }

    /**
     * Builds an SSE streaming response for the Mistral API with a tool call.
     *
     * @param functionName the function name
     * @param argumentsJson the JSON arguments string
     * @return SSE-formatted string
     */
    public static String mistralSseToolCall(String functionName, String argumentsJson) {
        try {
            String chunkId = "chatcmpl-" + UUID.randomUUID().toString().substring(0, 8);
            String toolCallId = "call_" + UUID.randomUUID().toString().substring(0, 8);

            // Build tool call delta
            ObjectNode toolCall = objectMapper.createObjectNode();
            toolCall.put("index", 0);
            toolCall.put("id", toolCallId);
            toolCall.put("type", "function");
            ObjectNode function = objectMapper.createObjectNode();
            function.put("name", functionName);
            function.put("arguments", argumentsJson);
            toolCall.set("function", function);

            ObjectNode delta = objectMapper.createObjectNode();
            delta.put("role", "assistant");
            ArrayNode toolCalls = objectMapper.createArrayNode();
            toolCalls.add(toolCall);
            delta.set("tool_calls", toolCalls);

            ObjectNode choice = objectMapper.createObjectNode();
            choice.put("index", 0);
            choice.set("delta", delta);
            // Mistral SDK requires finish_reason to be a valid enum value in every chunk
            choice.put("finish_reason", "tool_calls");

            ObjectNode chunk = objectMapper.createObjectNode();
            chunk.put("id", chunkId);
            chunk.put("object", "chat.completion.chunk");
            chunk.put("model", "mistral-vibe-cli-latest");
            ArrayNode choices = objectMapper.createArrayNode();
            choices.add(choice);
            chunk.set("choices", choices);

            String dataLine = "data: " + objectMapper.writeValueAsString(chunk) + "\n\n";

            // Final chunk with finish_reason
            ObjectNode finalChoice = objectMapper.createObjectNode();
            finalChoice.put("index", 0);
            finalChoice.set("delta", objectMapper.createObjectNode());
            finalChoice.put("finish_reason", "tool_calls");
            ObjectNode finalChunk = objectMapper.createObjectNode();
            finalChunk.put("id", chunkId);
            finalChunk.put("object", "chat.completion.chunk");
            finalChunk.put("model", "mistral-vibe-cli-latest");
            ArrayNode finalChoices = objectMapper.createArrayNode();
            finalChoices.add(finalChoice);
            finalChunk.set("choices", finalChoices);

            return dataLine + "data: " + objectMapper.writeValueAsString(finalChunk) + "\n\ndata: [DONE]\n\n";
        } catch (Exception e) {
            throw new RuntimeException("Failed to build Mistral SSE tool call response", e);
        }
    }

    // --- OpenAI Embeddings API ---

    public static String openAiEmbedding(float[] vector) {
        return openAiEmbedding(List.of(vector));
    }

    public static String openAiEmbedding(List<float[]> vectors) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("object", "list");
            ArrayNode data = objectMapper.createArrayNode();

            for (int i = 0; i < vectors.size(); i++) {
                ObjectNode embedding = objectMapper.createObjectNode();
                embedding.put("object", "embedding");
                embedding.put("index", i);
                ArrayNode embeddingArray = objectMapper.createArrayNode();
                for (float value : vectors.get(i)) {
                    embeddingArray.add(value);
                }
                embedding.set("embedding", embeddingArray);
                data.add(embedding);
            }

            root.set("data", data);
            root.put("model", "text-embedding-3-small");

            ObjectNode usage = objectMapper.createObjectNode();
            usage.put("prompt_tokens", 10);
            usage.put("total_tokens", 10);
            root.set("usage", usage);

            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build OpenAI embedding response", e);
        }
    }

    // --- Metadata / pre-flight endpoints ---

    /**
     * Builds an OpenAI-compatible {@code GET /v1/models} response listing a single model. Used by
     * SDKs that list available models before sending prompts to a custom base URL.
     *
     * @return JSON string
     */
    public static String openAiModelsList() {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("object", "list");
            ArrayNode data = objectMapper.createArrayNode();
            ObjectNode model = objectMapper.createObjectNode();
            model.put("id", "mistral-large-latest");
            model.put("object", "model");
            model.put("created", 1700000000);
            model.put("owned_by", "mistral");
            data.add(model);
            root.set("data", data);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build models list JSON", e);
        }
    }

    /**
     * Builds an OpenAI-compatible {@code GET /v1/users/me} response. Used by SDKs that validate
     * the API key against a custom base URL before sending prompts.
     *
     * @return JSON string
     */
    public static String openAiUserInfo() {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("id", "user-mock");
            root.put("object", "user");
            root.put("email", "mock@kratis.ai");
            root.put("name", "Mock User");
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build user info JSON", e);
        }
    }

    /**
     * Builds a Gemini {@code GET /gemini/v1beta/models} response listing a single model. Used by
     * SDKs that list available models before sending prompts to a custom base URL.
     *
     * @return JSON string
     */
    public static String geminiModelsList() {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            ArrayNode models = objectMapper.createArrayNode();
            ObjectNode model = objectMapper.createObjectNode();
            model.put("name", "models/gemini-2.5-pro");
            model.put("version", "001");
            model.put("displayName", "Gemini 2.5 Pro");
            model.put("description", "Gemini 2.5 Pro model");
            model.put("inputTokenLimit", 1000000);
            model.put("outputTokenLimit", 65536);
            ArrayNode supportedMethods = objectMapper.createArrayNode();
            supportedMethods.add("generateContent");
            supportedMethods.add("streamGenerateContent");
            model.set("supportedGenerationMethods", supportedMethods);
            models.add(model);
            root.set("models", models);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build Gemini models list JSON", e);
        }
    }

    // --- Private helper methods ---

    private static ObjectNode createCompletionRoot() {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("id", "chatcmpl-" + UUID.randomUUID().toString().substring(0, 8));
        root.put("object", "chat.completion");
        root.put("model", "gpt-4o");

        ObjectNode usage = objectMapper.createObjectNode();
        usage.put("prompt_tokens", 100);
        usage.put("completion_tokens", 50);
        usage.put("total_tokens", 150);
        root.set("usage", usage);
        return root;
    }

    private static ObjectNode createChoice(int index, ObjectNode message, String finishReason) {
        ObjectNode choice = objectMapper.createObjectNode();
        choice.put("index", index);
        choice.set("message", message);
        choice.put("finish_reason", finishReason);
        return choice;
    }

    private static ObjectNode createTextMessage(String content) {
        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", "assistant");
        message.put("content", content);
        return message;
    }

    private static ObjectNode createToolCallMessage(String toolCallId, String functionName, String arguments) {
        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", "assistant");
        message.putNull("content");

        ArrayNode toolCalls = objectMapper.createArrayNode();
        ObjectNode toolCall = objectMapper.createObjectNode();
        toolCall.put("id", toolCallId);
        toolCall.put("type", "function");

        ObjectNode function = objectMapper.createObjectNode();
        function.put("name", functionName);
        function.put("arguments", arguments);
        toolCall.set("function", function);

        toolCalls.add(toolCall);
        message.set("tool_calls", toolCalls);
        return message;
    }

    private static String formatSseEvent(String eventName, String data) {
        return "event: " + eventName + "\n" + "data: " + data + "\n\n";
    }
}
