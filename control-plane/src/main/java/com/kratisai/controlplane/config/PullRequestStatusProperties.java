package com.kratisai.controlplane.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@Validated
@ConfigurationProperties(prefix = "kratis.pull-request-status")
public class PullRequestStatusProperties {

    @NotNull
    private Duration maxExecutionAge = Duration.ofDays(14);

    private boolean enabled = true;

    @AssertTrue(message = "kratis.pull-request-status.max-execution-age must be positive")
    public boolean isMaxExecutionAgePositive() {
        return maxExecutionAge == null || !maxExecutionAge.isNegative() && !maxExecutionAge.isZero();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getMaxExecutionAge() {
        return maxExecutionAge;
    }

    public void setMaxExecutionAge(Duration maxExecutionAge) {
        this.maxExecutionAge = maxExecutionAge;
    }
}
