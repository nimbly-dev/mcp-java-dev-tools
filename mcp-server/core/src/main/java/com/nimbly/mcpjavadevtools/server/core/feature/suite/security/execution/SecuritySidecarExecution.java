package com.nimbly.mcpjavadevtools.server.core.feature.suite.security.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.ProbeFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.knowledge.SecurityKnowledgeCatalog;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.model.execution.SecurityCaseResult;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.model.execution.SecurityRuntimeEvidence;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.model.result.SecuritySuiteResult;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportResult;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Sequences reset, baseline, attack and runtime evidence for Sidecar-assisted cases. */
class SecuritySidecarExecution {

    private final ProbeFeature probe;
    private final SecurityKnowledgeCatalog knowledge;
    private final SecurityRequestExecution requests;
    private final SecurityProbeEvidence probeEvidence;

    SecuritySidecarExecution(ProbeFeature probe, SecurityKnowledgeCatalog knowledge, SecurityRequestExecution requests) {
        this.probe = probe;
        this.knowledge = knowledge;
        this.requests = requests;
        this.probeEvidence = new SecurityProbeEvidence(probe);
    }

    SecuritySuiteResult runSidecar(JsonNode contract, URI baseUrl, SecurityRequestContext credentials) {
        if (probe == null) {
            return SecurityExecutionResults.blocked("security_sidecar_probe_unavailable", "configure a live Sidecar Probe before execution");
        }
        SecurityKnowledgeCatalog.Selection selected = knowledge.select(SecurityContractPreflight.packRefs(contract));
        if (!selected.available()) {
            return SecurityExecutionResults.blocked("security_knowledge_pack_unavailable", "select only installed Security knowledge packs");
        }
        List<Map<String, Object>> cases = new ArrayList<>();
        List<Map<String, Object>> findings = new ArrayList<>();
        for (JsonNode attack : contract.path("attackProfiles")) {
            SecurityCaseResult result = executeSidecarCase(contract, baseUrl, attack, credentials);
            cases.add(result.coverage());
            findings.addAll(result.findings());
        }
        return SecurityExecutionResults.sidecarResult(contract, selected, cases, findings);
    }

    private SecurityCaseResult executeSidecarCase(JsonNode contract, URI baseUrl, JsonNode attack, SecurityRequestContext credentials) {
        JsonNode entrypoint = SecurityContractPreflight.entrypoint(contract, attack.path("entrypointRef").asText());
        JsonNode profile = SecurityContractPreflight.profile(contract, attack.path("authenticationProfileRef").asText());
        if (entrypoint == null || profile == null) {
            return SecurityExecutionResults.sidecarBlocked(attack, "security_sidecar_reference_missing");
        }
        JsonNode transportDefinition = SecurityRequestExecution.transportDefinition(entrypoint);
        if (!"http".equals(transportDefinition.path("type").asText())) {
            return SecurityExecutionResults.sidecarNotApplicable(attack, "security_sidecar_entrypoint_not_supported");
        }
        if (SecurityProbeEvidence.selectedTargets(contract, attack, entrypoint).isEmpty()) {
            return SecurityExecutionResults.sidecarBlocked(attack, "security_sidecar_runtime_target_ambiguous");
        }
        URI requestUri = SecurityRequestExecution.uri(baseUrl, transportDefinition.path("path").asText());
        if (requestUri == null || !SecurityRequestExecution.allowed(contract.path("targetBoundary"), requestUri)) {
            return SecurityExecutionResults.sidecarBlocked(attack, "security_target_boundary_violation");
        }
        return executeSidecarRequests(contract, attack, entrypoint, requestUri, profile, credentials);
    }

    private SecurityCaseResult executeSidecarRequests(
            JsonNode contract, JsonNode attack, JsonNode entrypoint, URI requestUri, JsonNode profile, SecurityRequestContext credentials) {
        if (!probeEvidence.resetTargets(contract, attack, entrypoint, credentials.budget())) {
            return SecurityExecutionResults.sidecarBlocked(attack, credentials.budget().reasonOr("security_sidecar_probe_reset_failed"));
        }
        long baselineStarted = System.currentTimeMillis();
        ExecuteTransportResult baseline = requests.executeRequest(requestUri, entrypoint, attack.path("baseline"), profile, credentials);
        if (!SecurityRequestExecution.matches(baseline, attack.path("baseline").path("expect"))) {
            return SecurityExecutionResults.sidecarBlocked(attack, credentials.budget().reasonOr("security_sidecar_baseline_unexpected"));
        }
        SecurityRuntimeEvidence baselineEvidence = probeEvidence.evaluateRuntimeEvidence(
                contract, attack, entrypoint, attack.path("baseline").path("expect"), baselineStarted, credentials.budget());
        if (baselineEvidence.blocked()) {
            return SecurityExecutionResults.sidecarBlocked(attack, baselineEvidence.reasonCode());
        }
        if (!probeEvidence.resetTargets(contract, attack, entrypoint, credentials.budget())) {
            return SecurityExecutionResults.sidecarBlocked(attack, credentials.budget().reasonOr("security_sidecar_probe_reset_failed"));
        }
        long attackStarted = System.currentTimeMillis();
        ExecuteTransportResult attackResult = requests.executeRequest(requestUri, entrypoint, attack.path("attack"), profile, credentials);
        if (!SecurityRequestExecution.transported(attackResult)) {
            return SecurityExecutionResults.sidecarBlocked(attack, credentials.budget().reasonOr("security_sidecar_transport_blocked"));
        }
        SecurityRuntimeEvidence evidence = probeEvidence.evaluateRuntimeEvidence(
                contract, attack, entrypoint, attack.path("attack").path("expect"), attackStarted, credentials.budget());
        if (evidence.blocked()) {
            SecurityCaseResult blocked = SecurityExecutionResults.sidecarBlocked(attack, evidence.reasonCode());
            return SecurityExecutionResults.withRuntimeEvidence(
                    SecurityExecutionResults.withHttpEvidence(blocked, attack, baseline, attackResult), baselineEvidence, evidence);
        }
        if (!SecurityProbeEvidence.sameRuntime(baselineEvidence, evidence)) {
            return SecurityExecutionResults.sidecarBlocked(attack, "security_sidecar_runtime_mismatch");
        }
        return SecurityExecutionResults.withRuntimeEvidence(SecurityExecutionResults.withHttpEvidence(classifySidecarCase(attack, attackResult, evidence),
                attack, baseline, attackResult), baselineEvidence, evidence);
    }

    private SecurityCaseResult classifySidecarCase(JsonNode attack, ExecuteTransportResult response, SecurityRuntimeEvidence evidence) {
        JsonNode expectation = attack.path("attack").path("expect");
        boolean externalBypass = "deny".equals(expectation.path("outcome").asText()) && SecurityRequestExecution.allowedResponse(response);
        boolean internalWeakness = "deny".equals(expectation.path("outcome").asText()) && evidence.forbiddenHit();
        if (externalBypass || internalWeakness) {
            String proof = "internal";
            if (externalBypass) {
                proof = evidence.requiredHit() ? "corroborated_external" : "external";
            }
            return SecurityExecutionResults.sidecarFinding(attack, proof, internalWeakness);
        }
        return SecurityRequestExecution.matches(response, expectation) ? SecurityExecutionResults.sidecarPassed(attack)
                : SecurityExecutionResults.sidecarBlocked(attack, "security_sidecar_attack_unexpected");
    }
}
