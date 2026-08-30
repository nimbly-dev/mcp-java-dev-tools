package com.nimbly.mcpjavadevtools.server.core.feature.probe.operation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.ProbeActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.ProbeAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.request.ProbeRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.ProbeTargetSelector;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProbeOperationCatalogTest {

    @Test
    void registersEveryPublicProbeActionInClosedEnumOrder() {
        ProbeOperationCatalog catalog = new ProbeOperationCatalog(handlers());

        assertThat(catalog.catalog())
                .extracting(descriptor -> descriptor.action())
                .containsExactly("check", "status", "reset", "wait_for_hit", "capture", "actuate", "profiler");
        assertThat(catalog.traceInventory())
                .extracting(entry -> entry.operationCatalog())
                .containsOnly(ProbeOperationCatalog.class.getName());
        assertThat(catalog.traceInventory())
                .extracting(entry -> entry.sideEffect())
                .containsExactly(
                        "probe_endpoint_read",
                        "probe_endpoint_read",
                        "probe_endpoint_write",
                        "probe_endpoint_poll",
                        "probe_capture_read",
                        "probe_endpoint_write",
                        "probe_artifact_write");
    }

    @Test
    void executesThroughTheOwnerForTheSelectedAction() {
        ProbeOperationCatalog catalog = new ProbeOperationCatalog(handlers());

        ProbeResult result = catalog.execute(ProbeAction.CAPTURE, new CaptureRequest());

        assertThat(result).isEqualTo(ProbeResult.success());
    }

    @Test
    void rejectsAnIncompleteActionSetBeforeTheFeatureCanBeCreated() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ProbeOperationCatalog(
                        handlers().stream()
                                .filter(handler -> handler.action() != ProbeAction.PROFILER)
                                .toList()))
                .withMessage("missing operation: PROFILER");
    }

    private static List<ProbeActionHandler> handlers() {
        return Arrays.stream(ProbeAction.values())
                .map(ProbeOperationCatalogTest::handler)
                .toList();
    }

    private static ProbeActionHandler handler(ProbeAction action) {
        return new ProbeActionHandler() {
            @Override
            public ProbeAction action() {
                return action;
            }

            @Override
            public ProbeResult execute(ProbeRequest request) {
                return ProbeResult.success();
            }
        };
    }

    private static final class CaptureRequest implements ProbeRequest {

        @Override
        public ProbeAction action() {
            return ProbeAction.CAPTURE;
        }

        @Override
        public ProbeTargetSelector targetSelector() {
            return new ProbeTargetSelector(null, null);
        }
    }
}
