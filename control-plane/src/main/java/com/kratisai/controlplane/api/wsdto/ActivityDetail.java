package com.kratisai.controlplane.api.wsdto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ActivityDetail(
        @JsonProperty("kind") ActivityKind kind,
        @JsonProperty("title") String title,
        @JsonProperty("locations") List<ActivityLocation> locations,
        @JsonProperty("output") String output,
        @JsonProperty("diff") ActivityDiff diff,
        @JsonProperty("exitCode") Integer exitCode,
        @JsonProperty("truncated") Boolean truncated,
        @JsonProperty("messageId") String messageId,
        @JsonProperty("role") String role,

        @JsonInclude(JsonInclude.Include.NON_EMPTY) @JsonProperty("plan")
        List<PlanEntry> plan,

        @JsonProperty("hitl") ActivityHitl hitl,
        @JsonProperty("prNumber") Long prNumber,
        @JsonProperty("prUrl") String prUrl,
        @JsonProperty("headBranch") String headBranch,
        @JsonProperty("baseBranch") String baseBranch,
        @JsonProperty("commitSha") String commitSha,
        @JsonProperty("isUpdate") Boolean isUpdate,
        @JsonProperty("providerType") String providerType) {

    public static Builder builder() {
        return new Builder();
    }

    public static ActivityDetail empty() {
        return builder().build();
    }

    public Builder toBuilder() {
        return new Builder()
                .kind(kind)
                .title(title)
                .locations(locations)
                .output(output)
                .diff(diff)
                .exitCode(exitCode)
                .truncated(truncated)
                .messageId(messageId)
                .role(role)
                .plan(plan)
                .hitl(hitl)
                .prNumber(prNumber)
                .prUrl(prUrl)
                .headBranch(headBranch)
                .baseBranch(baseBranch)
                .commitSha(commitSha)
                .isUpdate(isUpdate)
                .providerType(providerType);
    }

    public ActivityDetail withHitl(ActivityHitl hitl) {
        return toBuilder().hitl(hitl).build();
    }

    public static ActivityDetail forPublish(
            Long prNumber,
            String prUrl,
            String headBranch,
            String baseBranch,
            String commitSha,
            Boolean isUpdate,
            String providerType) {
        return builder()
                .prNumber(prNumber)
                .prUrl(prUrl)
                .headBranch(headBranch)
                .baseBranch(baseBranch)
                .commitSha(commitSha)
                .isUpdate(isUpdate)
                .providerType(providerType)
                .build();
    }

    public static ActivityDetail forPlan(List<PlanEntry> plan) {
        return builder().plan(plan).build();
    }

    public static ActivityDetail forCommand(String title) {
        return builder().title(title).build();
    }

    public static ActivityDetail forCommand(String title, String output, Integer exitCode) {
        return builder().title(title).output(output).exitCode(exitCode).build();
    }

    public static ActivityDetail forTool(ActivityKind kind, String title, Integer exitCode, Boolean truncated) {
        return builder()
                .kind(kind)
                .title(title)
                .exitCode(exitCode)
                .truncated(truncated)
                .build();
    }

    public static ActivityDetail forMessage(String messageId, String role) {
        return builder().messageId(messageId).role(role).build();
    }

    public static ActivityDetail forError(Integer exitCode) {
        return builder().exitCode(exitCode).build();
    }

    public static ActivityDetail forHitl(ActivityHitl hitl) {
        return builder().hitl(hitl).build();
    }

    public static final class Builder {
        private ActivityKind kind;
        private String title;
        private List<ActivityLocation> locations;
        private String output;
        private ActivityDiff diff;
        private Integer exitCode;
        private Boolean truncated;
        private String messageId;
        private String role;
        private List<PlanEntry> plan;
        private ActivityHitl hitl;
        private Long prNumber;
        private String prUrl;
        private String headBranch;
        private String baseBranch;
        private String commitSha;
        private Boolean isUpdate;
        private String providerType;

        public Builder kind(ActivityKind kind) {
            this.kind = kind;
            return this;
        }

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public Builder locations(List<ActivityLocation> locations) {
            this.locations = locations;
            return this;
        }

        public Builder output(String output) {
            this.output = output;
            return this;
        }

        public Builder diff(ActivityDiff diff) {
            this.diff = diff;
            return this;
        }

        public Builder exitCode(Integer exitCode) {
            this.exitCode = exitCode;
            return this;
        }

        public Builder truncated(Boolean truncated) {
            this.truncated = truncated;
            return this;
        }

        public Builder messageId(String messageId) {
            this.messageId = messageId;
            return this;
        }

        public Builder role(String role) {
            this.role = role;
            return this;
        }

        public Builder plan(List<PlanEntry> plan) {
            this.plan = plan;
            return this;
        }

        public Builder hitl(ActivityHitl hitl) {
            this.hitl = hitl;
            return this;
        }

        public Builder prNumber(Long prNumber) {
            this.prNumber = prNumber;
            return this;
        }

        public Builder prUrl(String prUrl) {
            this.prUrl = prUrl;
            return this;
        }

        public Builder headBranch(String headBranch) {
            this.headBranch = headBranch;
            return this;
        }

        public Builder baseBranch(String baseBranch) {
            this.baseBranch = baseBranch;
            return this;
        }

        public Builder commitSha(String commitSha) {
            this.commitSha = commitSha;
            return this;
        }

        public Builder isUpdate(Boolean isUpdate) {
            this.isUpdate = isUpdate;
            return this;
        }

        public Builder providerType(String providerType) {
            this.providerType = providerType;
            return this;
        }

        public ActivityDetail build() {
            return new ActivityDetail(
                    kind,
                    title,
                    locations,
                    output,
                    diff,
                    exitCode,
                    truncated,
                    messageId,
                    role,
                    plan,
                    hitl,
                    prNumber,
                    prUrl,
                    headBranch,
                    baseBranch,
                    commitSha,
                    isUpdate,
                    providerType);
        }
    }
}
