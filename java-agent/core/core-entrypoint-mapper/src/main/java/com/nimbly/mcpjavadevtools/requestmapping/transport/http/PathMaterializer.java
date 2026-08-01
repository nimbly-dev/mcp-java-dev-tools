package com.nimbly.mcpjavadevtools.requestmapping.transport.http;

import com.nimbly.mcpjavadevtools.requestmapping.api.MethodContext;
import com.nimbly.mcpjavadevtools.requestmapping.api.ResolvedParameter;
import com.nimbly.mcpjavadevtools.requestmapping.api.TypeDescriptor;
import com.nimbly.mcpjavadevtools.requestmapping.transport.TransportMaterializer;
import java.util.List;

/** Compatibility adapter delegating to the canonical API materializer. */
@Deprecated(forRemoval = false)
public final class PathMaterializer implements TransportMaterializer {
    private final com.nimbly.mcpjavadevtools.requestmapping.api.PathMaterializer delegate =
            new com.nimbly.mcpjavadevtools.requestmapping.api.PathMaterializer();

    public com.nimbly.mcpjavadevtools.requestmapping.resolution.ResolvedMapping materialize(
            String framework, String httpMethod, String classPath, String methodPath,
            com.nimbly.mcpjavadevtools.requestmapping.ast.MethodContext context) {
        return materialize(framework, httpMethod, classPath, methodPath, context, List.of());
    }

    @Override
    public com.nimbly.mcpjavadevtools.requestmapping.resolution.ResolvedMapping materialize(
            String framework, String httpMethod, String classPath, String methodPath,
            com.nimbly.mcpjavadevtools.requestmapping.ast.MethodContext context,
            List<com.nimbly.mcpjavadevtools.requestmapping.ast.ResolvedParameter> parameters) {
        var mapping = delegate.materialize(framework, httpMethod, classPath, methodPath,
                new MethodContext(
                        toApiDescriptor(context.owner()), context.method(), toApiDescriptor(context.originOwner())),
                parameters.stream()
                        .map(parameter -> new ResolvedParameter(
                                parameter.getKind(), parameter.getName(), parameter.getType()))
                        .toList());
        com.nimbly.mcpjavadevtools.requestmapping.resolution.ResolvedMapping legacy =
                new com.nimbly.mcpjavadevtools.requestmapping.resolution.ResolvedMapping();
        legacy.setFramework(mapping.getFramework());
        legacy.setRequestSource(mapping.getRequestSource());
        legacy.setHttpMethod(mapping.getHttpMethod());
        legacy.setMaterializedPath(mapping.getMaterializedPath());
        legacy.setQueryTemplate(mapping.getQueryTemplate());
        legacy.setBodyTemplate(mapping.getBodyTemplate());
        legacy.setMappingOwnerFile(mapping.getMappingOwnerFile());
        legacy.setPathParameters(mapping.getPathParameters());
        legacy.setExtensions(mapping.getExtensions());
        return legacy;
    }

    public static String joinPaths(String classPath, String methodPath) {
        return com.nimbly.mcpjavadevtools.requestmapping.api.PathMaterializer.joinPaths(classPath, methodPath);
    }

    public static String normalizePath(String raw) {
        return com.nimbly.mcpjavadevtools.requestmapping.api.PathMaterializer.normalizePath(raw);
    }

    private static TypeDescriptor toApiDescriptor(
            com.nimbly.mcpjavadevtools.requestmapping.ast.TypeDescriptor descriptor) {
        return new TypeDescriptor(
                descriptor.getFileAbs(), descriptor.getTypeDeclaration(), descriptor.getPackageName(),
                descriptor.getSimpleName(), descriptor.getFqcn(), descriptor.getImports());
    }
}
