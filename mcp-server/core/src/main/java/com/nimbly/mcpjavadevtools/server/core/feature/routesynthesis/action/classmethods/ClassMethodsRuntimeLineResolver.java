package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.classmethods;

import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.classmethods.ClassMethodsRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.discovery.JavaSourceFile;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.discovery.JavaSourceMethod;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.routing.RouteSynthesisProbeRouteResolution;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.runtime.RouteSynthesisRuntimeLineResolution;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.target.RouteTargetClass;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.target.RouteTargetMethod;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.routing.RouteSynthesisProbeRouteResolver;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.runtime.RouteSynthesisRuntimeEvidenceProvider;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.workspace.RouteSynthesisPathPolicy;
import java.nio.file.Path;
import java.util.Comparator;

/** Resolves a selected class and its methods with bounded runtime line evidence. */
public class ClassMethodsRuntimeLineResolver {

    private final RouteSynthesisProbeRouteResolver probeRouteResolver;
    private final RouteSynthesisRuntimeEvidenceProvider runtimeEvidenceProvider;

    /** Creates the class runtime-line resolver. */
    public ClassMethodsRuntimeLineResolver(
            RouteSynthesisProbeRouteResolver probeRouteResolver,
            RouteSynthesisRuntimeEvidenceProvider runtimeEvidenceProvider) {
        this.probeRouteResolver = probeRouteResolver;
        this.runtimeEvidenceProvider = runtimeEvidenceProvider;
    }

    /** Resolves the selected source class and stable method ordering. */
    public RouteTargetClass resolve(
            Path projectRoot,
            JavaSourceFile sourceFile,
            ClassMethodsRequest request) {
        RouteSynthesisProbeRouteResolution route = probeRouteResolver.resolve(
                request.probeId(), request.probeBaseUrl());
        return new RouteTargetClass(
                RouteSynthesisPathPolicy.relativePath(projectRoot, sourceFile.file()),
                sourceFile.className(),
                sourceFile.fqcn(),
                sourceFile.methods().stream()
                        .sorted(Comparator.comparingInt(JavaSourceMethod::declarationLine)
                                .thenComparing(JavaSourceMethod::name))
                        .map(method -> resolveMethod(sourceFile, method, route))
                        .toList());
    }

    private RouteTargetMethod resolveMethod(
            JavaSourceFile sourceFile,
            JavaSourceMethod method,
            RouteSynthesisProbeRouteResolution route) {
        String probeKey = sourceFile.fqcn() == null
                ? null
                : sourceFile.fqcn() + "#" + method.name();
        RouteSynthesisRuntimeLineResolution line = route.resolved()
                ? runtimeEvidenceProvider.resolveLine(
                        probeKey,
                        method.declarationLine(),
                        method.endLine(),
                        route)
                : RouteSynthesisRuntimeLineResolution.unresolved("runtime_route_unresolved");
        return new RouteTargetMethod(
                method.name(),
                method.signature(),
                method.declarationLine(),
                method.endLine(),
                method.firstExecutableLine(),
                line.status(),
                line.source(),
                probeKey);
    }
}
