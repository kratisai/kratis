package com.kratisai.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@code litellm/patch_response_logging.py} — the same script
 * {@link PostgresTestInitializer} runs against the LiteLLM Testcontainer — can
 * distinguish a known upstream LiteLLM module shape from an unrecognized one.
 * {@code --check} must accept the current known buggy and already-fixed shapes
 * and reject unknown shapes so a LiteLLM {@code main-stable} refactor is caught
 * at test-suite startup instead of silently disabling the spend-log patches.
 */
class LiteLLMLoggingPatchTest {

    private static final String BUGGY_SNIPPET = """
            class Logging:
                def _get_assembled_streaming_response(
                    self,
                    result,
                    start_time,
                    end_time,
                    is_async,
                    streaming_chunks,
                ):
                    if self.stream is not True:
                        return None
                    if isinstance(result, ModelResponse) or isinstance(result, TextCompletionResponse):
                        return result
                    elif isinstance(
                        result,
                        (ResponseCompletedEvent, ResponseIncompleteEvent, ResponseFailedEvent),
                    ):
                        ## return unified Usage object
                        if isinstance(result.response.usage, ResponseAPIUsage):
                            transformed_usage: Final = ResponseAPILoggingUtils._transform_response_api_usage_to_chat_usage(
                                result.response.usage
                            )
                            # Set as dict instead of Usage object so model_dump() serializes it correctly
                            setattr(
                                result.response,
                                "usage",
                                (
                                    transformed_usage.model_dump()
                                    if hasattr(transformed_usage, "model_dump")
                                    else dict(transformed_usage)
                                ),
                            )
                        return result.response
                    else:
                        return None
            """;

    private static final String FIXED_SNIPPET = """
            class Logging:
                def _get_assembled_streaming_response(
                    self,
                    result,
                    start_time,
                    end_time,
                    is_async,
                    streaming_chunks,
                ):
                    if self.stream is not True:
                        return None
                    if isinstance(result, ModelResponse) or isinstance(result, TextCompletionResponse):
                        return result
                    elif isinstance(
                        result,
                        (ResponseCompletedEvent, ResponseIncompleteEvent, ResponseFailedEvent),
                    ):
                        ## return unified Usage object
                        resp = result.response
                        usage = resp.get("usage") if isinstance(resp, dict) else getattr(resp, "usage", None)
                        if isinstance(usage, ResponseAPIUsage) or (
                            isinstance(usage, dict) and ResponseAPILoggingUtils._is_response_api_usage(usage)
                        ):
                            transformed_usage: Final = ResponseAPILoggingUtils._transform_response_api_usage_to_chat_usage(
                                usage
                            )
                            # Set as dict instead of Usage object so model_dump() serializes it correctly
                            new_usage = (
                                transformed_usage.model_dump()
                                if hasattr(transformed_usage, "model_dump")
                                else dict(transformed_usage)
                            )
                            if isinstance(resp, dict):
                                resp["usage"] = new_usage
                            else:
                                setattr(resp, "usage", new_usage)
                        return result.response
                    else:
                        return None
            """;

    // Third bug: Google-native streaming spend-log drop. The Google GenAI streaming iterator
    // dispatches end-of-stream logging through VertexPassthroughLoggingHandler with a synthetic
    // "/v1/generateContent" route, and the handler resolves the provider from that URL hostname
    // ("vertex_ai") while hardcoding "vertex_ai" in the cost calculation. For deployments
    // registered with custom_llm_provider "gemini", get_model_info("gemini-flash-lite-latest",
    // "vertex_ai") raises "This model isn't mapped yet" and the spend-log row is dropped.
    private static final String BUGGY_VERTEX_SNIPPET = """
            class VertexPassthroughLoggingHandler:
                @staticmethod
                def _handle_logging_vertex_collected_chunks(
                    litellm_logging_obj, passthrough_success_handler_obj, url_route, request_body,
                    endpoint_type, start_time, all_chunks, model, end_time,
                ):
                    kwargs = {}
                    vertex_location = get_vertex_location_from_url(url_route)
                    model = model or VertexPassthroughLoggingHandler.extract_model_from_url(url_route)
                    complete_streaming_response = VertexPassthroughLoggingHandler._build_complete_streaming_response(
                        all_chunks=all_chunks,
                        litellm_logging_obj=litellm_logging_obj,
                        model=model,
                        url_route=url_route,
                    )
                    kwargs = VertexPassthroughLoggingHandler._create_vertex_response_logging_payload_for_generate_content(
                        litellm_model_response=complete_streaming_response,
                        model=model,
                        kwargs=kwargs,
                        start_time=start_time,
                        end_time=end_time,
                        logging_obj=litellm_logging_obj,
                        custom_llm_provider=VertexPassthroughLoggingHandler._get_custom_llm_provider_from_url(url_route),
                        vertex_location=vertex_location,
                    )
                    return {"result": complete_streaming_response, "kwargs": kwargs}

                @staticmethod
                def _create_vertex_response_logging_payload_for_generate_content(
                    litellm_model_response, model, kwargs, start_time, end_time, logging_obj, custom_llm_provider,
                    vertex_location,
                ):
                    response_cost: Final = litellm.completion_cost(
                        completion_response=litellm_model_response,
                        model=model,
                        custom_llm_provider="vertex_ai",
                        vertex_location=vertex_location,
                    )
                    kwargs["response_cost"] = response_cost
                    kwargs["model"] = model
                    logging_obj.model_call_details["custom_llm_provider"] = custom_llm_provider
                    return kwargs
            """;

    private static final String FIXED_VERTEX_SNIPPET = """
            class VertexPassthroughLoggingHandler:
                @staticmethod
                def _handle_logging_vertex_collected_chunks(
                    litellm_logging_obj, passthrough_success_handler_obj, url_route, request_body,
                    endpoint_type, start_time, all_chunks, model, end_time,
                ):
                    kwargs = {}
                    vertex_location = get_vertex_location_from_url(url_route)
                    model = model or VertexPassthroughLoggingHandler.extract_model_from_url(url_route)
                    complete_streaming_response = VertexPassthroughLoggingHandler._build_complete_streaming_response(
                        all_chunks=all_chunks,
                        litellm_logging_obj=litellm_logging_obj,
                        model=model,
                        url_route=url_route,
                    )
                    custom_llm_provider = (
                        litellm_logging_obj.model_call_details.get("custom_llm_provider")
                        or VertexPassthroughLoggingHandler._get_custom_llm_provider_from_url(url_route)
                    )
                    kwargs = VertexPassthroughLoggingHandler._create_vertex_response_logging_payload_for_generate_content(
                        litellm_model_response=complete_streaming_response,
                        model=model,
                        kwargs=kwargs,
                        start_time=start_time,
                        end_time=end_time,
                        logging_obj=litellm_logging_obj,
                        custom_llm_provider=custom_llm_provider,
                        vertex_location=vertex_location,
                    )
                    return {"result": complete_streaming_response, "kwargs": kwargs}

                @staticmethod
                def _create_vertex_response_logging_payload_for_generate_content(
                    litellm_model_response, model, kwargs, start_time, end_time, logging_obj, custom_llm_provider,
                    vertex_location,
                ):
                    response_cost: Final = litellm.completion_cost(
                        completion_response=litellm_model_response,
                        model=model,
                        custom_llm_provider=custom_llm_provider,
                        vertex_location=vertex_location,
                    )
                    kwargs["response_cost"] = response_cost
                    kwargs["model"] = model
                    logging_obj.model_call_details["custom_llm_provider"] = custom_llm_provider
                    return kwargs
            """;

    private static final String BUGGY_ANTHROPIC_HANDLER_SNIPPET = """
            class Logging:
                def _handle_anthropic_messages_response_logging(self, result: Any) -> ModelResponse:
                    if self.stream and isinstance(result, ModelResponse) or isinstance(result, ModelResponse):
                        return result
                    if isinstance(
                        result,
                        (ResponseCompletedEvent, ResponseIncompleteEvent, ResponseFailedEvent),
                    ):
                        result = result.response
                    if isinstance(result, ResponsesAPIResponse):
                        return self._translate_responses_api_response_to_model_response(result)
                    from litellm.types.llms.anthropic import AnthropicResponse

                    pydantic_result: Final = AnthropicResponse.model_validate(result)
                    return pydantic_result
            """;

    private static final String FIXED_ANTHROPIC_HANDLER_SNIPPET = """
            class Logging:
                def _handle_anthropic_messages_response_logging(self, result: Any) -> ModelResponse:
                    if self.stream and isinstance(result, ModelResponse) or isinstance(result, ModelResponse):
                        return result
                    if isinstance(
                        result,
                        (ResponseCompletedEvent, ResponseIncompleteEvent, ResponseFailedEvent),
                    ):
                        result = result.response
                    if isinstance(result, ResponsesAPIResponse):
                        return self._translate_responses_api_response_to_model_response(result)
                    elif isinstance(result, dict) and "usage" in result:
                        # Responses-API dict from cross-route: extract usage directly
                        usage = result.get("usage", {})
                        return litellm.ModelResponse(
                            id=result.get("id", ""),
                            model=self.model,
                            usage=litellm.Usage(
                                prompt_tokens=usage.get("input_tokens", 0),
                                completion_tokens=usage.get("output_tokens", 0),
                                total_tokens=usage.get("total_tokens", 0),
                            ),
                        )
                    from litellm.types.llms.anthropic import AnthropicResponse

                    pydantic_result: Final = AnthropicResponse.model_validate(result)
                    return pydantic_result
            """;

    @Test
    void checkAcceptsPatchableLiteLLMModules() throws Exception {
        Path script = copyScriptToTemp();
        Path loggingTarget = Files.createTempFile("litellm_logging", ".py");
        Files.writeString(loggingTarget, BUGGY_SNIPPET + BUGGY_ANTHROPIC_HANDLER_SNIPPET);
        Path vertexTarget = Files.createTempFile("vertex_passthrough_logging_handler", ".py");
        Files.writeString(vertexTarget, BUGGY_VERTEX_SNIPPET);

        ProcessResult result = runCheck(script, loggingTarget, vertexTarget);

        assertThat(result.exitCode())
                .as(
                        "known upstream shape should pass the drift check\nstdout=%s\nstderr=%s",
                        result.stdout(), result.stderr())
                .isZero();
        assertThat(result.stderr()).contains("patch state: patchable, patchable");
    }

    @Test
    void checkAcceptsAlreadyPatchedLiteLLMModules() throws Exception {
        Path script = copyScriptToTemp();
        Path loggingTarget = Files.createTempFile("litellm_logging", ".py");
        Files.writeString(loggingTarget, FIXED_SNIPPET + FIXED_ANTHROPIC_HANDLER_SNIPPET);
        Path vertexTarget = Files.createTempFile("vertex_passthrough_logging_handler", ".py");
        Files.writeString(vertexTarget, FIXED_VERTEX_SNIPPET);

        ProcessResult result = runCheck(script, loggingTarget, vertexTarget);

        assertThat(result.exitCode()).isZero();
        assertThat(result.stderr()).contains("patch state: fixed, fixed");
    }

    @Test
    void checkRejectsUnrecognizedLoggingModuleShape() throws Exception {
        Path script = copyScriptToTemp();
        Path loggingTarget = Files.createTempFile("litellm_logging", ".py");
        Files.writeString(loggingTarget, "def upstream_refactor():\n    return 42\n");
        Path vertexTarget = Files.createTempFile("vertex_passthrough_logging_handler", ".py");
        Files.writeString(vertexTarget, FIXED_VERTEX_SNIPPET);

        ProcessResult result = runCheck(script, loggingTarget, vertexTarget);

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr()).contains("does not match a known LiteLLM logging patch shape");
    }

    @Test
    void checkRejectsUnrecognizedVertexModuleShape() throws Exception {
        Path script = copyScriptToTemp();
        Path loggingTarget = Files.createTempFile("litellm_logging", ".py");
        Files.writeString(loggingTarget, FIXED_SNIPPET + FIXED_ANTHROPIC_HANDLER_SNIPPET);
        Path vertexTarget = Files.createTempFile("vertex_passthrough_logging_handler", ".py");
        Files.writeString(vertexTarget, "def upstream_refactor():\n    return 42\n");

        ProcessResult result = runCheck(script, loggingTarget, vertexTarget);

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr()).contains("does not match a known LiteLLM vertex patch shape");
    }

    private static Path copyScriptToTemp() throws IOException {
        Path script = Files.createTempFile("patch_response_logging", ".py");
        try (InputStream in = LiteLLMLoggingPatchTest.class.getResourceAsStream("/litellm/patch_response_logging.py")) {
            assertThat(in).as("patch script must exist on the classpath").isNotNull();
            Files.copy(in, script, StandardCopyOption.REPLACE_EXISTING);
        }
        return script;
    }

    private static ProcessResult runCheck(Path script, Path loggingTarget, Path vertexTarget)
            throws IOException, InterruptedException {
        Process process = new ProcessBuilder(
                        "python3", script.toString(), "--check", loggingTarget.toString(), vertexTarget.toString())
                .redirectErrorStream(false)
                .start();
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        assertThat(finished).as("patch script must terminate within 30s").isTrue();
        return new ProcessResult(process.exitValue(), stdout, stderr);
    }

    private record ProcessResult(int exitCode, String stdout, String stderr) {}
}
