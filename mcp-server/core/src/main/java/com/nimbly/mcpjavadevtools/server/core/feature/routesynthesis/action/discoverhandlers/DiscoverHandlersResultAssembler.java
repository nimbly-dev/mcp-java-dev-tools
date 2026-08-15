package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.discoverhandlers;

import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.discoverhandlers.DiscoverHandlersRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.discoverhandlers.DiscoverHandlersResult;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.discoverhandlers.RouteSynthesisHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.discovery.RouteSynthesisHandlerDiscoveryResult;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.result.RouteSynthesisReportDetails;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.result.RouteSynthesisResult;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.target.RouteTargetHints;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.workspace.RouteSynthesisPathPolicy;
import java.nio.file.Path;
import java.util.List;

/** Assembles the stable discover_handlers inventory and line-validation result. */
public class DiscoverHandlersResultAssembler {

    /** Builds the handler inventory result from resolved handlers. */
    public RouteSynthesisResult assemble(
            Path projectRoot,
            DiscoverHandlersRequest request,
            List<Path> additionalRoots,
            RouteSynthesisHandlerDiscoveryResult discovered,
            List<RouteSynthesisHandler> handlers,
            boolean allLinesValidated) {
        DiscoverHandlersResult output = new DiscoverHandlersResult(
                projectRoot.toString(),
                new RouteTargetHints(
                        projectRoot.toString(), request.classHint(), null, null, "spring_http"),
                additionalRoots.stream().map(Path::toString).toList(),
                discovered.scannedJavaFiles(),
                discovered.framework(),
                discovered.controllerFqcn(),
                RouteSynthesisPathPolicy.relativePath(projectRoot, discovered.matchedTypeFile()),
                handlers,
                discovered.evidence(),
                discovered.attemptedStrategies());
        if (allLinesValidated) {
            return new RouteSynthesisResult(
                    "handler_inventory",
                    "ready",
                    null,
                    null,
                    null,
                    null,
                    output,
                    output.evidence(),
                    output.attemptedStrategies());
        }
        return RouteSynthesisResult.report(
                "handler_inventory",
                new RouteSynthesisReportDetails(
                        "partial",
                        "handler_line_validation_partial",
                        "line_validation",
                        "select_resolvable_line",
                        "Provide a reachable Probe route to validate handler lines.",
                        List.of("handlerCount=" + handlers.size()),
                        discovered.attemptedStrategies()),
                output);
    }
}
