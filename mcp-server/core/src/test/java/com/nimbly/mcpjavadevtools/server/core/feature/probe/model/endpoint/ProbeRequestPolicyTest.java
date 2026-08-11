package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ProbeRequestPolicyTest {

    @Test
    void clampsTimeoutPollAndRetryValuesToTheTypeScriptSafetyRanges() {
        ProbeRequestPolicy policy = new ProbeRequestPolicy(
                Duration.ofHours(2),
                Duration.ofMillis(1),
                50,
                true,
                0,
                bounds());

        assertThat(policy.defaultTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(policy.defaultPollInterval()).isEqualTo(Duration.ofMillis(100));
        assertThat(policy.maxRetries()).isEqualTo(10);
        assertThat(policy.unreachableMaxRetries()).isEqualTo(1);
        assertThat(policy.timeoutOrDefault(null)).isEqualTo(Duration.ofSeconds(60));
        assertThat(policy.timeoutOrDefault(Duration.ofMillis(1))).isEqualTo(Duration.ofSeconds(1));
        assertThat(policy.timeoutOrDefault(Duration.ofHours(1))).isEqualTo(Duration.ofSeconds(60));
        assertThat(policy.bounds().clampPollInterval(Duration.ofMinutes(1)))
                .isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void retainsValuesAlreadyWithinTheTypeScriptSafetyRanges() {
        ProbeRequestPolicy policy = new ProbeRequestPolicy(
                Duration.ofSeconds(2),
                Duration.ofMillis(500),
                3,
                false,
                2,
                bounds());

        assertThat(policy.defaultTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(policy.defaultPollInterval()).isEqualTo(Duration.ofMillis(500));
        assertThat(policy.maxRetries()).isEqualTo(3);
        assertThat(policy.unreachableMaxRetries()).isEqualTo(2);
    }

    @Test
    void rejectsConfiguredBoundsThatExceedCoreSafetyCeilings() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ProbeRequestBounds(
                Duration.ofSeconds(1),
                Duration.ofSeconds(61),
                Duration.ofMillis(100),
                Duration.ofSeconds(5),
                1,
                10));
        assertThatIllegalArgumentException().isThrownBy(() -> new ProbeRequestBounds(
                Duration.ofSeconds(1),
                Duration.ofSeconds(60),
                Duration.ofMillis(99),
                Duration.ofSeconds(5),
                1,
                10));
        assertThatIllegalArgumentException().isThrownBy(() -> new ProbeRequestBounds(
                Duration.ofSeconds(1),
                Duration.ofSeconds(60),
                Duration.ofMillis(100),
                Duration.ofSeconds(5),
                1,
                11));
    }

    private ProbeRequestBounds bounds() {
        return new ProbeRequestBounds(
                Duration.ofSeconds(1),
                Duration.ofSeconds(60),
                Duration.ofMillis(100),
                Duration.ofSeconds(5),
                1,
                10);
    }
}
