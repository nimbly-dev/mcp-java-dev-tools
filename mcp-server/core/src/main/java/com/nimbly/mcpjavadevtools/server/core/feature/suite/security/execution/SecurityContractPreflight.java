package com.nimbly.mcpjavadevtools.server.core.feature.suite.security.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.model.execution.SecurityPlanValidation;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/** Validates the finite Security contract and its declared references before execution. */
class SecurityContractPreflight {

    private SecurityContractPreflight() {
    }

    static JsonNode entrypoint(JsonNode contract, String id) {
        for (JsonNode value : contract.path("entrypoints")) {
            if (id.equals(value.path("id").asText())) {
                return value;
            }
        }
        return null;
    }

    static JsonNode profile(JsonNode contract, String id) {
        for (JsonNode value : contract.path("authenticationProfiles")) {
            if (id.equals(value.path("id").asText())) {
                return value;
            }
        }
        return null;
    }

    static List<String> packRefs(JsonNode contract) {
        List<String> values = new ArrayList<>();
        contract.path("securityKnowledge").path("packRefs").forEach(value -> values.add(value.asText()));
        return List.copyOf(values);
    }

    static SecurityPlanValidation from(JsonNode contract) {
        if (!contract.isObject() || !"security".equals(contract.path("suiteType").asText())) {
            return invalid("security_plan_input_invalid", "provide a Security contract with suiteType=security");
        }
        String mode = contract.path("securityMode").asText();
        if (!"blackbox".equals(mode) && !"sidecar_assisted".equals(mode)) {
            return invalid("security_mode_invalid", "select blackbox or sidecar_assisted mode");
        }
        if (!validIdentifiers(contract.path("entrypoints")) || contract.path("entrypoints").isEmpty()) {
            return invalid("security_contract_entrypoints_invalid", "provide at least one declared entrypoint");
        }
        if (!validAuthentication(contract.path("authenticationProfiles"))) {
            return invalid("security_contract_authentication_profiles_invalid", "provide authenticationProfiles");
        }
        if (contract.has("customCases") && !validIdentifiers(contract.path("customCases"))) {
            return invalid("security_contract_attack_profiles_invalid", "provide uniquely identified customCases");
        }
        SecurityPlanValidation policies = policies(contract);
        if (policies != null) {
            return policies;
        }
        URI baseUrl = baseUrl(contract.path("targetBoundary"));
        return baseUrl == null ? invalid("security_contract_target_boundary_invalid", "provide a bounded local-ci baseUrl")
                : new SecurityPlanValidation(null, null, mode, baseUrl);
    }

    private static SecurityPlanValidation policies(JsonNode contract) {
        JsonNode safety = contract.path("safetyPolicy");
        if (!safety.isMissingNode() && !validSafety(safety)) {
            return invalid("security_contract_safety_policy_invalid", "provide positive safe execution bounds");
        }
        JsonNode verdict = contract.path("verdictPolicy");
        if (!verdict.isMissingNode() && !validVerdict(verdict)) {
            return invalid("security_contract_verdict_policy_invalid", "provide the bounded severity verdict policy");
        }
        JsonNode exhaustive = contract.path("exhaustiveness");
        if (!exhaustive.isMissingNode() && (!"finite_matrix".equals(exhaustive.path("mode").asText())
                || !exhaustive.path("requireAllCases").asBoolean(false)
                || !"blocked".equals(exhaustive.path("onIncomplete").asText()))) {
            return invalid("security_contract_exhaustiveness_invalid", "require the complete finite matrix");
        }
        return null;
    }

    private static boolean validIdentifiers(JsonNode values) {
        if (!values.isArray()) {
            return false;
        }
        java.util.Set<String> identifiers = new java.util.HashSet<>();
        for (JsonNode value : values) {
            JsonNode id = value.path("id");
            if (!id.isTextual() || id.asText().isBlank() || !identifiers.add(id.asText())) {
                return false;
            }
        }
        return true;
    }

    private static boolean validAuthentication(JsonNode profiles) {
        if (!validIdentifiers(profiles) || profiles.isEmpty()) {
            return false;
        }
        for (JsonNode profile : profiles) {
            if (!List.of("anonymous", "bearer", "basic", "api_key", "custom").contains(profile.path("kind").asText())) {
                return false;
            }
        }
        return true;
    }

    private static boolean validSafety(JsonNode safety) {
        return safety.isObject() && positive(safety, "maxConcurrency")
                && positive(safety, "maxRequestsPerSecond") && positive(safety, "maxDurationMs")
                && "forbidden".equals(safety.path("destructivePayloads").asText())
                && "test-tenant-only".equals(safety.path("stateMutation").asText())
                && safety.path("cleanupRequired").asBoolean(false);
    }

    private static boolean validVerdict(JsonNode verdict) {
        return verdict.isObject() && verdict.path("failOnSeverity").isArray()
                && validSeverities(verdict.path("failOnSeverity"))
                && verdict.path("requireExhaustiveCompletion").asBoolean(false)
                && "fail".equals(verdict.path("blockedCountsAs").asText());
    }

    private static boolean positive(JsonNode policy, String field) {
        return policy.path(field).isIntegralNumber() && policy.path(field).canConvertToInt()
                && policy.path(field).asInt() > 0;
    }

    private static boolean validSeverities(JsonNode severities) {
        for (JsonNode severity : severities) {
            if (!severity.isTextual() || !List.of("critical", "high", "medium", "low", "info").contains(severity.asText())) {
                return false;
            }
        }
        return true;
    }

    static SecurityPlanValidation sidecar(JsonNode contract) {
        JsonNode attacks = contract.path("attackProfiles");
        if (!validIdentifiers(attacks) || attacks.isEmpty()) {
            return invalid("security_contract_attack_profiles_invalid", "provide non-empty sidecar attackProfiles");
        }
        JsonNode targets = contract.path("runtimeTargets");
        if (!targets.isArray() || targets.isEmpty()) {
            return invalid("security_contract_sidecar_runtime_target_required", "provide non-empty runtimeTargets");
        }
        if (!validIdentifiers(targets)) {
            return invalid("security_contract_runtime_targets_invalid", "provide uniquely identified runtimeTargets");
        }
        for (JsonNode target : targets) {
            boolean hasProbe = target.path("probeId").isTextual() || target.path("probeBaseUrl").isTextual();
            if (!hasProbe || !target.path("strictLineKey").isTextual()
                    || entrypoint(contract, target.path("entrypointRef").asText()) == null) {
                return invalid("security_contract_runtime_targets_invalid",
                        "provide probeId or probeBaseUrl, strictLineKey and a declared entrypointRef for every runtimeTarget");
            }
        }
        return new SecurityPlanValidation(null, null, "sidecar_assisted", null);
    }

    private static URI baseUrl(JsonNode boundary) {
        try {
            URI value = URI.create(boundary.path("baseUrl").asText());
            return "local-ci".equals(boundary.path("environment").asText()) && value.getHost() != null
                    && value.getUserInfo() == null && List.of("http", "https").contains(value.getScheme()) ? value : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static SecurityPlanValidation invalid(String reasonCode, String nextAction) {
        return new SecurityPlanValidation(reasonCode, nextAction, null, null);
    }
}
