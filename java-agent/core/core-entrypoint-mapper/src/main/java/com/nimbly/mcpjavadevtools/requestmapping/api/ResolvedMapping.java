package com.nimbly.mcpjavadevtools.requestmapping.api;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class ResolvedMapping {
    private String framework;
    private String requestSource;
    private String httpMethod;
    private String materializedPath;
    private String queryTemplate;
    private String bodyTemplate;
    private Path mappingOwnerFile;
    private List<String> pathParameters = List.of();
    private Map<String, Object> extensions = Map.of();

    public String getFramework() {
        return framework;
    }

    public void setFramework(String framework) {
        this.framework = framework;
    }

    public String getRequestSource() {
        return requestSource;
    }

    public void setRequestSource(String requestSource) {
        this.requestSource = requestSource;
    }

    public String getHttpMethod() {
        return httpMethod;
    }

    public void setHttpMethod(String httpMethod) {
        this.httpMethod = httpMethod;
    }

    public String getMaterializedPath() {
        return materializedPath;
    }

    public void setMaterializedPath(String materializedPath) {
        this.materializedPath = materializedPath;
    }

    public String getQueryTemplate() {
        return queryTemplate;
    }

    public void setQueryTemplate(String queryTemplate) {
        this.queryTemplate = queryTemplate;
    }

    public String getBodyTemplate() {
        return bodyTemplate;
    }

    public void setBodyTemplate(String bodyTemplate) {
        this.bodyTemplate = bodyTemplate;
    }

    public Path getMappingOwnerFile() {
        return mappingOwnerFile;
    }

    public void setMappingOwnerFile(Path mappingOwnerFile) {
        this.mappingOwnerFile = mappingOwnerFile;
    }

    public List<String> getPathParameters() {
        return pathParameters;
    }

    public void setPathParameters(List<String> pathParameters) {
        this.pathParameters = pathParameters;
    }

    public Map<String, Object> getExtensions() {
        return extensions;
    }

    public void setExtensions(Map<String, Object> extensions) {
        this.extensions = extensions;
    }
}




