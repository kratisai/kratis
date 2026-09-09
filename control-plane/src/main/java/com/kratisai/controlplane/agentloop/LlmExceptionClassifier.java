package com.kratisai.controlplane.agentloop;

import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.NoCredentialsException;
import com.google.genai.errors.ApiException;
import com.google.genai.errors.GenAiIOException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import java.io.IOException;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Maps exceptions raised while invoking an LLM provider onto a single {@link LlmErrorCategory}.
 *
 * <p>Each provider SDK surfaces HTTP failures as its own exception hierarchy, so the classifier
 * first walks the cause chain looking for a typed signal (SDK service exception, Spring AI retry
 * exception, Spring Web exception) carrying an HTTP status code. When no typed signal exists it
 * falls back to known LiteLLM/OpenAI error-body phrases, which are also how LiteLLM reports
 * underlying provider failures (for example {@code context_window_exceeded} or
 * {@code insufficient_quota}).
 */
public final class LlmExceptionClassifier {

    private static final int MAX_CAUSE_DEPTH = 10;

    private LlmExceptionClassifier() {}

    public static LlmErrorCategory classify(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; current = current.getCause(), depth++) {
            LlmErrorCategory category = classifyNode(current);
            if (category != null) {
                return category;
            }
        }
        return classifyByMessage(error);
    }

    private static @Nullable LlmErrorCategory classifyNode(Throwable error) {
        String message = error.getMessage();
        if (error instanceof HttpStatusCodeException http) {
            return byStatus(http.getStatusCode().value(), message);
        }
        if (error instanceof NonTransientAiException) {
            Integer status = statusFromMessage(message);
            return byStatus(status != null ? status : 400, message);
        }
        if (error instanceof TransientAiException) {
            Integer status = statusFromMessage(message);
            return byStatus(status != null ? status : 503, message);
        }
        if (error instanceof OpenAIServiceException openAi) {
            return byStatus(openAi.statusCode(), message);
        }
        if (error instanceof AnthropicServiceException anthropic) {
            return byStatus(anthropic.statusCode(), message);
        }
        if (error instanceof NoCredentialsException) {
            return LlmErrorCategory.AUTHENTICATION;
        }
        if (error instanceof ApiException google) {
            return byStatus(google.code(), google.message());
        }
        if (error instanceof ResourceAccessException
                || error instanceof OpenAIIoException
                || error instanceof AnthropicIoException
                || error instanceof GenAiIOException
                || error instanceof IOException) {
            return LlmErrorCategory.NETWORK_ERROR;
        }
        return null;
    }

    private static LlmErrorCategory byStatus(int status, @Nullable String message) {
        String text = normalized(message);
        return switch (status) {
            case 400, 413, 422 ->
                containsAny(text, CONTEXT_MARKERS)
                        ? LlmErrorCategory.CONTEXT_LENGTH_EXCEEDED
                        : LlmErrorCategory.BAD_REQUEST;
            case 401 -> LlmErrorCategory.AUTHENTICATION;
            case 403 -> LlmErrorCategory.PERMISSION_DENIED;
            case 404 -> LlmErrorCategory.MODEL_NOT_FOUND;
            case 408, 425 -> LlmErrorCategory.NETWORK_ERROR;
            case 429 ->
                containsAny(text, QUOTA_MARKERS) ? LlmErrorCategory.QUOTA_EXCEEDED : LlmErrorCategory.RATE_LIMIT;
            default -> {
                if (status >= 500 && status < 600) {
                    yield LlmErrorCategory.SERVER_ERROR;
                }
                if (status >= 400 && status < 500) {
                    yield LlmErrorCategory.BAD_REQUEST;
                }
                yield LlmErrorCategory.UNKNOWN;
            }
        };
    }

    private static LlmErrorCategory classifyByMessage(Throwable error) {
        StringBuilder chainText = new StringBuilder();
        Throwable current = error;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; current = current.getCause(), depth++) {
            if (current.getMessage() != null) {
                chainText.append(current.getMessage()).append('\n');
            }
        }
        String text = chainText.toString().toLowerCase(Locale.ROOT);
        if (containsAny(text, CONTEXT_MARKERS)) {
            return LlmErrorCategory.CONTEXT_LENGTH_EXCEEDED;
        }
        if (containsAny(text, AUTHENTICATION_MARKERS)) {
            return LlmErrorCategory.AUTHENTICATION;
        }
        if (containsAny(text, PERMISSION_MARKERS)) {
            return LlmErrorCategory.PERMISSION_DENIED;
        }
        if (containsAny(text, QUOTA_MARKERS)) {
            return LlmErrorCategory.QUOTA_EXCEEDED;
        }
        if (containsAny(text, NETWORK_MARKERS)) {
            return LlmErrorCategory.NETWORK_ERROR;
        }
        return LlmErrorCategory.UNKNOWN;
    }

    private static @Nullable Integer statusFromMessage(@Nullable String message) {
        if (message == null) {
            return null;
        }
        String trimmed = message.trim();
        int separator = trimmed.indexOf(" - ");
        if (separator <= 0) {
            return null;
        }
        try {
            return Integer.valueOf(trimmed.substring(0, separator).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String normalized(@Nullable String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }

    private static boolean containsAny(String text, String[] markers) {
        for (String marker : markers) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static final String[] CONTEXT_MARKERS = {
        "context_length_exceeded",
        "context_window_exceeded",
        "context window",
        "context length",
        "maximum context length",
        "string_above_max_length",
        "too many tokens",
        "token limit",
        "prompt is too long",
        "request is too large",
        "reduce the length",
        "input is too long",
        "maximum input length"
    };

    private static final String[] AUTHENTICATION_MARKERS = {
        "invalid_api_key",
        "invalid api key",
        "incorrect api key",
        "api key not",
        "no api key",
        "missing api key",
        "authentication failed",
        "not authenticated",
        "unauthorized",
        "invalid x-api-key"
    };

    private static final String[] PERMISSION_MARKERS = {
        "permission denied", "forbidden", "access denied", "not allowed to", "does not have access"
    };

    private static final String[] QUOTA_MARKERS = {
        "insufficient_quota",
        "quota exceeded",
        "exceeded your current quota",
        "budget exceeded",
        "budget_exceeded",
        "insufficient credits",
        "out of credits",
        "billing issue",
        "account has no credits",
        "credit balance is too low"
    };

    private static final String[] NETWORK_MARKERS = {
        "connection refused", "connection reset", "connection timed out", "broken pipe", "read timed out"
    };
}
