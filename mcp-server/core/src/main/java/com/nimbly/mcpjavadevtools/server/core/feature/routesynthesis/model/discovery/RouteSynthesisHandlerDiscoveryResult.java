package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.discovery;

import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.discoverhandlers.RouteSynthesisHandler;
import java.nio.file.Path;
import java.util.List;

/** Framework discovery outcome owned by the Route Synthesis Core. */
public record RouteSynthesisHandlerDiscoveryResult(
        String status,
        String reasonCode,
        String failedStep,
        String nextAction,
        String framework,
        String controllerFqcn,
        Path matchedTypeFile,
        List<RouteSynthesisHandler> handlers,
        int scannedJavaFiles,
        List<String> evidence,
        List<String> attemptedStrategies) {

    /** Creates a successful Spring HTTP discovery outcome. */
    public static RouteSynthesisHandlerDiscoveryResult success(
            String controllerFqcn,
            Path matchedTypeFile,
            List<RouteSynthesisHandler> handlers,
            int scannedJavaFiles,
            List<String> evidence) {
        return new RouteSynthesisHandlerDiscoveryResult(
                "ok", null, null, null, "spring_http", controllerFqcn, matchedTypeFile,
                copy(handlers), scannedJavaFiles, copy(evidence),
                List.of("java_source_index_lookup", "spring_http_annotation_resolution"));
    }

    /** Creates a failed discovery outcome. */
    public static RouteSynthesisHandlerDiscoveryResult failure(
            String reasonCode,
            String failedStep,
            String nextAction,
            List<String> evidence) {
        String status = "mapper_plugin_unavailable".equals(reasonCode) ? "blocked" : "report";
        return new RouteSynthesisHandlerDiscoveryResult(
                status, reasonCode, failedStep, nextAction, null, null, null, List.of(), 0,
                copy(evidence), List.of("java_source_index_lookup", "spring_http_annotation_resolution"));
    }

    private static <T> List<T> copy(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
