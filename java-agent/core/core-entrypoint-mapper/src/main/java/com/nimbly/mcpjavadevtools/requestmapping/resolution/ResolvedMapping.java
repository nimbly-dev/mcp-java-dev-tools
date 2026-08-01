package com.nimbly.mcpjavadevtools.requestmapping.resolution;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Binary-compatible mapping value retained for the pre-api SPI. */
@Deprecated(forRemoval = false)
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

    public String getFramework() { return framework; }
    public void setFramework(String value) { framework = value; }
    public String getRequestSource() { return requestSource; }
    public void setRequestSource(String value) { requestSource = value; }
    public String getHttpMethod() { return httpMethod; }
    public void setHttpMethod(String value) { httpMethod = value; }
    public String getMaterializedPath() { return materializedPath; }
    public void setMaterializedPath(String value) { materializedPath = value; }
    public String getQueryTemplate() { return queryTemplate; }
    public void setQueryTemplate(String value) { queryTemplate = value; }
    public String getBodyTemplate() { return bodyTemplate; }
    public void setBodyTemplate(String value) { bodyTemplate = value; }
    public Path getMappingOwnerFile() { return mappingOwnerFile; }
    public void setMappingOwnerFile(Path value) { mappingOwnerFile = value; }
    public List<String> getPathParameters() { return pathParameters; }
    public void setPathParameters(List<String> value) { pathParameters = value; }
    public Map<String, Object> getExtensions() { return extensions; }
    public void setExtensions(Map<String, Object> value) { extensions = value; }
}
