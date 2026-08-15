package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.discoverhandlers;

import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.discoverhandlers.RouteSynthesisHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.routing.RouteSynthesisProbeRouteResolution;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.runtime.RouteSynthesisRuntimeLineResolution;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.runtime.RouteSynthesisRuntimeEvidenceProvider;
import java.util.List;

/** Resolves bounded runtime line evidence for discovered handlers. */
public class DiscoverHandlersRuntimeLineResolver {

    private final RouteSynthesisRuntimeEvidenceProvider runtimeEvidenceProvider;

    /** Creates the handler runtime-line resolver. */
    public DiscoverHandlersRuntimeLineResolver(
            RouteSynthesisRuntimeEvidenceProvider runtimeEvidenceProvider) {
        this.runtimeEvidenceProvider = runtimeEvidenceProvider;
    }

    /** Resolves every discovered handler against the selected Probe route. */
    public List<RouteSynthesisHandler> resolve(
            List<RouteSynthesisHandler> handlers,
            RouteSynthesisProbeRouteResolution route) {
        return handlers.stream().map(handler -> resolve(handler, route)).toList();
    }

    /** Reports whether all handler lines have been validated. */
    public boolean allValidated(List<RouteSynthesisHandler> handlers) {
        return handlers.stream().allMatch(handler -> "validated".equals(handler.lineSelectionStatus()));
    }

    private RouteSynthesisHandler resolve(
            RouteSynthesisHandler handler,
            RouteSynthesisProbeRouteResolution route) {
        if (!route.resolved()) {
            return new RouteSynthesisHandler(
                    handler.httpMethod(), handler.path(), handler.methodName(), handler.signature(),
                    handler.runtimeClassFqcn(), handler.declarationLine(), handler.endLine(), null,
                    "unresolved", "runtime_validation_not_requested", null, null);
        }
        RouteSynthesisRuntimeLineResolution line = runtimeEvidenceProvider.resolveLine(
                handler.runtimeClassFqcn() + "#" + handler.methodName(),
                handler.declarationLine(),
                handler.endLine(),
                route);
        String strictLineKey = line.line() == null ? null : handler.runtimeClassFqcn() + "#"
                + handler.methodName() + ":" + line.line();
        return new RouteSynthesisHandler(
                handler.httpMethod(),
                handler.path(),
                handler.methodName(),
                handler.signature(),
                handler.runtimeClassFqcn(),
                handler.declarationLine(),
                handler.endLine(),
                line.line(),
                line.status(),
                line.source(),
                line.resolved() ? null : line.status(),
                strictLineKey);
    }
}
