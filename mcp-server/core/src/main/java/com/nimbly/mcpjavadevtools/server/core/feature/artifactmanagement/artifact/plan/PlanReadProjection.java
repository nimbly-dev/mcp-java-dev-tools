package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactManagementSupport;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactManagementSupport.Workspace;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactOperationException;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.request.ArtifactManagementRequest;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds suite-specific, transport-neutral plan read projections. */
class PlanReadProjection {

    private final ArtifactManagementSupport support;
    private final PlanContract contract;

    PlanReadProjection(ArtifactManagementSupport support, PlanContract contract) {
        this.support = support;
        this.contract = contract;
    }

    Map<String, Object> read(
            ArtifactManagementRequest request,
            String suiteType,
            String projectName,
            String planName,
            Workspace workspace) {
        Path plan = workspace.paths().resolve(
                ".mcpjvm", projectName, "plans", suiteType, planName);
        if ("regression".equals(suiteType)) {
            return regression(request, projectName, planName, plan, workspace);
        }
        if ("security".equals(suiteType)) {
            return security(request, projectName, planName, plan, workspace);
        }
        return performance(projectName, planName, plan, workspace);
    }

    private Map<String, Object> regression(
            ArtifactManagementRequest request,
            String projectName,
            String planName,
            Path plan,
            Workspace workspace) {
        List<String> selectors = selectors(request);
        JsonNode metadata = readWhen(plan, "metadata.json", workspace,
                selectors.isEmpty() || selected(selectors, "metadata", "summary"));
        JsonNode persistedContract = readWhen(plan, "contract.json", workspace,
                selectors.isEmpty() || selected(selectors,
                        "contract", "summary", "targets", "prerequisites", "steps"));
        Map<String, Object> output = base(projectName, planName);
        if (selectors.isEmpty()) {
            output.put("summary", regressionSummary(metadata, persistedContract));
            return output;
        }
        addRegressionSections(output, request, selectors, metadata, persistedContract);
        addArtifact(output, selectors, metadata, persistedContract, plan, workspace);
        return output;
    }

    private void addRegressionSections(
            Map<String, Object> output,
            ArtifactManagementRequest request,
            List<String> selectors,
            JsonNode metadata,
            JsonNode persistedContract) {
        if (selectors.contains("summary")) {
            output.put("summary", regressionSummary(metadata, persistedContract));
        }
        if (selectors.contains("targets")) {
            output.put("targets", arrayValue(persistedContract, "targets"));
        }
        if (selectors.contains("prerequisites")) {
            output.put("prerequisites", window(request, persistedContract, "prerequisites"));
        }
        if (selectors.contains("steps")) {
            output.put("steps", window(request, persistedContract, "steps"));
        }
    }

    private Map<String, Object> security(
            ArtifactManagementRequest request,
            String projectName,
            String planName,
            Path plan,
            Workspace workspace) {
        List<String> selectors = selectors(request);
        boolean includeAll = selectors.isEmpty();
        JsonNode metadata = readWhen(plan, "metadata.json", workspace,
                includeAll || selected(selectors, "metadata", "summary"));
        JsonNode persistedContract = readWhen(plan, "contract.json", workspace,
                includeAll || selected(selectors, "contract", "summary"));
        Map<String, Object> output = base(projectName, planName);
        if (includeAll || selectors.contains("summary")) {
            output.put("summary", securitySummary(persistedContract));
        }
        addArtifact(output, includeAll ? List.of("metadata", "contract", "plan") : selectors,
                metadata, persistedContract, plan, workspace);
        return output;
    }

    private Map<String, Object> performance(
            String projectName, String planName, Path plan, Workspace workspace) {
        JsonNode metadata = read(plan, "metadata.json", workspace);
        JsonNode persistedContract = read(plan, "contract.json", workspace);
        Map<String, Object> artifact = new LinkedHashMap<>();
        artifact.put("metadata", jsonValue(metadata));
        artifact.put("contract", jsonValue(persistedContract));
        artifact.put("plan", readText(plan, "plan.md", workspace));
        Map<String, Object> output = base(projectName, planName);
        output.put("artifact", artifact);
        output.put("summary", contract.summary(persistedContract, "performance"));
        return output;
    }

    private void addArtifact(
            Map<String, Object> output,
            List<String> selectors,
            JsonNode metadata,
            JsonNode persistedContract,
            Path plan,
            Workspace workspace) {
        Map<String, Object> artifact = new LinkedHashMap<>();
        if (selectors.contains("metadata")) {
            artifact.put("metadata", jsonValue(metadata));
        }
        if (selectors.contains("contract")) {
            artifact.put("contract", jsonValue(persistedContract));
        }
        if (selectors.contains("plan")) {
            artifact.put("plan", readText(plan, "plan.md", workspace));
        }
        if (!artifact.isEmpty()) {
            output.put("artifact", artifact);
        }
    }

    private Map<String, Object> regressionSummary(JsonNode metadata, JsonNode persistedContract) {
        Map<String, Object> summary = new LinkedHashMap<>();
        JsonNode intent = metadata.path("execution").get("intent");
        summary.put("intent", intent == null ? null : jsonValue(intent));
        summary.put("stepCount", arraySize(persistedContract, "steps"));
        summary.put("targetCount", arraySize(persistedContract, "targets"));
        summary.put("prerequisiteCount", arraySize(persistedContract, "prerequisites"));
        return summary;
    }

    private Map<String, Object> securitySummary(JsonNode persistedContract) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("suiteType", nullableValue(persistedContract, "suiteType"));
        summary.put("securityMode", nullableValue(persistedContract, "securityMode"));
        summary.put("entrypointCount", arraySize(persistedContract, "entrypoints"));
        summary.put("authenticationProfileCount",
                arraySize(persistedContract, "authenticationProfiles"));
        summary.put("attackProfileCount", arraySize(persistedContract, "attackProfiles"));
        return summary;
    }

    private Map<String, Object> window(
            ArtifactManagementRequest request, JsonNode persistedContract, String section) {
        JsonNode query = request.input().path("query").path(section);
        if (!query.isObject() || !isInteger(query.path("offset"))
                || query.path("offset").intValue() < 0 || !isInteger(query.path("limit"))
                || query.path("limit").intValue() <= 0) {
            throw new ArtifactOperationException(
                    "window_query_required", section + " requires a non-negative offset and positive limit");
        }
        List<Object> values = arrayValue(persistedContract, section);
        int offset = Math.min(query.path("offset").intValue(), values.size());
        int limit = query.path("limit").intValue();
        int end = (int) Math.min((long) offset + limit, values.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("offset", offset);
        result.put("limit", limit);
        result.put("returned", end - offset);
        result.put("total", values.size());
        result.put("items", values.subList(offset, end));
        return result;
    }

    private List<String> selectors(ArtifactManagementRequest request) {
        List<String> values = new ArrayList<>();
        JsonNode select = request.input().path("query").path("select");
        if (select.isArray()) {
            select.forEach(value -> {
                if (value.isTextual() && !value.asText().trim().isEmpty()) {
                    values.add(value.asText());
                }
            });
        }
        return values;
    }

    private JsonNode readWhen(Path plan, String file, Workspace workspace, boolean required) {
        return required ? read(plan, file, workspace) : null;
    }

    private JsonNode read(Path plan, String file, Workspace workspace) {
        return support.jsonStore().read(workspace.paths().check(plan.resolve(file)));
    }

    private String readText(Path plan, String file, Workspace workspace) {
        return support.jsonStore().readText(workspace.paths().check(plan.resolve(file)));
    }

    @SuppressWarnings("unchecked")
    private List<Object> arrayValue(JsonNode parent, String field) {
        JsonNode value = parent == null ? null : parent.get(field);
        if (value == null || !value.isArray()) {
            return List.of();
        }
        return support.mapper().convertValue(value, List.class);
    }

    private Object nullableValue(JsonNode parent, String field) {
        JsonNode value = parent == null ? null : parent.get(field);
        return value == null ? null : jsonValue(value);
    }

    private Object jsonValue(JsonNode value) {
        return value == null ? null : support.mapper().convertValue(value, Object.class);
    }

    private static Map<String, Object> base(String projectName, String planName) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projectName", projectName);
        result.put("planName", planName);
        return result;
    }

    private static boolean selected(List<String> selectors, String... values) {
        return java.util.Arrays.stream(values).anyMatch(selectors::contains);
    }

    private static int arraySize(JsonNode parent, String field) {
        JsonNode value = parent == null ? null : parent.get(field);
        return value != null && value.isArray() ? value.size() : 0;
    }

    private static boolean isInteger(JsonNode value) {
        return value.isNumber() && value.canConvertToInt()
                && value.decimalValue().stripTrailingZeros().scale() <= 0;
    }
}
