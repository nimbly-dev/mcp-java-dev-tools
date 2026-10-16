package com.nimbly.mcpjavadevtools.server.core.feature.suite.security.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.knowledge.SecurityKnowledgeCatalog;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.model.execution.SecurityCaseResult;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.model.execution.SecurityRuntimeEvidence;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.model.result.SecuritySuiteResult;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Assembles sanitized findings, proof, coverage and deterministic Security results. */
class SecurityExecutionResults {

    private SecurityExecutionResults() {
    }

    static String generatedCaseId(JsonNode entrypoint, SecurityKnowledgeCatalog.GeneratedCase template, JsonNode profile) {
        return template.ruleId() + "-" + entrypoint.path("id").asText() + "-" + profile.path("id").asText();
    }

    static Map<String, Object> caseForProfile(Map<String, Object> outcome, JsonNode profile) {
        Map<String, Object> row = new LinkedHashMap<>(outcome);
        String prefix = String.valueOf(row.getOrDefault("ruleId", "baseline"));
        row.put("caseId", prefix + "-" + row.get("entrypointRef") + "-" + profile.path("id").asText());
        row.put("authenticationProfileRef", profile.path("id").asText());
        return Map.copyOf(row);
    }

    static SecurityCaseResult generatedResult(
            JsonNode entrypoint, SecurityKnowledgeCatalog.GeneratedCase template, String outcome, String reasonCode, Integer statusCode) {
        Map<String, Object> coverage = new LinkedHashMap<>(caseResult(entrypoint, outcome, reasonCode, statusCode));
        coverage.put("knowledgePackRef", template.packRef());
        coverage.put("ruleId", template.ruleId());
        coverage.put("mutationBoundary", template.mutationBoundary());
        return new SecurityCaseResult(Map.copyOf(coverage), List.of());
    }

    static SecurityCaseResult generatedFinding(
            JsonNode entrypoint,
            SecurityKnowledgeCatalog.GeneratedCase template,
            ExecuteTransportResult baseline,
            ExecuteTransportResult attack, JsonNode profile) {
        String findingId = "finding-" + generatedCaseId(entrypoint, template, profile);
        Map<String, Object> finding = new LinkedHashMap<>();
        finding.put("id", findingId);
        finding.put("severity", template.severity());
        finding.put("category", template.category());
        finding.put("outcome", "confirmed");
        finding.put("proofClassification", "external");
        finding.put("title", template.title());
        finding.put("cwe", template.cwe());
        SecurityCaseResult outcome = generatedResult(entrypoint, template, "confirmed", "security_generated_mutation_allowed", attack.statusCode());
        Map<String, Object> coverage = new LinkedHashMap<>(outcome.coverage());
        coverage.put("findingIds", List.of(findingId));
        return withHttpEvidence(new SecurityCaseResult(Map.copyOf(coverage), List.of(Map.copyOf(finding))),
                generatedCaseId(entrypoint, template, profile), baseline, attack);
    }

    static SecurityCaseResult sidecarPassed(JsonNode attack) {
        return new SecurityCaseResult(sidecarCoverage(attack, "passed", "external", null, List.of()), List.of());
    }

    static SecurityCaseResult sidecarNotApplicable(JsonNode attack, String reasonCode) {
        return new SecurityCaseResult(sidecarCoverage(attack, "not_applicable", "external", reasonCode, List.of()), List.of());
    }

    static SecurityCaseResult sidecarBlocked(JsonNode attack, String reasonCode) {
        return new SecurityCaseResult(sidecarCoverage(attack, "blocked", "external", reasonCode, List.of()), List.of());
    }

    static SecurityCaseResult sidecarFinding(JsonNode attack, String proof, boolean internalWeakness) {
        String findingId = "finding-" + attack.path("id").asText();
        Map<String, Object> finding = new LinkedHashMap<>();
        finding.put("id", findingId);
        finding.put("severity", "medium");
        finding.put("category", attack.path("category").asText());
        finding.put("outcome", "confirmed");
        finding.put("proofClassification", proof);
        finding.put("title", internalWeakness ? "Runtime authorization boundary weakness" : "External security control bypass");
        return new SecurityCaseResult(sidecarCoverage(attack, "confirmed", proof, null, List.of(findingId)), List.of(Map.copyOf(finding)));
    }

    static SecurityCaseResult withHttpEvidence(
            SecurityCaseResult result, JsonNode attack, ExecuteTransportResult baseline, ExecuteTransportResult attackResult) {
        return withHttpEvidence(result, attack.path("id").asText(), baseline, attackResult);
    }

    static SecurityCaseResult withHttpEvidence(
            SecurityCaseResult result, String caseId, ExecuteTransportResult baseline, ExecuteTransportResult attackResult) {
        List<Map<String, Object>> evidence = List.of(httpEvidence(caseId, "baseline", baseline),
                httpEvidence(caseId, "attack", attackResult));
        Map<String, Object> coverage = new LinkedHashMap<>(result.coverage());
        coverage.put("evidenceRefIds", evidence.stream().map(value -> value.get("id")).toList());
        coverage.put("evidence", evidence);
        return new SecurityCaseResult(Map.copyOf(coverage), result.findings());
    }

    private static Map<String, Object> httpEvidence(String caseId, String phase, ExecuteTransportResult result) {
        return Map.of("id", "http-" + caseId + "-" + phase, "kind", "http_response", "phase", phase,
                "status", result.status(), "statusCode", result.statusCode() == null ? 0 : result.statusCode(),
                "durationMs", result.durationMs());
    }

    private static Map<String, Object> sidecarCoverage(
            JsonNode attack, String outcome, String proof, String reasonCode, List<String> findingIds) {
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("caseId", attack.path("id").asText());
        coverage.put("entrypointRef", attack.path("entrypointRef").asText());
        coverage.put("authenticationProfileRef", attack.path("authenticationProfileRef").asText());
        coverage.put("attackProfileRef", attack.path("id").asText());
        coverage.put("outcome", outcome);
        coverage.put("proofClassification", proof);
        coverage.put("findingIds", findingIds);
        if (reasonCode != null) {
            coverage.put("reasonCode", reasonCode);
        }
        return Map.copyOf(coverage);
    }

    static SecuritySuiteResult sidecarResult(
            JsonNode contract, SecurityKnowledgeCatalog.Selection selected,
            List<Map<String, Object>> cases, List<Map<String, Object>> findings) {
        long blocked = cases.stream().filter(value -> "blocked".equals(value.get("outcome"))).count();
        Map<String, Object> coverage = Map.of("plannedCount", cases.size(), "executedCount", cases.size(),
                "blockedCount", blocked, "complete", blocked == 0);
        if (blocked > 0) {
            return new SecuritySuiteResult("blocked", "security_sidecar_coverage_incomplete",
                    "resolve blocked Security cases and rerun", Map.of(), Map.of("securityMode", "sidecar_assisted",
                            "knowledgeSnapshot", selected.snapshot().digest(), "coverage", coverage, "cases", List.copyOf(cases),
                            "findings", List.copyOf(findings)));
        }
        return SecuritySuiteResult.completed(Map.of("runStatus", verdict(contract, findings), "securityMode",
                "sidecar_assisted", "knowledgeSnapshot", Map.of("packs", selected.snapshot().packs(), "digest",
                        selected.snapshot().digest()), "coverage", coverage, "cases", List.copyOf(cases), "findings", List.copyOf(findings)));
    }

    static Map<String, Object> caseResult(JsonNode entrypoint, String outcome, String reasonCode, Integer statusCode) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("entrypointRef", entrypoint.path("id").asText());
        result.put("outcome", outcome);
        result.put("reasonCode", reasonCode);
        result.put("proofClassification", "external");
        if (statusCode != null) {
            result.put("statusCode", statusCode);
        }
        return Map.copyOf(result);
    }

    static SecuritySuiteResult incomplete(SecurityKnowledgeCatalog.Selection selection, List<Map<String, Object>> cases,
            String reasonCode, int plannedCount, List<Map<String, Object>> findings) {
        return new SecuritySuiteResult("blocked", reasonCode, "resolve the blocked Security case and rerun", Map.of(), Map.of(
                "securityMode", "blackbox", "knowledgeSnapshot", selection.snapshot().digest(), "cases", List.copyOf(cases),
                "coverage", Map.of("plannedCount", plannedCount, "completedCount", cases.size(), "complete", false),
                "findings", List.copyOf(findings)));
    }

    static String verdict(JsonNode contract, List<Map<String, Object>> findings) {
        JsonNode severities = contract.path("verdictPolicy").path("failOnSeverity");
        boolean failing = findings.stream().anyMatch(finding -> !severities.isArray()
                || containsSeverity(severities, String.valueOf(finding.get("severity"))));
        return failing ? "fail" : "pass";
    }

    private static boolean containsSeverity(JsonNode values, String expected) {
        for (JsonNode value : values) {
            if (expected.equals(value.asText())) {
                return true;
            }
        }
        return false;
    }

    static SecuritySuiteResult blocked(String reasonCode, String nextAction) {
        return SecuritySuiteResult.blocked(reasonCode, nextAction, Map.of("failedStep", "security_preflight"));
    }

    static SecurityCaseResult withRuntimeEvidence(SecurityCaseResult result, SecurityRuntimeEvidence baseline, SecurityRuntimeEvidence attack) {
        List<Map<String, Object>> observations = new ArrayList<>();
        addPhase(observations, baseline, "baseline");
        addPhase(observations, attack, "attack");
        Map<String, Object> coverage = new LinkedHashMap<>(result.coverage());
        coverage.put("runtimeEvidence", List.copyOf(observations));
        return new SecurityCaseResult(Map.copyOf(coverage), result.findings());
    }

    private static void addPhase(List<Map<String, Object>> observations, SecurityRuntimeEvidence evidence, String phase) {
        for (Map<String, Object> observation : evidence.observations()) {
            Map<String, Object> row = new LinkedHashMap<>(observation);
            row.put("phase", phase);
            observations.add(Map.copyOf(row));
        }
    }
}
