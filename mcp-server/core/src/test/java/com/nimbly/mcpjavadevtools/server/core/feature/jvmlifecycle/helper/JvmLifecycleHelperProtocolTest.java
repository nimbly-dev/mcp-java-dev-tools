package com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.helper;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class JvmLifecycleHelperProtocolTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesTheBoundedHelperProtocol() {
        String response = """
                {"operation":"attach","outcome":"active","reasonCode":"active",
                "pids":[],"candidates":[],"nonRestorableClasses":[]}
                """;

        assertThat(JvmLifecycleHelperProtocol.parse(response, mapper)).isPresent();
    }

    @Test
    void rejectsMalformedOrUnboundedCandidateOutput() {
        String response = """
                {"operation":"discover","outcome":"unverified","reasonCode":"ok",
                "pids":["1"],"candidates":[{"pid":"1","identityHint":null,
                "identitySource":"unsafe","frameworkHint":"unknown",
                "frameworkEvidence":[],"processStartEpochMs":1}],
                "nonRestorableClasses":[]}
                """;

        assertThat(JvmLifecycleHelperProtocol.parse(response, mapper)).isEmpty();
    }
}
