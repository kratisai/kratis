package com.kratisai.controlplane.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TelemetryPropertiesTest {

    @Test
    void enabledByDefaultWhenNoOptOutFlagsSet() {
        assertThat(new TelemetryProperties().isEnabled()).isTrue();
    }

    @Test
    void kratisTelemetryDisabledDisables() {
        TelemetryProperties props = new TelemetryProperties();
        props.setDisabled("1");
        assertThat(props.isEnabled()).isFalse();

        props.setDisabled("true");
        assertThat(props.isEnabled()).isFalse();

        props.setDisabled("TRUE");
        assertThat(props.isEnabled()).isFalse();

        props.setDisabled("false");
        assertThat(props.isEnabled()).isTrue();
    }

    @Test
    void doNotTrackDisables() {
        TelemetryProperties props = new TelemetryProperties();
        props.setDoNotTrack("1");
        assertThat(props.isEnabled()).isFalse();

        props.setDoNotTrack("true");
        assertThat(props.isEnabled()).isFalse();

        props.setDoNotTrack("0");
        assertThat(props.isEnabled()).isTrue();
    }

    @Test
    void eitherFlagDisablesEvenWhenOtherIsUnset() {
        TelemetryProperties viaKratis = new TelemetryProperties();
        viaKratis.setDisabled("1");
        assertThat(viaKratis.isEnabled()).isFalse();

        TelemetryProperties viaDnt = new TelemetryProperties();
        viaDnt.setDoNotTrack("1");
        assertThat(viaDnt.isEnabled()).isFalse();
    }

    @Test
    void whitespaceAndNullAreHandled() {
        TelemetryProperties props = new TelemetryProperties();
        props.setDisabled(" 1 ");
        assertThat(props.isEnabled()).isFalse();

        props.setDisabled(null);
        props.setDoNotTrack(null);
        assertThat(props.isEnabled()).isTrue();
    }

    @Test
    void defaultsAreExposed() {
        TelemetryProperties props = new TelemetryProperties();
        assertThat(props.getVersion()).isEqualTo("unknown");
        assertThat(props.getBuildTag()).isEqualTo("unknown");
        assertThat(props.getEndpoint()).isEqualTo("https://us.i.posthog.com/capture/");
        assertThat(props.getFixedDelayMs()).isEqualTo(86_400_000);
        assertThat(props.getInitialDelayMs()).isEqualTo(60_000);
        assertThat(props.getHttpTimeoutMs()).isEqualTo(10_000);
    }
}
