package com.kratisai.controlplane.api.wsdto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import java.util.Objects;

/**
 * Control-plane → connector result payloads. Connector → control-plane results live in
 * {@link EnvironmentConnectorResult}.
 */
public sealed interface EnvironmentResponsePayload
        permits EnvironmentResponsePayload.EnvironmentRegisterResult,
                EnvironmentResponsePayload.EnvironmentHeartbeatResult,
                EnvironmentResponsePayload.HitlResult,
                EnvironmentResponsePayload.GitTokenResult {

    record EnvironmentRegisterResult(EnvironmentResultType type, EnvironmentRegisterStatus status, String environmentId)
            implements EnvironmentResponsePayload {
        public EnvironmentRegisterResult {
            Objects.requireNonNull(type, "type is required");
            Objects.requireNonNull(status, "status is required");
            Objects.requireNonNull(environmentId, "environmentId is required");
        }

        public EnvironmentRegisterResult(String environmentId) {
            this(EnvironmentResultType.ENV_REGISTER, EnvironmentRegisterStatus.REGISTERED, environmentId);
        }

        public static EnvironmentRegisterResult reconnected(String environmentId) {
            return new EnvironmentRegisterResult(
                    EnvironmentResultType.ENV_REGISTER, EnvironmentRegisterStatus.RECONNECTED, environmentId);
        }
    }

    record EnvironmentHeartbeatResult(EnvironmentResultType type, EnvironmentHeartbeatStatus status)
            implements EnvironmentResponsePayload {
        public EnvironmentHeartbeatResult {
            Objects.requireNonNull(type, "type is required");
            Objects.requireNonNull(status, "status is required");
        }

        public EnvironmentHeartbeatResult() {
            this(EnvironmentResultType.ENV_HEARTBEAT, EnvironmentHeartbeatStatus.OK);
        }
    }

    record HitlResult(
            @JsonProperty("response") HitlResponse response,
            @JsonProperty("optionId") String optionId,
            @JsonProperty("content") Map<String, Object> content)
            implements EnvironmentResponsePayload {
        public HitlResult {
            Objects.requireNonNull(response, "response is required");
        }

        public static HitlResult approved(String optionId) {
            return new HitlResult(HitlResponse.APPROVED, optionId, null);
        }

        public static HitlResult answered(Map<String, Object> content) {
            return new HitlResult(HitlResponse.ANSWERED, null, content);
        }

        public static HitlResult declined() {
            return new HitlResult(HitlResponse.DECLINED, null, null);
        }

        public static HitlResult cancelled() {
            return new HitlResult(HitlResponse.CANCELLED, null, null);
        }
    }

    record GitTokenResult(@JsonProperty("token") String token) implements EnvironmentResponsePayload {
        public GitTokenResult {
            Objects.requireNonNull(token, "token is required");
        }
    }
}
