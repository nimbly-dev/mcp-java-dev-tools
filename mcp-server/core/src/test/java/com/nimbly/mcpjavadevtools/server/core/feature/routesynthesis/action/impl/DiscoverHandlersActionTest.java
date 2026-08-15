package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.discovery.RouteSynthesisHandlerDiscovery;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.discovery.RouteSynthesisHandlerDiscoveryResult;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.discoverhandlers.DiscoverHandlersRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.discoverhandlers.DiscoverHandlersResult;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.discoverhandlers.RouteSynthesisHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.result.RouteSynthesisResult;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.routing.RouteSynthesisProbeRouteResolution;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.runtime.RouteSynthesisRuntimeLineResolution;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.workspace.RouteSynthesisWorkspaceProvider;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.workspace.RouteSynthesisWorkspaceSnapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiscoverHandlersActionTest {

    @TempDir
    Path workspace;

    @Test
    void returnsStableReadyInventoryWithValidatedStrictLineKeys() throws IOException {
        Path project = createProject();
        RouteSynthesisHandlerDiscovery discovery = discoveryWith(
                List.of(handler("get", 10), handler("save", 20)));
        DiscoverHandlersAction action = action(discovery, () -> RouteSynthesisRuntimeLineResolution.resolved(11));

        RouteSynthesisResult result = action.execute(new DiscoverHandlersRequest(
                project.toString(), List.of(), "example.WorkController", "probe", "http://probe"));

        assertThat(result.resultType()).isEqualTo("handler_inventory");
        assertThat(result.status()).isEqualTo("ready");
        DiscoverHandlersResult inventory = (DiscoverHandlersResult) result.actionResult();
        assertThat(inventory.handlers()).extracting(RouteSynthesisHandler::methodName)
                .containsExactly("get", "save");
        assertThat(inventory.handlers()).extracting(RouteSynthesisHandler::strictLineKey)
                .containsExactly("example.WorkController#get:11", "example.WorkController#save:11");
    }

    @Test
    void returnsWorkspaceReportWhenWorkspaceIsUnavailable() {
        DiscoverHandlersAction action = new DiscoverHandlersAction(
                Optional::empty,
                (root, additional, hint) -> failure(),
                (probeId, baseUrl) -> RouteSynthesisProbeRouteResolution.unresolved("probe_id_required"),
                (key, start, end, route) -> RouteSynthesisRuntimeLineResolution.unresolved("not_requested"));

        RouteSynthesisResult result = action.execute(new DiscoverHandlersRequest(
                "project", List.of(), "example.WorkController", null, null));

        assertThat(result.resultType()).isEqualTo("report");
        assertThat(result.status()).isEqualTo("workspace_context_missing");
        assertThat(result.reasonCode()).isEqualTo("workspace_context_missing");
        assertThat(result.failedStep()).isEqualTo("workspace_resolution");
    }

    @Test
    void preservesPartialLineValidationAsDeterministicReport() throws IOException {
        Path project = createProject();
        DiscoverHandlersAction action = action(
                discoveryWith(List.of(handler("get", 10))),
                () -> RouteSynthesisRuntimeLineResolution.unresolved("runtime_line_unresolved"));

        RouteSynthesisResult result = action.execute(new DiscoverHandlersRequest(
                project.toString(), List.of(), "example.WorkController", "probe", "http://probe"));

        assertThat(result.resultType()).isEqualTo("handler_inventory");
        assertThat(result.status()).isEqualTo("partial");
        assertThat(result.reasonCode()).isEqualTo("handler_line_validation_partial");
        assertThat(result.failedStep()).isEqualTo("line_validation");
    }

    private DiscoverHandlersAction action(
            RouteSynthesisHandlerDiscovery discovery,
            RuntimeEvidenceFactory evidenceFactory) {
        RouteSynthesisWorkspaceProvider workspaceProvider = () ->
                Optional.of(new RouteSynthesisWorkspaceSnapshot(workspace));
        return new DiscoverHandlersAction(
                workspaceProvider,
                discovery,
                (probeId, baseUrl) -> RouteSynthesisProbeRouteResolution.resolved("http://probe"),
                (key, start, end, route) -> evidenceFactory.create());
    }

    private RouteSynthesisHandlerDiscovery discoveryWith(List<RouteSynthesisHandler> handlers) {
        Path matchedFile = workspace.resolve("project/src/main/java/example/WorkController.java");
        return (root, additional, hint) -> RouteSynthesisHandlerDiscoveryResult.success(
                "example.WorkController", matchedFile, handlers, 2, List.of("handlerCount=2"));
    }

    private RouteSynthesisHandler handler(String method, int line) {
        return new RouteSynthesisHandler(
                "GET", "/work", method, "public String " + method + "()",
                "example.WorkController", line, line + 3, line + 1, "static", "source", null, null);
    }

    private Path createProject() throws IOException {
        Path project = workspace.resolve("project");
        Files.createDirectories(project.resolve("src/main/java/example"));
        Files.writeString(project.resolve("src/main/java/example/WorkController.java"), "package example;");
        return project;
    }

    private RouteSynthesisHandlerDiscoveryResult failure() {
        return RouteSynthesisHandlerDiscoveryResult.failure(
                "unsupported_framework", "framework_detection", "use_supported_framework", List.of());
    }

    @FunctionalInterface
    private interface RuntimeEvidenceFactory {
        RouteSynthesisRuntimeLineResolution create();
    }
}
