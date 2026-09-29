package com.kratisai.controlplane.agentloop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.NoCredentialsException;
import com.google.genai.errors.ClientException;
import com.google.genai.errors.GenAiIOException;
import com.google.genai.errors.ServerException;
import com.openai.errors.OpenAIServiceException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

class LlmExceptionClassifierTest {

    @Test
    void nonTransient401_isAuthentication() {
        assertThat(LlmExceptionClassifier.classify(new NonTransientAiException("401 - invalid api key")))
                .isEqualTo(LlmErrorCategory.AUTHENTICATION);
    }

    @Test
    void nonTransient403_isPermissionDenied() {
        assertThat(LlmExceptionClassifier.classify(new NonTransientAiException("403 - forbidden")))
                .isEqualTo(LlmErrorCategory.PERMISSION_DENIED);
    }

    @Test
    void nonTransient404_isModelNotFound() {
        assertThat(LlmExceptionClassifier.classify(new NonTransientAiException("404 - model not found")))
                .isEqualTo(LlmErrorCategory.MODEL_NOT_FOUND);
    }

    @Test
    void nonTransient400WithContextMarker_isContextLengthExceeded() {
        assertThat(LlmExceptionClassifier.classify(new NonTransientAiException(
                        "400 - This model's maximum context length is 128000 tokens. You requested 200000.")))
                .isEqualTo(LlmErrorCategory.CONTEXT_LENGTH_EXCEEDED);
    }

    @Test
    void nonTransient400WithoutMarker_isBadRequest() {
        assertThat(LlmExceptionClassifier.classify(new NonTransientAiException("400 - unsupported parameter: foo")))
                .isEqualTo(LlmErrorCategory.BAD_REQUEST);
    }

    @Test
    void nonTransient429WithoutMarker_isRateLimit() {
        assertThat(LlmExceptionClassifier.classify(
                        new NonTransientAiException("429 - rate limit reached, retry after 1s")))
                .isEqualTo(LlmErrorCategory.RATE_LIMIT);
    }

    @Test
    void nonTransient429WithQuotaMarker_isQuotaExceeded() {
        assertThat(LlmExceptionClassifier.classify(new NonTransientAiException(
                        "429 - You exceeded your current quota, please check your plan and billing details.")))
                .isEqualTo(LlmErrorCategory.QUOTA_EXCEEDED);
    }

    @Test
    void transient5xx_isServerError() {
        for (int status : new int[] {500, 502, 503, 504}) {
            assertThat(LlmExceptionClassifier.classify(new TransientAiException(status + " - upstream failure")))
                    .isEqualTo(LlmErrorCategory.SERVER_ERROR);
        }
    }

    @Test
    void transientExceptionWithoutStatus_isServerError() {
        assertThat(LlmExceptionClassifier.classify(new TransientAiException("connection throttled")))
                .isEqualTo(LlmErrorCategory.SERVER_ERROR);
    }

    @Test
    void httpClientUnauthorized_isAuthentication() {
        assertThat(LlmExceptionClassifier.classify(new HttpClientErrorException(HttpStatus.UNAUTHORIZED)))
                .isEqualTo(LlmErrorCategory.AUTHENTICATION);
    }

    @Test
    void httpServer5xx_isServerError() {
        assertThat(LlmExceptionClassifier.classify(new HttpServerErrorException(HttpStatus.BAD_GATEWAY)))
                .isEqualTo(LlmErrorCategory.SERVER_ERROR);
    }

    @Test
    void resourceAccessException_isNetworkError() {
        assertThat(LlmExceptionClassifier.classify(new ResourceAccessException("I/O error: Connection reset")))
                .isEqualTo(LlmErrorCategory.NETWORK_ERROR);
    }

    @Test
    void socketTimeout_isNetworkError() {
        assertThat(LlmExceptionClassifier.classify(new SocketTimeoutException("connect timed out")))
                .isEqualTo(LlmErrorCategory.NETWORK_ERROR);
    }

    @Test
    void nestedCause_isWalkedToTypedException() {
        RuntimeException wrapper =
                new RuntimeException("Aggregation Error", new TransientAiException("503 - overloaded"));
        assertThat(LlmExceptionClassifier.classify(wrapper)).isEqualTo(LlmErrorCategory.SERVER_ERROR);
    }

    @Test
    void googleClientContextError_isContextLengthExceeded() {
        assertThat(LlmExceptionClassifier.classify(
                        new ClientException(400, "BAD_REQUEST", "Token limit exceeded: 200000 tokens were requested.")))
                .isEqualTo(LlmErrorCategory.CONTEXT_LENGTH_EXCEEDED);
    }

    @Test
    void googleServerError_isServerError() {
        assertThat(LlmExceptionClassifier.classify(new ServerException(503, "UNAVAILABLE", "overloaded")))
                .isEqualTo(LlmErrorCategory.SERVER_ERROR);
    }

    @Test
    void googleIoError_isNetworkError() {
        assertThat(LlmExceptionClassifier.classify(new GenAiIOException(new IOException("Connection reset"))))
                .isEqualTo(LlmErrorCategory.NETWORK_ERROR);
    }

    @Test
    void anthropicNoCredentials_isAuthentication() {
        assertThat(LlmExceptionClassifier.classify(new NoCredentialsException(List.of())))
                .isEqualTo(LlmErrorCategory.AUTHENTICATION);
    }

    @Test
    void anthropicIoError_isNetworkError() {
        assertThat(LlmExceptionClassifier.classify(new AnthropicIoException("upstream closed", new IOException("EOF"))))
                .isEqualTo(LlmErrorCategory.NETWORK_ERROR);
    }

    @Test
    void anthropicServiceForbidden_isPermissionDenied() {
        AnthropicServiceException forbidden = mock(AnthropicServiceException.class);
        when(forbidden.statusCode()).thenReturn(403);

        assertThat(LlmExceptionClassifier.classify(forbidden)).isEqualTo(LlmErrorCategory.PERMISSION_DENIED);
    }

    @Test
    void openAiServiceUnauthorized_isAuthentication() {
        OpenAIServiceException unauthorized = mock(OpenAIServiceException.class);
        when(unauthorized.statusCode()).thenReturn(401);

        assertThat(LlmExceptionClassifier.classify(unauthorized)).isEqualTo(LlmErrorCategory.AUTHENTICATION);
    }

    @Test
    void openAiService429WithQuotaBody_isQuotaExceeded() {
        OpenAIServiceException rateLimited = mock(OpenAIServiceException.class);
        when(rateLimited.statusCode()).thenReturn(429);
        when(rateLimited.getMessage()).thenReturn("Error code: insufficient_quota - You exceeded your current quota.");

        assertThat(LlmExceptionClassifier.classify(rateLimited)).isEqualTo(LlmErrorCategory.QUOTA_EXCEEDED);
    }

    @Test
    void messageFallback_contextMarkerOnGenericWrapper_isContextLengthExceeded() {
        assertThat(LlmExceptionClassifier.classify(
                        new IllegalStateException("The model produced an error: context_length_exceeded (max 128k)")))
                .isEqualTo(LlmErrorCategory.CONTEXT_LENGTH_EXCEEDED);
    }

    @Test
    void messageFallback_quotaMarker_isQuotaExceeded() {
        assertThat(LlmExceptionClassifier.classify(new RuntimeException("budget_exceeded for this virtual key")))
                .isEqualTo(LlmErrorCategory.QUOTA_EXCEEDED);
    }

    @Test
    void unknownMessage_isUnknown() {
        assertThat(LlmExceptionClassifier.classify(new IllegalStateException("boom")))
                .isEqualTo(LlmErrorCategory.UNKNOWN);
    }

    @Test
    void messageLessThrowable_isUnknown() {
        assertThat(LlmExceptionClassifier.classify(new IllegalStateException())).isEqualTo(LlmErrorCategory.UNKNOWN);
    }
}
