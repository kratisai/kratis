package com.kratisai.controlplane.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.api.wsdto.RpcPayload;
import com.kratisai.controlplane.config.TransientDatabaseExceptionClassifier;
import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;

public final class JsonRpcInbound {

    private static final Logger logger = LoggerFactory.getLogger(JsonRpcInbound.class);
    private static final int MAX_TRANSIENT_RETRIES = 2;
    private static final Duration INITIAL_RETRY_BACKOFF = Duration.ofMillis(50);

    private JsonRpcInbound() {}

    public static Object deserializeParams(ObjectMapper mapper, JsonNode params, Class<?> payloadType)
            throws Exception {
        if (params != null && !params.isNull()) {
            return mapper.treeToValue(params, payloadType);
        }
        if (RpcPayload.NoParamsPayload.class.isAssignableFrom(payloadType)) {
            return mapper.treeToValue(mapper.createObjectNode(), payloadType);
        }
        throw new IllegalArgumentException("Missing required params");
    }

    public static <R> Flux<R> invoke(Supplier<Flux<R>> handler) {
        return Flux.defer(() -> {
                    try {
                        Flux<R> result = handler.get();
                        return result == null ? Flux.empty() : result;
                    } catch (RuntimeException e) {
                        return Flux.error(e);
                    }
                })
                .retryWhen(Retry.backoff(MAX_TRANSIENT_RETRIES, INITIAL_RETRY_BACKOFF)
                        .filter(TransientDatabaseExceptionClassifier::isTransient)
                        .doBeforeRetry(signal -> logger.warn(
                                "Retrying RPC invocation (attempt {}/{}) due to transient DB connection failure: {}",
                                signal.totalRetries() + 1,
                                MAX_TRANSIENT_RETRIES,
                                signal.failure().getMessage())))
                .onErrorMap(Exceptions::isRetryExhausted, Throwable::getCause);
    }
}
