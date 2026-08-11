package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class ProbeResultTest {

    @Test
    void rejectsContradictoryStatusAndReasonCodeCombinations() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ProbeResult(
                ProbeResultStatus.SUCCESS,
                ProbeReasonCode.INVALID_REQUEST,
                ProbeReasonMetadata.empty()));
        assertThatIllegalArgumentException().isThrownBy(() -> new ProbeResult(
                ProbeResultStatus.FAILURE,
                ProbeReasonCode.SUCCESS,
                ProbeReasonMetadata.empty()));
        assertThatIllegalArgumentException().isThrownBy(() -> new ProbeResult(
                ProbeResultStatus.BLOCKED,
                ProbeReasonCode.INVALID_REQUEST,
                ProbeReasonMetadata.empty()));
    }

    @Test
    void boundsUserControlledProbeIdentifiersAndRejectsInvalidCounts() {
        ProbeReasonMetadata metadata = ProbeReasonMetadata.routing("a".repeat(129), 10);

        assertThat(metadata.probeId()).hasSize(128);
        assertThatIllegalArgumentException().isThrownBy(() -> new ProbeReasonMetadata(
                ProbeFailureStep.PROBE_REGISTRY_RESOLUTION,
                "probe",
                -1));
    }

    @Test
    void exposesCanonicalStatusAndNextActionPolicyFromCore() {
        ProbeResult result = ProbeResult.blocked(
                ProbeReasonCode.PROBE_ID_REQUIRED,
                ProbeReasonMetadata.routing(null, 2));

        assertThat(result.policy().status()).isEqualTo("probe_selection_failed");
        assertThat(result.policy().nextActionCode()).isEqualTo("provide_probe_id");
        assertThat(result.policy().nextAction())
                .isEqualTo("Provide probeId or baseUrl. Multi-probe profiles require explicit selection.");
    }
}
