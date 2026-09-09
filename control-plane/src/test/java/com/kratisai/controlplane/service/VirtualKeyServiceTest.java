package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.kratisai.controlplane.client.litellm.LiteLLMClient;
import com.kratisai.controlplane.client.litellm.LiteLLMDto.*;
import com.kratisai.controlplane.model.LlmUsageSnapshot;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClientException;

@ExtendWith(MockitoExtension.class)
class VirtualKeyServiceTest {

    @Mock
    private LiteLLMClient liteLLMClient;

    private VirtualKeyService virtualKeyService;

    @BeforeEach
    void setUp() {
        virtualKeyService = new VirtualKeyService(liteLLMClient);
    }

    @Test
    void generateKey_shouldCallLiteLLMAndReturnToken() {
        String keyAlias = "kratis-ingest-12345";
        List<String> models = List.of("openai-provider-gpt-4o-abc123");
        GenerateKeyResponse response = new GenerateKeyResponse("sk-test-key", keyAlias);
        when(liteLLMClient.generateKey(any(GenerateKeyRequest.class))).thenReturn(response);

        String result = virtualKeyService.generateKey(keyAlias, models);

        ArgumentCaptor<GenerateKeyRequest> requestCaptor = ArgumentCaptor.forClass(GenerateKeyRequest.class);
        verify(liteLLMClient).generateKey(requestCaptor.capture());
        assertThat(requestCaptor.getValue().keyAlias()).isEqualTo(keyAlias);
        assertThat(requestCaptor.getValue().models()).isEqualTo(models);
        assertThat(result).isEqualTo("sk-test-key");
    }

    @Test
    void generateKey_shouldPropagateLiteLLMErrors() {
        when(liteLLMClient.generateKey(any(GenerateKeyRequest.class)))
                .thenThrow(new RestClientException("LiteLLM unavailable"));

        assertThatThrownBy(() -> virtualKeyService.generateKey("test-alias", List.of("model")))
                .isInstanceOf(RestClientException.class)
                .hasMessageContaining("LiteLLM unavailable");
    }

    @Test
    void generateKey_shouldRevokeAndRegenerateWhenKeyAlreadyExists() {
        String keyAlias = "kratis-ingest-12345";
        List<String> models = List.of("openai-provider-gpt-4o-abc123");

        // First call throws "already exists"
        when(liteLLMClient.generateKey(any(GenerateKeyRequest.class)))
                .thenThrow(new RuntimeException("Key already exists"))
                .thenReturn(new GenerateKeyResponse("sk-new-key", keyAlias));
        when(liteLLMClient.deleteKey(any(DeleteKeyRequest.class)))
                .thenReturn(new DeleteKeyResponse(List.of("deleted"), "deleted"));

        String result = virtualKeyService.generateKey(keyAlias, models);

        // Verify delete was called with key_aliases
        ArgumentCaptor<DeleteKeyRequest> deleteCaptor = ArgumentCaptor.forClass(DeleteKeyRequest.class);
        verify(liteLLMClient).deleteKey(deleteCaptor.capture());
        assertThat(deleteCaptor.getValue().keyAliases()).containsExactly(keyAlias);

        // Verify generate was called twice
        verify(liteLLMClient, times(2)).generateKey(any(GenerateKeyRequest.class));
        assertThat(result).isEqualTo("sk-new-key");
    }

    @Test
    void fetchUsage_shouldReturnSpendFromKeyInfoAndAggregatedTokensFromSpendLogs() {
        String token = "sk-test-token";
        KeyInfoResponse info = new KeyInfoResponse(token, new KeyInfoData("kratis-ingest-123", 0.10));
        when(liteLLMClient.keyInfo(token)).thenReturn(info);
        when(liteLLMClient.spendLogs(token))
                .thenReturn(List.of(new SpendLogEntry(100L, 60L, 40L), new SpendLogEntry(200L, 120L, 80L)));

        LlmUsageSnapshot snapshot = virtualKeyService.fetchUsage(token);

        assertThat(snapshot.spend()).isEqualTo(0.10);
        assertThat(snapshot.totalTokens()).isEqualTo(300L);
        assertThat(snapshot.promptTokens()).isEqualTo(180L);
        assertThat(snapshot.completionTokens()).isEqualTo(120L);
        verify(liteLLMClient).keyInfo(token);
        verify(liteLLMClient).spendLogs(token);
    }

    @Test
    void fetchUsage_shouldHandleNullValues() {
        String token = "sk-test-token";
        KeyInfoResponse info = new KeyInfoResponse(token, new KeyInfoData("kratis-ingest-123", null));
        when(liteLLMClient.keyInfo(token)).thenReturn(info);
        when(liteLLMClient.spendLogs(token)).thenReturn(List.of(new SpendLogEntry(null, null, null)));

        LlmUsageSnapshot snapshot = virtualKeyService.fetchUsage(token);

        assertThat(snapshot.spend()).isEqualTo(0.0);
        assertThat(snapshot.totalTokens()).isEqualTo(0L);
        assertThat(snapshot.promptTokens()).isEqualTo(0L);
        assertThat(snapshot.completionTokens()).isEqualTo(0L);
    }

    @Test
    void fetchUsage_shouldReturnZeroTokensWhenNoSpendLogs() {
        String token = "sk-test-token";
        KeyInfoResponse info = new KeyInfoResponse(token, new KeyInfoData("kratis-ingest-123", 0.25));
        when(liteLLMClient.keyInfo(token)).thenReturn(info);
        when(liteLLMClient.spendLogs(token)).thenReturn(List.of());

        LlmUsageSnapshot snapshot = virtualKeyService.fetchUsage(token);

        assertThat(snapshot.spend()).isEqualTo(0.25);
        assertThat(snapshot.totalTokens()).isEqualTo(0L);
        assertThat(snapshot.promptTokens()).isEqualTo(0L);
        assertThat(snapshot.completionTokens()).isEqualTo(0L);
    }

    @Test
    void fetchUsage_shouldHandleNullKeyInfo() {
        String token = "sk-test-token";
        when(liteLLMClient.keyInfo(token)).thenReturn(new KeyInfoResponse(token, null));
        when(liteLLMClient.spendLogs(token)).thenReturn(List.of(new SpendLogEntry(10L, null, 4L)));

        LlmUsageSnapshot snapshot = virtualKeyService.fetchUsage(token);

        assertThat(snapshot.spend()).isZero();
        assertThat(snapshot.totalTokens()).isEqualTo(10L);
        assertThat(snapshot.promptTokens()).isZero();
        assertThat(snapshot.completionTokens()).isEqualTo(4L);
    }

    @Test
    void fetchUsage_shouldPropagateKeyInfoErrors() {
        when(liteLLMClient.keyInfo("sk-test")).thenThrow(new RestClientException("LiteLLM unavailable"));

        assertThatThrownBy(() -> virtualKeyService.fetchUsage("sk-test")).isInstanceOf(RestClientException.class);
    }

    @Test
    void fetchUsage_shouldPropagateSpendLogErrors() {
        when(liteLLMClient.keyInfo("sk-test"))
                .thenReturn(new KeyInfoResponse("sk-test", new KeyInfoData("alias", 0.0)));
        when(liteLLMClient.spendLogs("sk-test")).thenThrow(new RestClientException("LiteLLM unavailable"));

        assertThatThrownBy(() -> virtualKeyService.fetchUsage("sk-test")).isInstanceOf(RestClientException.class);
    }

    @Test
    void revokeKey_shouldCallLiteLLMDeleteWithToken() {
        String token = "sk-test-token";
        when(liteLLMClient.deleteKey(any(DeleteKeyRequest.class)))
                .thenReturn(new DeleteKeyResponse(List.of("deleted"), "deleted"));

        virtualKeyService.revokeKey(token);

        ArgumentCaptor<DeleteKeyRequest> requestCaptor = ArgumentCaptor.forClass(DeleteKeyRequest.class);
        verify(liteLLMClient).deleteKey(requestCaptor.capture());
        assertThat(requestCaptor.getValue().keys()).containsExactly(token);
        assertThat(requestCaptor.getValue().keyAliases()).isNull();
    }

    @Test
    void revokeKey_shouldPropagateErrors() {
        when(liteLLMClient.deleteKey(any(DeleteKeyRequest.class))).thenThrow(new RestClientException("Not found"));

        assertThatThrownBy(() -> virtualKeyService.revokeKey("sk-test")).isInstanceOf(RestClientException.class);
    }

    @Test
    void updateKeyModels_shouldCallLiteLLMUpdate() {
        String token = "sk-test-token";
        List<String> models = List.of("provider-model-a-123", "provider-model-b-456");
        when(liteLLMClient.updateKey(any(UpdateKeyRequest.class)))
                .thenReturn(new UpdateKeyResponse(token, "test-alias"));

        virtualKeyService.updateKeyModels(token, models);

        ArgumentCaptor<UpdateKeyRequest> requestCaptor = ArgumentCaptor.forClass(UpdateKeyRequest.class);
        verify(liteLLMClient).updateKey(requestCaptor.capture());
        assertThat(requestCaptor.getValue().key()).isEqualTo(token);
        assertThat(requestCaptor.getValue().models()).isEqualTo(models);
    }

    @Test
    void updateKeyModels_shouldPropagateErrors() {
        when(liteLLMClient.updateKey(any(UpdateKeyRequest.class)))
                .thenThrow(new RestClientException("LiteLLM unavailable"));

        assertThatThrownBy(() -> virtualKeyService.updateKeyModels("sk-test", List.of("model")))
                .isInstanceOf(RestClientException.class)
                .hasMessageContaining("LiteLLM unavailable");
    }
}
