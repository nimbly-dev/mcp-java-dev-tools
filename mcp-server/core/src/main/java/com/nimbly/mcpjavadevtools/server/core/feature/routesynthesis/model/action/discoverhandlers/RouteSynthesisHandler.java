package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.discoverhandlers;

/**
 * One deterministic HTTP handler discovered from a Java controller.
 *
 * @param httpMethod resolved HTTP method
 * @param path resolved route path
 * @param methodName Java method name
 * @param signature normalized source signature
 * @param runtimeClassFqcn runtime owner class
 * @param declarationLine declaration line
 * @param endLine method end line
 * @param firstExecutableLine statically selected body line
 * @param lineSelectionStatus runtime line status
 * @param lineSelectionSource runtime evidence source
 * @param lineSelectionReasonCode unresolved line reason
 * @param strictLineKey validated Strict Line Key
 */
public record RouteSynthesisHandler(
        String httpMethod,
        String path,
        String methodName,
        String signature,
        String runtimeClassFqcn,
        int declarationLine,
        int endLine,
        Integer firstExecutableLine,
        String lineSelectionStatus,
        String lineSelectionSource,
        String lineSelectionReasonCode,
        String strictLineKey) {
}
