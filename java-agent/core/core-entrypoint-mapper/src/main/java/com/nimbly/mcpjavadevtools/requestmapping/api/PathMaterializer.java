package com.nimbly.mcpjavadevtools.requestmapping.api;

import com.nimbly.mcpjavadevtools.requestmapping.transport.http.ParameterTemplateBuilder;

import java.util.ArrayList;
import java.util.List;

public final class PathMaterializer implements TransportMaterializer {
    public ResolvedMapping materialize(
            String framework,
            String httpMethod,
            String classPath,
            String methodPath,
            MethodContext context
    ) {
        return materialize(framework, httpMethod, classPath, methodPath, context, List.of());
    }

    @Override
    public ResolvedMapping materialize(
            String framework,
            String httpMethod,
            String classPath,
            String methodPath,
            MethodContext context,
            List<ResolvedParameter> parameters
    ) {
        String resolvedPath = joinPaths(classPath, methodPath);
        List<String> queryParts = new ArrayList<>();
        List<String> pathParameters = new ArrayList<>();
        String bodyTemplate = null;

        for (ResolvedParameter param : parameters) {
            if (param.getKind().equals("query")) {
                queryParts.add(param.getName() + "="
                        + ParameterTemplateBuilder.sampleValueForParameter(param.getName(), param.getType()));
            } else if (param.getKind().equals("path")) {
                String value = ParameterTemplateBuilder.samplePathValueForParameter(
                        param.getName(),
                        param.getType()
                );
                resolvedPath = resolvedPath
                        .replace("{" + param.getName() + "}", value)
                        .replace(":" + param.getName(), value);
                pathParameters.add(param.getName() + "=" + value);
            } else if (param.getKind().equals("body")) {
                bodyTemplate = ParameterTemplateBuilder.sampleBodyForParameter(param.getName(), param.getType());
            }
        }

        ResolvedMapping mapping = new ResolvedMapping();
        mapping.setFramework(framework);
        mapping.setRequestSource(framework);
        mapping.setHttpMethod(httpMethod);
        mapping.setMaterializedPath(resolvedPath);
        mapping.setQueryTemplate(String.join("&", queryParts));
        mapping.setBodyTemplate(bodyTemplate);
        mapping.setMappingOwnerFile(context.owner().getFileAbs());
        mapping.setPathParameters(pathParameters);
        return mapping;
    }

    public static String joinPaths(String classPath, String methodPath) {
        String base = normalizePath(classPath);
        String sub = methodPath == null ? "" : methodPath.trim();
        if (sub.isBlank()) {
            return base;
        }
        String normalizedSub = sub.startsWith("/") ? sub : "/" + sub;
        String joined = (base.equals("/") ? "" : base) + normalizedSub;
        return joined.isBlank() ? "/" : joined;
    }

    public static String normalizePath(String raw) {
        if (raw == null || raw.isBlank()) {
            return "/";
        }
        String trimmed = raw.trim();
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    }
}




