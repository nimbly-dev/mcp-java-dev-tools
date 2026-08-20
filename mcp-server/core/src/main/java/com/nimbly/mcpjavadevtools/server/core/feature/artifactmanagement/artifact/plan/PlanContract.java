package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactManagementSupport;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactOperationException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Suite-owned plan contract validation and summaries. */
final class PlanContract {

    void validate(JsonNode contract, String suiteType) {
        ArtifactManagementSupport.requireObject(contract, "plan_contract_invalid",
                "plan contract must be a JSON object");
        if ("regression".equals(suiteType)) {
            for (JsonNode step : contract.path("steps")) {
                String protocol = step.path("protocol").asText("").trim();
                if (!protocol.isEmpty() && !"http".equals(protocol)) {
                    throw new ArtifactOperationException("transport_protocol_mismatch",
                            "regression plan contains an unsupported step protocol");
                }
            }
        } else if ("performance".equals(suiteType)) {
            validatePerformance(contract);
        } else if (!contract.path("suiteType").isMissingNode()
                && !"security".equals(contract.path("suiteType").asText("security"))) {
            throw new ArtifactOperationException("security_plan_contract_invalid",
                    "security plan suiteType is invalid");
        }
    }

    void validatePerformanceMetadata(JsonNode metadata) {
        if (!"performance".equals(metadata.path("suiteType").asText("")
                ) || !"performance".equals(metadata.path("execution").path("intent").asText(""))) {
            throw new ArtifactOperationException("performance_plan_metadata_invalid",
                    "performance plan metadata must declare performance intent");
        }
    }

    Map<String, Object> summary(JsonNode contract, String suiteType) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("suiteType", suiteType);
        result.put("stepCount", arraySize(contract, "steps"));
        result.put("targetCount", arraySize(contract, "targets"));
        result.put("prerequisiteCount", arraySize(contract, "prerequisites"));
        if ("security".equals(suiteType)) {
            result.put("securityMode", contract.path("securityMode").asText(null));
            result.put("entrypointCount", arraySize(contract, "entrypoints"));
        } else if ("performance".equals(suiteType)) {
            result.put("entrypointCount", arraySize(contract, "entrypoints"));
            result.put("workloadProvider", contract.path("workloadProvider").path("type").asText(null));
            result.put("concurrency", contract.path("loadModel").path("concurrency").asInt(0));
        }
        return result;
    }

    private void validatePerformance(JsonNode contract) {
        if (!"performance".equals(contract.path("suiteType").asText("performance"))) {
            throw new ArtifactOperationException("performance_plan_contract_invalid",
                    "performance plan suiteType is invalid");
        }
        requireObjectField(contract, "loadModel", "performance_load_model_required");
        requireObjectField(contract, "successCriteria", "performance_success_criteria_required");
        requireObjectField(contract, "workloadProvider", "performance_workload_provider_required");
        JsonNode loadModel = contract.path("loadModel");
        if (!"concurrency".equals(loadModel.path("mode").asText("")
                ) || loadModel.path("concurrency").asInt(0) < 1
                || loadModel.path("durationSeconds").asInt(0) < 1) {
            throw new ArtifactOperationException("performance_load_model_invalid",
                    "performance loadModel must define positive concurrency and duration");
        }
        if (!contract.path("entrypoints").isArray() || contract.path("entrypoints").isEmpty()) {
            throw new ArtifactOperationException("performance_entrypoints_required",
                    "performance plan entrypoints[] is required");
        }
    }

    private static void requireObjectField(JsonNode parent, String field, String reason) {
        if (!parent.path(field).isObject()) {
            throw new ArtifactOperationException(reason, field + " must be a JSON object");
        }
    }

    private static int arraySize(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isArray() ? value.size() : 0;
    }
}
