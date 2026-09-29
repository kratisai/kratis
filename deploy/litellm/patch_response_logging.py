"""One-shot patches for open LiteLLM bugs that drop LiteLLM_SpendLogs rows.

1. Streaming ``/v1/responses`` (BerriAI/litellm#29913, PR #29915): the success logger
   crashes with ``AttributeError: 'dict' object has no attribute 'usage'`` because the
   streaming-assembly path stores a plain dict in ``ResponseCompletedEvent.response``
   while the logging code accesses ``result.response.usage`` directly. The exception is
   swallowed as "[Non-Blocking] LoggingError", so the client gets a 200 stream but no
   spend-log row is written.

2. ``/v1/messages`` routed to a Responses-API backend (BerriAI/litellm#28595 / #28943):
   ``_handle_anthropic_messages_response_logging`` receives the Responses-shaped result
   as a plain dict (not ``ResponsesAPIResponse``) and crashes in
   ``AnthropicResponse.model_validate(dict)``, again dropping the spend-log row.

3. Google-native streaming (``:generateContent`` / ``:streamGenerateContent``): the
   end-of-stream logging for the Google GenAI streaming iterator
   (``litellm/google_genai/streaming_iterator.py``) dispatches through
   ``VertexPassthroughLoggingHandler`` with a hardcoded ``/v1/generateContent`` route,
   so ``_get_custom_llm_provider_from_url`` resolves the provider as ``vertex_ai`` even
   when the deployment is a Google AI Studio ``gemini`` provider, and
   ``_create_vertex_response_logging_payload_for_generate_content`` hardcodes
   ``custom_llm_provider="vertex_ai"`` in the ``completion_cost`` call. The model's
   cost-map entry lives under ``gemini/<model>`` (not ``vertex_ai/<model>``), so
   ``get_model_info`` raises "This model isn't mapped yet", the exception is swallowed
   by ``_route_streaming_logging_to_handler`` and no spend-log row is written — the
   Gemini CLI stream returns 200 but usage is silently lost. The fix resolves the
   provider from the request's own ``model_call_details`` (the deployment's real
   ``custom_llm_provider``) and honors it in the cost calculation.

All patches are applied before the litellm process imports the modules (container
entrypoint) and are no-ops once an upstream LiteLLM release contains the fixes.
"""

import logging
import pathlib
import sys

logging.basicConfig(level=logging.INFO, format="%(message)s")

OLD_BLOCK = """            if isinstance(result.response.usage, ResponseAPIUsage):
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
            return result.response"""

NEW_BLOCK = """            resp = result.response
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
            return result.response"""

# Second bug (BerriAI/litellm#28595 / #28943): /v1/messages (anthropic_messages) routed to a
# Responses-API backend passes a plain dict (Responses shape) into
# _handle_anthropic_messages_response_logging, which only recognizes ResponsesAPIResponse
# instances and falls through to AnthropicResponse.model_validate(dict) -> ValidationError,
# dropping the spend-log row.
OLD_BLOCK_2 = """        if isinstance(result, ResponsesAPIResponse):
            return self._translate_responses_api_response_to_model_response(result)"""

NEW_BLOCK_2 = """        if isinstance(result, ResponsesAPIResponse):
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
            )"""

# Third bug: Google-native streaming spend-log drop (see module docstring). The streaming
# iterator passes a synthetic "/v1/generateContent" route, so the vertex handler must not
# trust URL hostname sniffing; the deployment's real provider lives in the logging object.
OLD_BLOCK_3 = """        kwargs = VertexPassthroughLoggingHandler._create_vertex_response_logging_payload_for_generate_content(
            litellm_model_response=complete_streaming_response,
            model=model,
            kwargs=kwargs,
            start_time=start_time,
            end_time=end_time,
            logging_obj=litellm_logging_obj,
            custom_llm_provider=VertexPassthroughLoggingHandler._get_custom_llm_provider_from_url(url_route),
            vertex_location=vertex_location,
        )"""

NEW_BLOCK_3 = """        custom_llm_provider = (
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
        )"""

OLD_BLOCK_4 = """        response_cost: Final = litellm.completion_cost(
            completion_response=litellm_model_response,
            model=model,
            custom_llm_provider="vertex_ai",
            vertex_location=vertex_location,
        )"""

NEW_BLOCK_4 = """        response_cost: Final = litellm.completion_cost(
            completion_response=litellm_model_response,
            model=model,
            custom_llm_provider=custom_llm_provider,
            vertex_location=vertex_location,
        )"""


def main() -> int:
    arguments = sys.argv[1:]
    check_only = "--check" in arguments
    arguments = [argument for argument in arguments if argument != "--check"]

    if len(arguments) > 0:
        logging_target = pathlib.Path(arguments[0])
        vertex_target = pathlib.Path(arguments[1]) if len(arguments) > 1 else None
    else:
        import litellm

        package_root = pathlib.Path(litellm.__file__).parent
        logging_target = package_root / "litellm_core_utils" / "litellm_logging.py"
        vertex_target = (
            package_root
            / "proxy"
            / "pass_through_endpoints"
            / "llm_provider_handlers"
            / "vertex_passthrough_logging_handler.py"
        )

    if len(arguments) > 2:
        logging.error("expected at most two target files")
        return 2
    if check_only:
        return _check(logging_target, vertex_target)
    return _run(logging_target, vertex_target)


def _run(logging_target, vertex_target):
    patched_logging = patch_logging_module(logging_target)
    patched_vertex = patch_vertex_streaming_module(vertex_target) if vertex_target is not None else False

    if not patched_logging and not patched_vertex:
        logging.warning("no known buggy blocks matched — leaving targets untouched")
    return 0


def _check(logging_target, vertex_target):
    states = [check_logging_module(logging_target)]
    if vertex_target is not None:
        states.append(check_vertex_streaming_module(vertex_target))
    return 0 if all(states) else 1


def _patch_state(source, fixed_marker, buggy_block):
    if fixed_marker in source:
        return "fixed"
    if buggy_block in source:
        return "patchable"
    return "drifted"


def check_logging_module(target: pathlib.Path) -> bool:
    source = target.read_text(encoding="utf-8")
    states = (
        _patch_state(source, 'resp.get("usage") if isinstance(resp, dict)', OLD_BLOCK),
        _patch_state(source, 'elif isinstance(result, dict) and "usage" in result:', OLD_BLOCK_2),
    )
    if all(state != "drifted" for state in states):
        logging.info("%s patch state: %s", target, ", ".join(states))
        return True
    logging.error("%s does not match a known LiteLLM logging patch shape: %s", target, ", ".join(states))
    return False


def check_vertex_streaming_module(target: pathlib.Path) -> bool:
    source = target.read_text(encoding="utf-8")
    states = (
        _patch_state(
            source,
            'custom_llm_provider = (\n            litellm_logging_obj.model_call_details.get("custom_llm_provider")',
            OLD_BLOCK_3,
        ),
        _patch_state(
            source,
            "response_cost: Final = litellm.completion_cost(\n            completion_response=litellm_model_response,\n            model=model,\n            custom_llm_provider=custom_llm_provider,",
            OLD_BLOCK_4,
        ),
    )
    if all(state != "drifted" for state in states):
        logging.info("%s patch state: %s", target, ", ".join(states))
        return True
    logging.error("%s does not match a known LiteLLM vertex patch shape: %s", target, ", ".join(states))
    return False


def patch_logging_module(target: pathlib.Path) -> bool:
    source = target.read_text(encoding="utf-8")

    applied = False
    if OLD_BLOCK in source and NEW_BLOCK not in source:
        source = source.replace(OLD_BLOCK, NEW_BLOCK, 1)
        applied = True
    if OLD_BLOCK_2 in source and NEW_BLOCK_2 not in source:
        source = source.replace(OLD_BLOCK_2, NEW_BLOCK_2, 1)
        applied = True

    if applied:
        target.write_text(source, encoding="utf-8")
        logging.info("Patched %s (Responses-API streaming spend-log fixes)", target)
        return True
    if "resp.get(\"usage\")" in source and "_is_response_api_usage" in source:
        logging.info("%s already contains the Responses-API dict guards — no patch needed", target)
        return False
    logging.warning("%s did not match the expected buggy blocks — leaving it untouched", target)
    return False


def patch_vertex_streaming_module(target: pathlib.Path) -> bool:
    source = target.read_text(encoding="utf-8")

    applied = False
    if OLD_BLOCK_3 in source and NEW_BLOCK_3 not in source:
        source = source.replace(OLD_BLOCK_3, NEW_BLOCK_3, 1)
        applied = True
    if OLD_BLOCK_4 in source and NEW_BLOCK_4 not in source:
        source = source.replace(OLD_BLOCK_4, NEW_BLOCK_4, 1)
        applied = True

    if applied:
        target.write_text(source, encoding="utf-8")
        logging.info("Patched %s (Google-native streaming spend-log provider fixes)", target)
        return True
    if "model_call_details.get(\"custom_llm_provider\")" in source and "custom_llm_provider=custom_llm_provider," in source:
        logging.info("%s already contains the Gemini streaming provider fixes — no patch needed", target)
        return False
    logging.warning("%s did not match the expected buggy blocks — leaving it untouched", target)
    return False


if __name__ == "__main__":
    raise SystemExit(main())
