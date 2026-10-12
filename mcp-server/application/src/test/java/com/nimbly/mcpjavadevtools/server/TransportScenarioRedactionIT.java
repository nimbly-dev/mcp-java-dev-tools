package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Canaries for the harness assertion, using the JVM's real startup stderr output. */
@Tag("transport")
@Timeout(90)
class TransportScenarioRedactionIT extends McpServerStdioSupport {
    @ParameterizedTest
    @ValueSource(ints = {0, 17000})
    void rawStderrLeaksFailEvenWhenDiagnosticsRedactOrTruncateThem(int padding) throws Exception {
        String secret = UUID.randomUUID().toString();
        Map<String, String> environment = Map.of("JAVA_TOOL_OPTIONS", "-Dpadding=" + "x".repeat(padding) + " -Dtoken=" + secret);
        TransportScenario scenario = TransportScenario.startWithSecrets(JavaItScenario.workspace(), environment, List.of(secret));
        try {
            // Draining avoids any ordering assumption between the independent stdout and stderr readers.
            scenario.server.closeInputAndAwaitTermination();
            assertThat(scenario.server.stderrText().contains(secret)).as("diagnostics conceal the injected sentinel").isFalse();
            assertThat(scenario.server.stderrText()).contains("Picked up JAVA_TOOL_OPTIONS:");
            assertThatThrownBy(() -> scenario.assertSecretsAbsent(secret)).isInstanceOf(AssertionError.class)
                    .hasMessageContaining("raw server stderr").hasMessageNotContaining(secret);
            assertThatThrownBy(scenario::close).isInstanceOf(AssertionError.class)
                    .hasMessageContaining("raw server stderr").hasMessageNotContaining(secret);
        } finally {
            scenario.server.close();
        }
    }

    @Test
    void alreadyRedactedStderrPassesTheRawCheck() throws Exception {
        String secret = UUID.randomUUID().toString();
        try (TransportScenario scenario = TransportScenario.startWithSecrets(JavaItScenario.workspace(),
                Map.of("JAVA_TOOL_OPTIONS", "-Dtoken=[REDACTED]"), List.of(secret))) {
            scenario.server.closeInputAndAwaitTermination();
            assertThat(scenario.server.stderrText()).contains("Picked up JAVA_TOOL_OPTIONS:");
            scenario.assertSecretsAbsent(secret);
        }
    }
}
