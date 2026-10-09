package com.kratisai.controlplane.api.wsdto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Control-plane → connector result payloads. Connector → control-plane results live in
 * {@link EnvironmentConnectorResult}.
 */
public sealed interface EnvironmentResponsePayload
        permits EnvironmentResponsePayload.EnvironmentRegisterResult,
                EnvironmentResponsePayload.EnvironmentHeartbeatResult,
                EnvironmentResponsePayload.DiffManifestResult,
                EnvironmentResponsePayload.DiffSectionsResult,
                EnvironmentResponsePayload.HitlActivityResult,
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

    record HitlActivityResult(
            @JsonProperty("response") HitlResponse response,
            @JsonProperty("optionId") String optionId,
            @JsonProperty("content") Map<String, Object> content)
            implements EnvironmentResponsePayload {
        public HitlActivityResult {
            Objects.requireNonNull(response, "response is required");
        }

        public static HitlActivityResult approved(String optionId) {
            return new HitlActivityResult(HitlResponse.APPROVED, optionId, null);
        }

        public static HitlActivityResult answered(Map<String, Object> content) {
            return new HitlActivityResult(HitlResponse.ANSWERED, null, content);
        }

        public static HitlActivityResult declined() {
            return new HitlActivityResult(HitlResponse.DECLINED, null, null);
        }

        public static HitlActivityResult cancelled() {
            return new HitlActivityResult(HitlResponse.CANCELLED, null, null);
        }
    }

    record GitTokenResult(@JsonProperty("token") String token) implements EnvironmentResponsePayload {
        public GitTokenResult {
            Objects.requireNonNull(token, "token is required");
        }
    }

    enum DiffManifestStatus {
        @JsonProperty("committed")
        COMMITTED,
        @JsonProperty("incomplete")
        INCOMPLETE
    }

    enum DiffSectionsStatus {
        @JsonProperty("stored")
        STORED
    }

    record DiffManifestResult(
            @JsonProperty("type") EnvironmentResultType type,
            @JsonProperty("status") DiffManifestStatus status,
            @JsonProperty("missing") List<String> missing)
            implements EnvironmentResponsePayload {
        public DiffManifestResult {
            Objects.requireNonNull(type, "type is required");
            Objects.requireNonNull(status, "status is required");
            Objects.requireNonNull(missing, "missing is required");
            missing = List.copyOf(missing);
        }

        public DiffManifestResult(DiffManifestStatus status, List<String> missing) {
            this(EnvironmentResultType.ENV_DIFF_MANIFEST, status, missing);
        }
    }

    record DiffSectionsResult(
            @JsonProperty("type") EnvironmentResultType type,
            @JsonProperty("status") DiffSectionsStatus status,
            @JsonProperty("missing") List<String> missing)
            implements EnvironmentResponsePayload {
        public DiffSectionsResult {
            Objects.requireNonNull(type, "type is required");
            Objects.requireNonNull(status, "status is required");
            Objects.requireNonNull(missing, "missing is required");
            missing = List.copyOf(missing);
        }

        public DiffSectionsResult(DiffSectionsStatus status, List<String> missing) {
            this(EnvironmentResultType.ENV_DIFF_SECTIONS, status, missing);
        }
    }
}
