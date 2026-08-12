package com.nimbly.mcpjavadevtools.server.mcp.tools.jvmlifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.action.attach.AttachRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.action.deactivate.DeactivateRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.action.listjvms.JvmListResult;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.action.listjvms.ListJvmsRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.candidate.JvmCandidate;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.result.JvmLifecycleResult;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.result.JvmLifecycleResultStatus;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.result.JvmMutationResult;
import com.nimbly.mcpjavadevtools.server.mcp.tools.action.McpActionResponse;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import org.junit.jupiter.api.Test;

/**
 * Verifies JVM lifecycle output against the checked-in TypeScript contract fixture.
 */
class JvmLifecycleMcpTypeScriptParityFixtureTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void provesSuccessAndFailureParityMatrix() throws IOException {
        try (InputStream fixtureStream = getClass().getResourceAsStream(
                "/jvm-lifecycle/jvm-lifecycle-typescript-java-parity.json")) {
            assertThat(fixtureStream).isNotNull();
            JsonNode fixture = JSON.readTree(fixtureStream);
            for (JsonNode testCase : fixture.path("success")) {
                assertFixture(testCase, response(testCase.path("kind").asText()));
            }
            for (JsonNode testCase : fixture.path("failure")) {
                assertFixture(testCase, response(testCase.path("kind").asText()));
            }
        }
    }

    private static void assertFixture(JsonNode testCase, McpActionResponse response) {
        JsonNode actual = JSON.valueToTree(response);
        Iterator<Entry<String, JsonNode>> assertions = testCase.path("assertions").fields();
        while (assertions.hasNext()) {
            Entry<String, JsonNode> assertion = assertions.next();
            assertThat(actual.at(assertion.getKey()).toString())
                    .as("%s %s", testCase.path("name").asText(), assertion.getKey())
                    .isEqualTo(assertion.getValue().toString());
        }
    }

    private static McpActionResponse response(String kind) {
        JvmLifecycleMcpResponseMapper mapper = new JvmLifecycleMcpResponseMapper();
        return switch (kind) {
            case "list_jvms" -> mapper.map(
                    new ListJvmsRequest(),
                    JvmLifecycleResult.discovery(
                            "jvm_discovery_unverified",
                            new JvmListResult(List.of(new JvmCandidate(
                                    "1234", "orders.jar", "sanitized_attach_descriptor",
                                    "spring_boot_candidate", List.of("executable_jar_name"),
                                    1720000000000L)))));
            case "attach_active" -> mapper.map(
                    new AttachRequest("1234", 1720000000000L, true,
                            "127.0.0.1", 9191, null, null),
                    JvmLifecycleResult.mutation(
                            JvmLifecycleResultStatus.OK,
                            "active",
                            new JvmMutationResult(
                                    "attach", "active", "1234", 1720000000000L,
                                    "127.0.0.1", 9191, List.of())));
            case "deactivate_deactivated" -> mapper.map(
                    new DeactivateRequest("1234", 1720000000000L, true),
                    JvmLifecycleResult.mutation(
                            JvmLifecycleResultStatus.OK,
                            "deactivated",
                            new JvmMutationResult(
                                    "deactivate", "deactivated", "1234", 1720000000000L,
                                    null, null, List.of())));
            case "invalid_request" -> mapper.invalidRequest();
            case "probe_host_blocked" -> mapper.map(
                    new AttachRequest("1234", 1720000000000L, true,
                            "192.0.2.1", 9191, null, null),
                    JvmLifecycleResult.blocked("probe_host_not_allowed"));
            default -> throw new IllegalArgumentException("Unknown lifecycle fixture: " + kind);
        };
    }
}
