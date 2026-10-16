package com.nimbly.mcpjavadevtools.server.core.feature.suite.security.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.knowledge.SecurityKnowledgeCatalog;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.model.execution.SecurityCaseResult;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.model.execution.SecurityGeneratedRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.model.result.SecuritySuiteResult;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportResult;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Executes the finite baseline, custom and reviewed-knowledge black-box matrix. */
class SecurityBlackboxExecution {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final SecurityKnowledgeCatalog knowledge;
    private final SecurityRequestExecution requests;

    SecurityBlackboxExecution(SecurityKnowledgeCatalog knowledge, SecurityRequestExecution requests) {
        this.knowledge = knowledge;
        this.requests = requests;
    }

    SecuritySuiteResult runBlackbox(
            JsonNode contract, URI baseUrl, SecurityKnowledgeCatalog.Selection selected, SecurityRequestContext credentials) {
        List<Map<String, Object>> cases = new ArrayList<>();
        List<Map<String, Object>> findings = new ArrayList<>();
        List<SecurityKnowledgeCatalog.GeneratedCase> generated = contract.has("customCases")
                ? List.of() : knowledge.generatedCases(selected);
        int plannedCount = contract.path("entrypoints").size() * contract.path("authenticationProfiles").size()
                * (generated.size() + 1) + contract.path("customCases").size();
        for (JsonNode entrypoint : contract.path("entrypoints")) {
            for (JsonNode profile : contract.path("authenticationProfiles")) {
                Map<String, Object> outcome = baseline(contract, baseUrl, entrypoint, profile, credentials);
                cases.add(SecurityExecutionResults.caseForProfile(outcome, profile));
                if ("blocked".equals(outcome.get("outcome"))) {
                    return SecurityExecutionResults.incomplete(selected, cases, String.valueOf(outcome.get("reasonCode")), plannedCount, findings);
                }
            }
        }
        for (JsonNode attack : contract.path("customCases")) {
            SecurityCaseResult outcome = executeBlackboxCase(contract, baseUrl, attack, credentials);
            cases.add(outcome.coverage());
            findings.addAll(outcome.findings());
            if ("blocked".equals(outcome.coverage().get("outcome"))) {
                return SecurityExecutionResults.incomplete(selected, cases, String.valueOf(outcome.coverage().get("reasonCode")), plannedCount, findings);
            }
        }
        for (SecurityKnowledgeCatalog.GeneratedCase template : generated) {
            for (JsonNode entrypoint : contract.path("entrypoints")) {
                for (JsonNode profile : contract.path("authenticationProfiles")) {
                    SecurityCaseResult outcome = executeGeneratedCase(contract, entrypoint, profile, baseUrl, template, credentials);
                    cases.add(SecurityExecutionResults.caseForProfile(outcome.coverage(), profile));
                    findings.addAll(outcome.findings());
                    if ("blocked".equals(outcome.coverage().get("outcome"))) {
                        return SecurityExecutionResults.incomplete(selected, cases,
                                String.valueOf(outcome.coverage().get("reasonCode")), plannedCount, findings);
                    }
                }
            }
        }
        return SecuritySuiteResult.completed(Map.of("runStatus", SecurityExecutionResults.verdict(contract, findings), "securityMode", "blackbox",
                "knowledgeSnapshot", Map.of("packs", selected.snapshot().packs(), "digest", selected.snapshot().digest()),
                "coverage", Map.of("plannedCount", cases.size(), "completedCount", cases.size(), "complete", true),
                "cases", List.copyOf(cases), "findings", List.copyOf(findings)));
    }

    private SecurityCaseResult executeGeneratedCase(
            JsonNode contract,
            JsonNode entrypoint,
            JsonNode profile,
            URI baseUrl,
            SecurityKnowledgeCatalog.GeneratedCase template,
            SecurityRequestContext credentials) {
        JsonNode transportDefinition = SecurityRequestExecution.transportDefinition(entrypoint);
        URI requestUri = SecurityRequestExecution.uri(baseUrl, transportDefinition.path("path").asText());
        if (requestUri == null || !"http".equals(transportDefinition.path("type").asText())) {
            return SecurityExecutionResults.generatedResult(entrypoint, template, "not_applicable", "security_generated_entrypoint_not_supported", null);
        }
        if (!applicable(contract, profile, template)) {
            return SecurityExecutionResults.generatedResult(entrypoint, template, "not_applicable", "security_generated_rule_not_applicable", null);
        }
        SecurityGeneratedRequest mutation = generatedRequest(entrypoint, requestUri, contract, template, profile);
        if (mutation == null) {
            return SecurityExecutionResults.generatedResult(entrypoint, template, "not_applicable", "security_generated_mutation_target_missing", null);
        }
        ExecuteTransportResult baseline = requests.executeRequest(requestUri, entrypoint, entrypoint.path("baseline"), profile, credentials);
        if (!SecurityRequestExecution.allowedResponse(baseline)) {
            return SecurityExecutionResults.generatedResult(entrypoint, template, "blocked",
                    credentials.budget().reasonOr("security_generated_baseline_unexpected"), baseline.statusCode());
        }
        ExecuteTransportResult response = requests.executeRequest(
                mutation.uri(), entrypoint, mutation.request(), profile, credentials, !mutation.removeCredential());
        if (!SecurityRequestExecution.transported(response)) {
            return SecurityExecutionResults.generatedResult(entrypoint, template, "blocked",
                    credentials.budget().reasonOr("security_generated_transport_blocked"), response.statusCode());
        }
        if (expectedDeny(response, template)) {
            return SecurityExecutionResults.withHttpEvidence(SecurityExecutionResults.generatedResult(entrypoint, template, "passed",
                    "security_generated_mutation_denied", response.statusCode()),
                    SecurityExecutionResults.generatedCaseId(entrypoint, template, profile), baseline, response);
        }
        if (SecurityRequestExecution.allowedResponse(response)) {
            return SecurityExecutionResults.generatedFinding(entrypoint, template, baseline, response, profile);
        }
        return SecurityExecutionResults.generatedResult(entrypoint, template, "blocked", "security_generated_attack_unexpected", response.statusCode());
    }

    private static boolean applicable(JsonNode contract, JsonNode profile, SecurityKnowledgeCatalog.GeneratedCase template) {
        if (template.requiredAuthentication() && "anonymous".equals(profile.path("kind").asText())) {
            return false;
        }
        JsonNode fixtures = contract.path("targetBoundary").path("fixtureContext");
        return template.requiredFixtureContextKeys().stream().allMatch(key -> fixtures.hasNonNull(key));
    }

    private static SecurityGeneratedRequest generatedRequest(
            JsonNode entrypoint, URI uri, JsonNode contract, SecurityKnowledgeCatalog.GeneratedCase template, JsonNode profile) {
        ObjectNode request = entrypoint.path("baseline").isObject()
                ? ((ObjectNode) entrypoint.path("baseline")).deepCopy() : MAPPER.createObjectNode();
        String payload = payload(contract, template);
        if (payload == null && !template.removeCredential()) {
            return null;
        }
        return switch (template.mutationBoundary()) {
            case "query_parameter" -> queryMutation(uri, request, payload);
            case "header" -> headerMutation(uri, request, template.removeCredential(), profile.path("credentialRef").asText());
            case "path_parameter" -> pathMutation(uri, request, payload);
            case "request_body" -> bodyMutation(uri, request, payload);
            default -> null;
        };
    }

    private static String payload(JsonNode contract, SecurityKnowledgeCatalog.GeneratedCase template) {
        String raw = template.payloadTemplate();
        if (!raw.startsWith("${fixture.") || !raw.endsWith("}")) {
            return raw.isBlank() ? null : raw;
        }
        String key = raw.substring("${fixture.".length(), raw.length() - 1);
        JsonNode value = contract.path("targetBoundary").path("fixtureContext").path(key);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    private static SecurityGeneratedRequest queryMutation(URI uri, ObjectNode request, String payload) {
        ObjectNode query = request.path("query").isObject() ? (ObjectNode) request.path("query") : request.putObject("query");
        String name = query.fieldNames().hasNext() ? query.fieldNames().next() : null;
        if (name == null) {
            return null;
        }
        query.put(name, payload);
        return new SecurityGeneratedRequest(uri, request, false);
    }

    private static SecurityGeneratedRequest headerMutation(URI uri, ObjectNode request, boolean removeCredential, String credentialRef) {
        if (!removeCredential) {
            return null;
        }
        ObjectNode headers = request.path("headers").isObject() ? (ObjectNode) request.path("headers") : request.putObject("headers");
        List<String> credentialHeaders = new ArrayList<>();
        headers.fields().forEachRemaining(header -> {
            if ("Authorization".equalsIgnoreCase(header.getKey()) || "X-API-Key".equalsIgnoreCase(header.getKey())
                    || "Cookie".equalsIgnoreCase(header.getKey())
                    || !credentialRef.isBlank() && header.getValue().asText().contains("${" + credentialRef + "}")) {
                credentialHeaders.add(header.getKey());
            }
        });
        headers.remove(credentialHeaders);
        return new SecurityGeneratedRequest(uri, request, true);
    }

    private static SecurityGeneratedRequest pathMutation(URI uri, ObjectNode request, String payload) {
        ObjectNode parameters = request.path("pathParameters").isObject()
                ? (ObjectNode) request.path("pathParameters") : null;
        if (parameters == null || !parameters.fieldNames().hasNext()) {
            return null;
        }
        parameters.put(parameters.fieldNames().next(), payload);
        return new SecurityGeneratedRequest(uri, request, false);
    }

    private static SecurityGeneratedRequest bodyMutation(URI uri, ObjectNode request, String payload) {
        if (!request.path("body").isObject()) {
            return null;
        }
        request.set("body", MAPPER.getNodeFactory().textNode(payload));
        return new SecurityGeneratedRequest(uri, request, false);
    }

    private SecurityCaseResult executeBlackboxCase(JsonNode contract, URI baseUrl, JsonNode attack, SecurityRequestContext credentials) {
        JsonNode entrypoint = SecurityContractPreflight.entrypoint(contract, attack.path("entrypointRef").asText());
        JsonNode profile = SecurityContractPreflight.profile(contract, attack.path("authenticationProfileRef").asText());
        if (entrypoint == null || profile == null) {
            return SecurityExecutionResults.sidecarBlocked(attack, "security_blackbox_reference_missing");
        }
        JsonNode transportDefinition = SecurityRequestExecution.transportDefinition(entrypoint);
        if (!"http".equals(transportDefinition.path("type").asText())) {
            return SecurityExecutionResults.sidecarNotApplicable(attack, "security_blackbox_entrypoint_not_supported");
        }
        URI requestUri = SecurityRequestExecution.uri(baseUrl, transportDefinition.path("path").asText());
        if (requestUri == null || !SecurityRequestExecution.allowed(contract.path("targetBoundary"), requestUri)) {
            return SecurityExecutionResults.sidecarBlocked(attack, "security_target_boundary_violation");
        }
        return executeBlackboxRequests(attack, entrypoint, requestUri, profile, credentials);
    }

    private SecurityCaseResult executeBlackboxRequests(
            JsonNode attack, JsonNode entrypoint, URI requestUri, JsonNode profile, SecurityRequestContext credentials) {
        ExecuteTransportResult baseline = requests.executeRequest(requestUri, entrypoint, attack.path("baseline"), profile, credentials);
        if (!SecurityRequestExecution.matches(baseline, attack.path("baseline").path("expect"))) {
            return SecurityExecutionResults.sidecarBlocked(attack, "security_blackbox_baseline_unexpected");
        }
        ExecuteTransportResult attackResult = requests.executeRequest(requestUri, entrypoint, attack.path("attack"), profile, credentials);
        if (!SecurityRequestExecution.transported(attackResult)) {
            return SecurityExecutionResults.sidecarBlocked(attack, "security_blackbox_transport_blocked");
        }
        boolean bypass = "deny".equals(attack.path("attack").path("expect").path("outcome").asText())
                && SecurityRequestExecution.allowedResponse(attackResult);
        SecurityCaseResult result;
        if (bypass) {
            result = SecurityExecutionResults.sidecarFinding(attack, "external", false);
        } else if (SecurityRequestExecution.matches(attackResult, attack.path("attack").path("expect"))) {
            result = SecurityExecutionResults.sidecarPassed(attack);
        } else {
            result = SecurityExecutionResults.sidecarBlocked(attack, "security_blackbox_attack_unexpected");
        }
        return SecurityExecutionResults.withHttpEvidence(result, attack, baseline, attackResult);
    }

    private Map<String, Object> baseline(
            JsonNode contract, URI baseUrl, JsonNode entrypoint, JsonNode profile, SecurityRequestContext credentials) {
        JsonNode transportDefinition = SecurityRequestExecution.transportDefinition(entrypoint);
        if (!"http".equals(transportDefinition.path("type").asText())) {
            return SecurityExecutionResults.caseResult(entrypoint, "not_applicable", "security_blackbox_entrypoint_not_supported", null);
        }
        URI requestUri = SecurityRequestExecution.uri(baseUrl, transportDefinition.path("path").asText());
        if (requestUri == null || !SecurityRequestExecution.allowed(contract.path("targetBoundary"), requestUri)) {
            return SecurityExecutionResults.caseResult(entrypoint, "blocked", "security_target_boundary_violation", null);
        }
        ExecuteTransportResult result = requests.executeRequest(
                requestUri, entrypoint, entrypoint.path("baseline"), profile, credentials);
        if (!SecurityRequestExecution.transported(result)) {
            return SecurityExecutionResults.caseResult(entrypoint, "blocked", result.reasonCode(), result.statusCode());
        }
        return SecurityRequestExecution.allowedResponse(result)
                ? SecurityExecutionResults.caseResult(entrypoint, "passed", "ok", result.statusCode())
                : SecurityExecutionResults.caseResult(entrypoint, "blocked", "security_blackbox_baseline_unexpected", result.statusCode());
    }

    private static boolean expectedDeny(
            ExecuteTransportResult response, SecurityKnowledgeCatalog.GeneratedCase template) {
        return template.expectedDenyStatusCodes().contains(response.statusCode()) && SecurityRequestExecution.deniedResponse(response);
    }
}
