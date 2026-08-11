package com.nimbly.mcpjavadevtools.server.mcp.tools.probe;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.actuate.ProbeActuateCommand;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.actuate.ProbeActuateRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.actuate.ProbeActuateResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.capture.ProbeCaptureRecord;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.capture.ProbeCaptureRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.capture.ProbeCaptureResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.check.ProbeCheckEndpointResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.check.ProbeCheckEndpointStatus;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.check.ProbeCheckRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.check.ProbeCheckResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.profiler.ProbeProfilerCommand;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.profiler.ProbeProfilerProvider;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.profiler.ProbeProfilerRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.profiler.ProbeProfilerResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.reset.ProbeBatchResetRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.reset.ProbeClassResetRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.reset.ProbeResetEntry;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.reset.ProbeResetResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.reset.ProbeSingleResetRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.status.ProbeBatchStatusRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.status.ProbeSingleStatusRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.status.ProbeStatusEntry;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.status.ProbeStatusResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.waitforhit.ProbeWaitForHitRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.waitforhit.ProbeWaitForHitResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.waitforhit.ProbeWaitOutcome;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.key.ProbeKeyBatchSelector;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.key.ProbeKeySelector;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeReasonCode;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeReasonMetadata;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.response.ProbeCapturePreview;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.ProbeTargetSelector;
import com.nimbly.mcpjavadevtools.server.mcp.tools.action.McpActionRequest;
import com.nimbly.mcpjavadevtools.server.mcp.tools.action.McpActionResponse;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import org.junit.jupiter.api.Test;

/**
 * Verifies the Java Probe boundary against the checked-in TypeScript compatibility matrix.
 */
class ProbeMcpTypeScriptParityFixtureTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ProbeTargetSelector TARGET =
            new ProbeTargetSelector("orders", "http://probe.example");
    private static final String KEY = "example.Work#doIt:17";
    private static final String SECOND_KEY = "example.Work#save:21";
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    @Test
    void provesSuccessAndFailureParityMatrix() throws IOException {
        try (InputStream fixtureStream = getClass().getResourceAsStream(
                "/probe/probe-typescript-java-parity.json")) {
            assertThat(fixtureStream).isNotNull();
            JsonNode fixture = JSON.readTree(fixtureStream);
            for (JsonNode testCase : fixture.path("success")) {
                assertFixture(testCase, successResponse(testCase.path("kind").asText()));
            }
            for (JsonNode testCase : fixture.path("failure")) {
                assertFixture(testCase, failureResponse(testCase.path("kind").asText()));
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

    private static McpActionResponse successResponse(String kind) {
        ProbeMcpResponseMapper mapper = new ProbeMcpResponseMapper();
        return switch (kind) {
            case "check" -> mapper.map(
                    new ProbeCheckRequest(TARGET, Map.of("Authorization", "secret"), TIMEOUT),
                    ProbeResult.success(checkResult()));
            case "status_single" -> mapper.map(
                    new ProbeSingleStatusRequest(TARGET, new ProbeKeySelector(KEY, null), TIMEOUT),
                    ProbeResult.success(new ProbeStatusResult(List.of(statusEntry(KEY, true, "resolvable")))));
            case "status_batch" -> mapper.map(
                    new ProbeBatchStatusRequest(
                            TARGET,
                            new ProbeKeyBatchSelector(List.of(KEY, SECOND_KEY)),
                            TIMEOUT),
                    ProbeResult.success(new ProbeStatusResult(List.of(
                            statusEntry(KEY, true, "resolvable"),
                            statusEntry(SECOND_KEY, false, "invalid_line_target")))));
            case "reset_single" -> mapper.map(
                    new ProbeSingleResetRequest(TARGET, new ProbeKeySelector(KEY, null), TIMEOUT),
                    ProbeResult.success(new ProbeResetResult(
                            "key", null, null, List.of(new ProbeResetEntry(KEY, true, 200, true, "resolvable")))));
            case "reset_batch" -> mapper.map(
                    new ProbeBatchResetRequest(
                            TARGET,
                            new ProbeKeyBatchSelector(List.of(KEY, SECOND_KEY)),
                            TIMEOUT),
                    ProbeResult.success(new ProbeResetResult(
                            "keys",
                            null,
                            null,
                            List.of(
                                    new ProbeResetEntry(KEY, true, 200, true, "resolvable"),
                                    new ProbeResetEntry(SECOND_KEY, false, 400, false, "invalid_line_target")))));
            case "reset_class" -> mapper.map(
                    new ProbeClassResetRequest(TARGET, "example.Work", TIMEOUT),
                    ProbeResult.success(new ProbeResetResult(
                            "className",
                            "example.Work",
                            null,
                            List.of(new ProbeResetEntry(KEY, true, 200, true, "resolvable")))));
            case "wait" -> mapper.map(
                    new ProbeWaitForHitRequest(
                            TARGET,
                            new ProbeKeySelector(KEY, null),
                            TIMEOUT,
                            Duration.ofMillis(100),
                            2),
                    ProbeResult.success(new ProbeWaitForHitResult(
                            KEY,
                            ProbeWaitOutcome.LINE_HIT,
                            2,
                            0L,
                            3L,
                            123456L,
                            statusEntry(KEY, true, "resolvable"))));
            case "capture" -> mapper.map(
                    new ProbeCaptureRequest(TARGET, "capture-1", TIMEOUT),
                    ProbeResult.success(new ProbeCaptureResult(
                            true,
                            new ProbeCaptureRecord(
                                    "capture-1",
                                    "example.Work#doIt",
                                    123456L,
                                    123400L,
                                    123455L,
                                    55L,
                                    4096L,
                                    "safe",
                                    2,
                                    true,
                                    false,
                                    false,
                                    List.of("example.Work#doIt()#17")),
                            null)));
            case "actuate" -> mapper.map(
                    new ProbeActuateRequest(
                            TARGET,
                            ProbeActuateCommand.ARM,
                            "session-1",
                            "actuator-1",
                            KEY,
                            true,
                            15000L,
                            TIMEOUT),
                    ProbeResult.success(new ProbeActuateResult(
                            true,
                            "arm",
                            "actuate",
                            "session-1",
                            "actuator-1",
                            KEY,
                            true,
                            15000L,
                            123456L,
                            "armed",
                            null)));
            case "profiler" -> mapper.map(
                    new ProbeProfilerRequest(
                            TARGET,
                            ProbeProfilerCommand.STATUS,
                            "session-1",
                            ProbeProfilerProvider.AUTO,
                            null,
                            null,
                            null,
                            null,
                            TIMEOUT),
                    ProbeResult.success(new ProbeProfilerResult(
                            "status",
                            "idle",
                            true,
                            "auto",
                            "session-1",
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null)));
            default -> throw new IllegalArgumentException("Unknown parity success case: " + kind);
        };
    }

    private static McpActionResponse failureResponse(String kind) {
        return switch (kind) {
            case "invalid_request" -> new ProbeMcpTool(request -> ProbeResult.success())
                    .invokeMcpRequest(new McpActionRequest<>("unknown", null));
            case "line_key_required" -> new ProbeMcpResponseMapper().map(
                    new ProbeSingleStatusRequest(
                            TARGET,
                            new ProbeKeySelector("example.Work#doIt", null),
                            TIMEOUT),
                    ProbeResult.failure(ProbeReasonCode.LINE_KEY_REQUIRED, ProbeReasonMetadata.status()));
            case "probe_unreachable" -> new ProbeMcpResponseMapper().map(
                    new ProbeSingleStatusRequest(TARGET, new ProbeKeySelector(KEY, null), TIMEOUT),
                    ProbeResult.failure(ProbeReasonCode.PROBE_UNREACHABLE, ProbeReasonMetadata.status()));
            default -> throw new IllegalArgumentException("Unknown parity failure case: " + kind);
        };
    }

    private static ProbeCheckResult checkResult() {
        ProbeCheckEndpointResult endpoint = new ProbeCheckEndpointResult(
                ProbeCheckEndpointStatus.AVAILABLE,
                200,
                "mcp.jvm.diagnose#key",
                Map.of("content-type", "application/json"),
                null);
        return new ProbeCheckResult(
                endpoint,
                endpoint,
                null,
                List.of("rerun status after the next request"));
    }

    private static ProbeStatusEntry statusEntry(String key, boolean lineResolvable, String lineValidation) {
        return new ProbeStatusEntry(
                key,
                key,
                lineResolvable ? 200 : 400,
                lineResolvable,
                lineResolvable ? 3L : 0L,
                lineResolvable ? 123456L : 0L,
                lineResolvable,
                lineValidation,
                new ProbeCapturePreview(false, null, null, null, null, null, null, null, null, false, List.of()),
                null);
    }
}
