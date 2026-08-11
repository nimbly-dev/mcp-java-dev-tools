package com.nimbly.mcpjavadevtools.server.core.feature.probe.action.profiler;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.profiler.impl.ProbeProfilerAction;
import static org.assertj.core.api.Assertions.assertThat;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.endpoint.ProbeEndpointClient;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.profiler.ProbeProfilerCommand;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.profiler.ProbeProfilerDownload;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.profiler.ProbeProfilerRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.profiler.ProbeProfilerResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointConfiguration;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointLimits;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointPaths;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointResponse;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeRequestBounds;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeRequestPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.response.ProbeResponseCompactionPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeReasonCode;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.ProbeTargetSelector;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.routing.ProbeTargetResolver;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProbeProfilerActionTest {

    @Test
    void startsTheExistingSidecarProfilerWithAValidatedSession() {
        ProbeEndpointConfiguration configuration = configuration();
        ProbeEndpointClient client = request -> new ProbeEndpointResponse(
                200,
                Map.of(),
                "{\"ok\":true,\"profiler\":{\"status\":\"running\",\"supported\":true,\"sessionId\":\"session-1\"}}",
                configuration);

        ProbeResult result = action(configuration, client, (path, bytes) -> new ProbeProfilerDownload(path, bytes.length))
                .execute(new ProbeProfilerRequest(
                        new ProbeTargetSelector(null, "http://127.0.0.1:9191"),
                        ProbeProfilerCommand.START,
                        "session-1",
                        null,
                        null,
                        null,
                        null,
                        "jfr",
                        null));

        assertThat(result.reasonCode()).isEqualTo(ProbeReasonCode.SUCCESS);
        assertThat(((ProbeProfilerResult) result.actionResult().orElseThrow()).status()).isEqualTo("running");
    }

    @Test
    void writesOnlyTheBoundedDownloadedBytesThroughTheOwnedOutputStore() {
        ProbeEndpointConfiguration configuration = configuration();
        ProbeEndpointClient client = request -> new ProbeEndpointResponse(
                200,
                Map.of(),
                "binary-jfr",
                configuration);

        ProbeResult result = action(configuration, client, (path, bytes) -> {
            assertThat(bytes).containsExactly("binary-jfr".getBytes());
            return new ProbeProfilerDownload("C:/temp/result.jfr", bytes.length);
        }).execute(new ProbeProfilerRequest(
                new ProbeTargetSelector(null, "http://127.0.0.1:9191"),
                ProbeProfilerCommand.DOWNLOAD,
                "session-1",
                null,
                null,
                null,
                "C:/temp/result.jfr",
                "jfr",
                null));

        ProbeProfilerResult profiler = (ProbeProfilerResult) result.actionResult().orElseThrow();
        assertThat(result.reasonCode()).isEqualTo(ProbeReasonCode.SUCCESS);
        assertThat(profiler.downloadedBytes()).isEqualTo(10L);
        assertThat(profiler.outputPath()).isEqualTo("C:/temp/result.jfr");
    }

    private ProbeProfilerAction action(
            ProbeEndpointConfiguration configuration,
            ProbeEndpointClient client,
            ProbeProfilerOutputStore outputStore) {
        return new ProbeProfilerAction(
                new ProbeTargetResolver(configuration, null),
                configuration,
                client,
                new ProbeResponseCompactionPolicy(false, 64, 2, 8, 64, Set.of("content-type")),
                outputStore);
    }

    private ProbeEndpointConfiguration configuration() {
        ProbeRequestBounds bounds = new ProbeRequestBounds(
                Duration.ofSeconds(1), Duration.ofSeconds(60), Duration.ofMillis(100), Duration.ofSeconds(5), 1, 10);
        return new ProbeEndpointConfiguration(
                null,
                new ProbeEndpointPaths("/__probe/status", "/__probe/reset", "/__probe/actuate", "/__probe/capture", "/__probe/profiler"),
                new ProbeRequestPolicy(Duration.ofSeconds(15), Duration.ofMillis(500), 1, false, 3, bounds),
                new ProbeEndpointLimits(64, 128, 4096, 65536, 1048576));
    }
}
