package com.nimbly.mcpjavadevtools.server.core.feature.suite.security.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.SecuritySuiteFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.knowledge.SecurityKnowledgeCatalog;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.ProbeFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.reset.ProbeBatchResetRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.status.ProbeSingleStatusRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.status.ProbeStatusResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.status.ProbeStatusEntry;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.key.ProbeKeyBatchSelector;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.key.ProbeKeySelector;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.ProbeTargetSelector;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.model.result.SecuritySuiteResult;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.execution.SuiteRuntimeEnvironment;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.TransportExecutionFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportResult;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.TransportProtocol;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.time.Duration;

/** Executes bounded Black-box HTTP baselines through the wrapped Transport Core Feature. */
public final class SecurityPlanExecutor implements SecuritySuiteFeature {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration RUNTIME_EVIDENCE_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration RUNTIME_EVIDENCE_POLL_INTERVAL = Duration.ofMillis(100);
    private static final java.util.regex.Pattern PATH_PARAMETER = java.util.regex.Pattern.compile(
            "%7B([^%/]+)%7D", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern CONTEXT_EXPRESSION = java.util.regex.Pattern.compile("\\$\\{([^}]+)\\}");
    private final TransportExecutionFeature transport;
    private final SecurityKnowledgeCatalog knowledge;
    private final ProbeFeature probe;

    /** Creates the executor from its public Core collaborators. */
    public SecurityPlanExecutor(TransportExecutionFeature transport, SecurityKnowledgeCatalog knowledge) {
        this(transport, knowledge, null);
    }

    /** Creates the executor with the public Probe Feature required for Sidecar mode. */
    public SecurityPlanExecutor(TransportExecutionFeature transport, SecurityKnowledgeCatalog knowledge, ProbeFeature probe) {
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
        this.knowledge = Objects.requireNonNull(knowledge, "knowledge must not be null");
        this.probe = probe;
    }

    /** Validates the Security contract and executes its finite declared request matrix. */
    @Override
    public SecuritySuiteResult executePlan(JsonNode input) {
        JsonNode contract = input.path("contract").isObject() ? input.path("contract") : input;
        Validation validation = Validation.from(contract);
        if (validation.reasonCode() != null) {
            return blocked(validation.reasonCode(), validation.nextAction());
        }
        RequestContext credentials = RequestContext.from(input, contract);
        if (credentials.reasonCode() != null) {
            return blocked(credentials.reasonCode(), "bind each credentialRef to an environment key in project context");
        }
        if (!"blackbox".equals(validation.mode())) {
            Validation sidecar = Validation.sidecar(contract);
            if (sidecar.reasonCode() != null) {
                return blocked(sidecar.reasonCode(), sidecar.nextAction());
            }
            return runSidecar(contract, validation.baseUrl(), credentials);
        }
        SecurityKnowledgeCatalog.Selection selected = knowledge.select(packRefs(contract));
        if (!selected.available()) {
            return blocked("security_knowledge_pack_unavailable", "select only installed Security knowledge packs");
        }
        return runBlackbox(contract, validation.baseUrl(), selected, credentials);
    }

    private SecuritySuiteResult runSidecar(JsonNode contract, URI baseUrl, RequestContext credentials) {
        if (probe == null) {
            return blocked("security_sidecar_probe_unavailable", "configure a live Sidecar Probe before execution");
        }
        SecurityKnowledgeCatalog.Selection selected = knowledge.select(packRefs(contract));
        if (!selected.available()) {
            return blocked("security_knowledge_pack_unavailable", "select only installed Security knowledge packs");
        }
        List<Map<String, Object>> cases = new ArrayList<>();
        List<Map<String, Object>> findings = new ArrayList<>();
        for (JsonNode attack : contract.path("attackProfiles")) {
            SidecarCase result = executeSidecarCase(contract, baseUrl, attack, credentials);
            cases.add(result.coverage());
            findings.addAll(result.findings());
        }
        return sidecarResult(contract, selected, cases, findings);
    }

    private SidecarCase executeSidecarCase(JsonNode contract, URI baseUrl, JsonNode attack, RequestContext credentials) {
        JsonNode entrypoint = entrypoint(contract, attack.path("entrypointRef").asText());
        JsonNode profile = profile(contract, attack.path("authenticationProfileRef").asText());
        if (entrypoint == null || profile == null) {
            return sidecarBlocked(attack, "security_sidecar_reference_missing");
        }
        JsonNode transportDefinition = transportDefinition(entrypoint);
        if (!"http".equals(transportDefinition.path("type").asText())) {
            return sidecarNotApplicable(attack, "security_sidecar_entrypoint_not_supported");
        }
        if (selectedTargets(contract, attack, entrypoint).isEmpty()) {
            return sidecarBlocked(attack, "security_sidecar_runtime_target_ambiguous");
        }
        URI requestUri = uri(baseUrl, transportDefinition.path("path").asText());
        if (requestUri == null || !allowed(contract.path("targetBoundary"), requestUri)) {
            return sidecarBlocked(attack, "security_target_boundary_violation");
        }
        return executeSidecarRequests(contract, attack, entrypoint, requestUri, profile, credentials);
    }

    private SidecarCase executeSidecarRequests(
            JsonNode contract, JsonNode attack, JsonNode entrypoint, URI requestUri, JsonNode profile, RequestContext credentials) {
        if (!resetTargets(contract, attack, entrypoint, credentials.budget)) {
            return sidecarBlocked(attack, credentials.budget.reasonOr("security_sidecar_probe_reset_failed"));
        }
        long baselineStarted = System.currentTimeMillis();
        ExecuteTransportResult baseline = executeRequest(requestUri, entrypoint, attack.path("baseline"), profile, credentials);
        if (!matches(baseline, attack.path("baseline").path("expect"))) {
            return sidecarBlocked(attack, credentials.budget.reasonOr("security_sidecar_baseline_unexpected"));
        }
        RuntimeEvidence baselineEvidence = evaluateRuntimeEvidence(
                contract, attack, entrypoint, attack.path("baseline").path("expect"), baselineStarted, credentials.budget);
        if (baselineEvidence.blocked()) {
            return sidecarBlocked(attack, baselineEvidence.reasonCode());
        }
        if (!resetTargets(contract, attack, entrypoint, credentials.budget)) {
            return sidecarBlocked(attack, credentials.budget.reasonOr("security_sidecar_probe_reset_failed"));
        }
        long attackStarted = System.currentTimeMillis();
        ExecuteTransportResult attackResult = executeRequest(requestUri, entrypoint, attack.path("attack"), profile, credentials);
        if (!transported(attackResult)) {
            return sidecarBlocked(attack, credentials.budget.reasonOr("security_sidecar_transport_blocked"));
        }
        RuntimeEvidence evidence = evaluateRuntimeEvidence(
                contract, attack, entrypoint, attack.path("attack").path("expect"), attackStarted, credentials.budget);
        if (evidence.blocked()) {
            return withRuntimeEvidence(withHttpEvidence(sidecarBlocked(attack, evidence.reasonCode()),
                    attack, baseline, attackResult), baselineEvidence, evidence);
        }
        if (!sameRuntime(baselineEvidence, evidence)) {
            return sidecarBlocked(attack, "security_sidecar_runtime_mismatch");
        }
        return withRuntimeEvidence(withHttpEvidence(classifySidecarCase(attack, attackResult, evidence),
                attack, baseline, attackResult), baselineEvidence, evidence);
    }

    private boolean resetTargets(JsonNode contract, JsonNode attack, JsonNode entrypoint, RequestBudget budget) {
        for (JsonNode target : selectedTargets(contract, attack, entrypoint)) {
            if (budget.expiredNow()) {
                return false;
            }
            var result = probe.execute(new ProbeBatchResetRequest(
                    targetSelector(target),
                    new ProbeKeyBatchSelector(List.of(target.path("strictLineKey").asText())), budget.probeTimeout()));
            if (result.status() != com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResultStatus.SUCCESS) {
                return false;
            }
        }
        return true;
    }

    private RuntimeEvidence evaluateRuntimeEvidence(
            JsonNode contract, JsonNode attack, JsonNode entrypoint, JsonNode expectation, long phaseStarted, RequestBudget budget) {
        Map<String, JsonNode> targets = targetsById(selectedTargets(contract, attack, entrypoint));
        if (!requiredTargetsHit(targets, expectation.path("mustHitRuntimeTargets"), budget)) {
            return runtimeBlocked(budget.reasonOr("security_sidecar_required_line_not_hit"));
        }
        List<Map<String, Object>> observations = new ArrayList<>();
        boolean forbiddenHit = false;
        for (Map.Entry<String, JsonNode> target : targets.entrySet()) {
            ProbeStatusEntry status = observedStatus(target.getValue(), budget);
            if (status == null) {
                return runtimeBlocked(budget.reasonOr("security_sidecar_probe_status_unavailable"));
            }
            if (status.runtime() == null || status.runtime().runtimeInstanceId() == null) {
                return runtimeBlocked("security_sidecar_runtime_identity_missing");
            }
            boolean freshHit = status.lineHit() && status.lastHitEpoch() != null && status.lastHitEpoch() >= phaseStarted;
            if (containsText(expectation.path("mustHitRuntimeTargets"), target.getKey()) && !freshHit) {
                return runtimeBlocked("security_sidecar_required_line_not_hit");
            }
            forbiddenHit |= containsText(expectation.path("mustNotHitRuntimeTargets"), target.getKey()) && freshHit;
            observations.add(runtimeObservation(target.getKey(), status, freshHit));
        }
        for (JsonNode forbidden : expectation.path("mustNotHitRuntimeTargets")) {
            if (!targets.containsKey(forbidden.asText())) {
                return runtimeBlocked("security_sidecar_runtime_target_ambiguous");
            }
        }
        return new RuntimeEvidence(false, null, expectation.path("mustHitRuntimeTargets").size() > 0,
                forbiddenHit, List.copyOf(observations));
    }

    private ProbeStatusEntry observedStatus(JsonNode target, RequestBudget budget) {
        ProbeStatusResult status = targetStatus(target, budget);
        if (status == null) {
            return null;
        }
        return status.entries().stream().filter(entry -> target.path("strictLineKey").asText().equals(entry.key())
                && Boolean.TRUE.equals(entry.endpointOk()) && Boolean.TRUE.equals(entry.lineResolvable()))
                .findFirst().orElse(null);
    }

    private static Map<String, Object> runtimeObservation(String target, ProbeStatusEntry status, boolean freshHit) {
        return Map.of("targetId", target, "strictLineKey", status.key(), "lineHit", freshHit,
                "hitCount", status.hitCount() == null ? 0 : status.hitCount(),
                "lastHitEpoch", status.lastHitEpoch() == null ? 0 : status.lastHitEpoch(),
                "runtimeInstanceId", status.runtime().runtimeInstanceId());
    }

    private static RuntimeEvidence runtimeBlocked(String reason) {
        return new RuntimeEvidence(true, reason, false, false, List.of());
    }

    private static boolean sameRuntime(RuntimeEvidence baseline, RuntimeEvidence attack) {
        return baseline.observations().stream().allMatch(before -> attack.observations().stream().anyMatch(after ->
                before.get("targetId").equals(after.get("targetId"))
                        && before.get("runtimeInstanceId").equals(after.get("runtimeInstanceId"))));
    }

    private static SidecarCase withRuntimeEvidence(SidecarCase result, RuntimeEvidence baseline, RuntimeEvidence attack) {
        List<Map<String, Object>> observations = new ArrayList<>();
        addPhase(observations, baseline, "baseline");
        addPhase(observations, attack, "attack");
        Map<String, Object> coverage = new LinkedHashMap<>(result.coverage());
        coverage.put("runtimeEvidence", List.copyOf(observations));
        return new SidecarCase(Map.copyOf(coverage), result.findings());
    }

    private static void addPhase(List<Map<String, Object>> observations, RuntimeEvidence evidence, String phase) {
        for (Map<String, Object> observation : evidence.observations()) {
            Map<String, Object> row = new LinkedHashMap<>(observation);
            row.put("phase", phase);
            observations.add(Map.copyOf(row));
        }
    }

    private boolean requiredTargetsHit(Map<String, JsonNode> targets, JsonNode required, RequestBudget budget) {
        for (JsonNode targetId : required) {
            JsonNode target = targets.get(targetId.asText());
            if (target == null || !awaitObservedTargetHit(target, budget)) {
                return false;
            }
        }
        return true;
    }

    private boolean awaitObservedTargetHit(JsonNode target, RequestBudget budget) {
        long deadline = Math.min(budget.deadline, System.nanoTime() + RUNTIME_EVIDENCE_TIMEOUT.toNanos());
        while (System.nanoTime() < deadline) {
            ProbeStatusResult status = targetStatus(target, budget);
            if (status != null && status.entries().stream().anyMatch(entry -> entry.lineHit())) {
                return true;
            }
            if (!pauseForEvidence()) {
                return false;
            }
        }
        return false;
    }

    private ProbeStatusResult targetStatus(JsonNode target, RequestBudget budget) {
        if (budget.expiredNow()) {
            return null;
        }
        var result = probe.execute(new ProbeSingleStatusRequest(
                targetSelector(target),
                new ProbeKeySelector(target.path("strictLineKey").asText(), null), budget.probeTimeout()));
        if (result.status() != com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResultStatus.SUCCESS) {
            return null;
        }
        return result.actionResult().filter(ProbeStatusResult.class::isInstance).map(ProbeStatusResult.class::cast)
                .orElse(null);
    }

    private static boolean pauseForEvidence() {
        try {
            Thread.sleep(RUNTIME_EVIDENCE_POLL_INTERVAL.toMillis());
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private SidecarCase classifySidecarCase(JsonNode attack, ExecuteTransportResult response, RuntimeEvidence evidence) {
        JsonNode expectation = attack.path("attack").path("expect");
        boolean externalBypass = "deny".equals(expectation.path("outcome").asText()) && allowedResponse(response);
        boolean internalWeakness = "deny".equals(expectation.path("outcome").asText()) && evidence.forbiddenHit();
        if (externalBypass || internalWeakness) {
            String proof = externalBypass && evidence.requiredHit() ? "corroborated_external"
                    : externalBypass ? "external" : "internal";
            return sidecarFinding(attack, proof, internalWeakness);
        }
        return matches(response, expectation) ? sidecarPassed(attack) : sidecarBlocked(attack, "security_sidecar_attack_unexpected");
    }

    private SecuritySuiteResult runBlackbox(
            JsonNode contract, URI baseUrl, SecurityKnowledgeCatalog.Selection selected, RequestContext credentials) {
        List<Map<String, Object>> cases = new ArrayList<>();
        List<Map<String, Object>> findings = new ArrayList<>();
        List<SecurityKnowledgeCatalog.GeneratedCase> generated = contract.has("customCases")
                ? List.of() : knowledge.generatedCases(selected);
        int plannedCount = contract.path("entrypoints").size() * contract.path("authenticationProfiles").size()
                * (generated.size() + 1) + contract.path("customCases").size();
        for (JsonNode entrypoint : contract.path("entrypoints")) {
            for (JsonNode profile : contract.path("authenticationProfiles")) {
                Map<String, Object> outcome = baseline(contract, baseUrl, entrypoint, profile, credentials);
                cases.add(caseForProfile(outcome, profile));
                if ("blocked".equals(outcome.get("outcome"))) {
                    return incomplete(selected, cases, String.valueOf(outcome.get("reasonCode")), plannedCount, findings);
                }
            }
        }
        for (JsonNode attack : contract.path("customCases")) {
            SidecarCase outcome = executeBlackboxCase(contract, baseUrl, attack, credentials);
            cases.add(outcome.coverage());
            findings.addAll(outcome.findings());
            if ("blocked".equals(outcome.coverage().get("outcome"))) {
                return incomplete(selected, cases, String.valueOf(outcome.coverage().get("reasonCode")), plannedCount, findings);
            }
        }
        for (SecurityKnowledgeCatalog.GeneratedCase template : generated) {
            for (JsonNode entrypoint : contract.path("entrypoints")) {
                for (JsonNode profile : contract.path("authenticationProfiles")) {
                    SidecarCase outcome = executeGeneratedCase(contract, entrypoint, profile, baseUrl, template, credentials);
                    cases.add(caseForProfile(outcome.coverage(), profile));
                    findings.addAll(outcome.findings());
                    if ("blocked".equals(outcome.coverage().get("outcome"))) {
                        return incomplete(selected, cases, String.valueOf(outcome.coverage().get("reasonCode")), plannedCount, findings);
                    }
                }
            }
        }
        return SecuritySuiteResult.completed(Map.of("runStatus", verdict(contract, findings), "securityMode", "blackbox",
                "knowledgeSnapshot", Map.of("packs", selected.snapshot().packs(), "digest", selected.snapshot().digest()),
                "coverage", Map.of("plannedCount", cases.size(), "completedCount", cases.size(), "complete", true),
                "cases", List.copyOf(cases), "findings", List.copyOf(findings)));
    }

    private SidecarCase executeGeneratedCase(
            JsonNode contract,
            JsonNode entrypoint,
            JsonNode profile,
            URI baseUrl,
            SecurityKnowledgeCatalog.GeneratedCase template,
            RequestContext credentials) {
        JsonNode transportDefinition = transportDefinition(entrypoint);
        URI requestUri = uri(baseUrl, transportDefinition.path("path").asText());
        if (requestUri == null || !"http".equals(transportDefinition.path("type").asText())) {
            return generatedResult(entrypoint, template, "not_applicable", "security_generated_entrypoint_not_supported", null);
        }
        if (!applicable(contract, profile, template)) {
            return generatedResult(entrypoint, template, "not_applicable", "security_generated_rule_not_applicable", null);
        }
        GeneratedRequest mutation = generatedRequest(entrypoint, requestUri, contract, template, profile);
        if (mutation == null) {
            return generatedResult(entrypoint, template, "not_applicable", "security_generated_mutation_target_missing", null);
        }
        ExecuteTransportResult baseline = executeRequest(requestUri, entrypoint, entrypoint.path("baseline"), profile, credentials);
        if (!allowedResponse(baseline)) {
            return generatedResult(entrypoint, template, "blocked",
                    credentials.budget.reasonOr("security_generated_baseline_unexpected"), baseline.statusCode());
        }
        ExecuteTransportResult response = executeRequest(
                mutation.uri(), entrypoint, mutation.request(), profile, credentials, !mutation.removeCredential());
        if (!transported(response)) {
            return generatedResult(entrypoint, template, "blocked",
                    credentials.budget.reasonOr("security_generated_transport_blocked"), response.statusCode());
        }
        if (expectedDeny(response, template)) {
            return withHttpEvidence(generatedResult(entrypoint, template, "passed",
                    "security_generated_mutation_denied", response.statusCode()),
                    generatedCaseId(entrypoint, template, profile), baseline, response);
        }
        if (allowedResponse(response)) {
            return generatedFinding(entrypoint, template, baseline, response, profile);
        }
        return generatedResult(entrypoint, template, "blocked", "security_generated_attack_unexpected", response.statusCode());
    }

    private static boolean applicable(JsonNode contract, JsonNode profile, SecurityKnowledgeCatalog.GeneratedCase template) {
        if (template.requiredAuthentication() && "anonymous".equals(profile.path("kind").asText())) {
            return false;
        }
        JsonNode fixtures = contract.path("targetBoundary").path("fixtureContext");
        return template.requiredFixtureContextKeys().stream().allMatch(key -> fixtures.hasNonNull(key));
    }

    private static String generatedCaseId(JsonNode entrypoint, SecurityKnowledgeCatalog.GeneratedCase template, JsonNode profile) {
        return template.ruleId() + "-" + entrypoint.path("id").asText() + "-" + profile.path("id").asText();
    }

    private static Map<String, Object> caseForProfile(Map<String, Object> outcome, JsonNode profile) {
        Map<String, Object> row = new LinkedHashMap<>(outcome);
        String prefix = String.valueOf(row.getOrDefault("ruleId", "baseline"));
        row.put("caseId", prefix + "-" + row.get("entrypointRef") + "-" + profile.path("id").asText());
        row.put("authenticationProfileRef", profile.path("id").asText());
        return Map.copyOf(row);
    }

    private static GeneratedRequest generatedRequest(
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

    private static GeneratedRequest queryMutation(URI uri, ObjectNode request, String payload) {
        ObjectNode query = request.path("query").isObject() ? (ObjectNode) request.path("query") : request.putObject("query");
        String name = query.fieldNames().hasNext() ? query.fieldNames().next() : null;
        if (name == null) {
            return null;
        }
        query.put(name, payload);
        return new GeneratedRequest(uri, request, false);
    }

    private static GeneratedRequest headerMutation(URI uri, ObjectNode request, boolean removeCredential, String credentialRef) {
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
        return new GeneratedRequest(uri, request, true);
    }

    private static GeneratedRequest pathMutation(URI uri, ObjectNode request, String payload) {
        ObjectNode parameters = request.path("pathParameters").isObject()
                ? (ObjectNode) request.path("pathParameters") : null;
        if (parameters == null || !parameters.fieldNames().hasNext()) {
            return null;
        }
        parameters.put(parameters.fieldNames().next(), payload);
        return new GeneratedRequest(uri, request, false);
    }

    private static GeneratedRequest bodyMutation(URI uri, ObjectNode request, String payload) {
        if (!request.path("body").isObject()) {
            return null;
        }
        request.set("body", MAPPER.getNodeFactory().textNode(payload));
        return new GeneratedRequest(uri, request, false);
    }

    private static SidecarCase generatedResult(
            JsonNode entrypoint, SecurityKnowledgeCatalog.GeneratedCase template, String outcome, String reasonCode, Integer statusCode) {
        Map<String, Object> coverage = new LinkedHashMap<>(caseResult(entrypoint, outcome, reasonCode, statusCode));
        coverage.put("knowledgePackRef", template.packRef());
        coverage.put("ruleId", template.ruleId());
        coverage.put("mutationBoundary", template.mutationBoundary());
        return new SidecarCase(Map.copyOf(coverage), List.of());
    }

    private SidecarCase executeBlackboxCase(JsonNode contract, URI baseUrl, JsonNode attack, RequestContext credentials) {
        JsonNode entrypoint = entrypoint(contract, attack.path("entrypointRef").asText());
        JsonNode profile = profile(contract, attack.path("authenticationProfileRef").asText());
        if (entrypoint == null || profile == null) {
            return sidecarBlocked(attack, "security_blackbox_reference_missing");
        }
        JsonNode transportDefinition = transportDefinition(entrypoint);
        if (!"http".equals(transportDefinition.path("type").asText())) {
            return sidecarNotApplicable(attack, "security_blackbox_entrypoint_not_supported");
        }
        URI requestUri = uri(baseUrl, transportDefinition.path("path").asText());
        if (requestUri == null || !allowed(contract.path("targetBoundary"), requestUri)) {
            return sidecarBlocked(attack, "security_target_boundary_violation");
        }
        return executeBlackboxRequests(attack, entrypoint, requestUri, profile, credentials);
    }

    private SidecarCase executeBlackboxRequests(
            JsonNode attack, JsonNode entrypoint, URI requestUri, JsonNode profile, RequestContext credentials) {
        ExecuteTransportResult baseline = executeRequest(requestUri, entrypoint, attack.path("baseline"), profile, credentials);
        if (!matches(baseline, attack.path("baseline").path("expect"))) {
            return sidecarBlocked(attack, "security_blackbox_baseline_unexpected");
        }
        ExecuteTransportResult attackResult = executeRequest(requestUri, entrypoint, attack.path("attack"), profile, credentials);
        if (!transported(attackResult)) {
            return sidecarBlocked(attack, "security_blackbox_transport_blocked");
        }
        boolean bypass = "deny".equals(attack.path("attack").path("expect").path("outcome").asText())
                && allowedResponse(attackResult);
        SidecarCase result = bypass ? sidecarFinding(attack, "external", false)
                : matches(attackResult, attack.path("attack").path("expect")) ? sidecarPassed(attack)
                        : sidecarBlocked(attack, "security_blackbox_attack_unexpected");
        return withHttpEvidence(result, attack, baseline, attackResult);
    }

    private Map<String, Object> baseline(
            JsonNode contract, URI baseUrl, JsonNode entrypoint, JsonNode profile, RequestContext credentials) {
        JsonNode transportDefinition = transportDefinition(entrypoint);
        if (!"http".equals(transportDefinition.path("type").asText())) {
            return caseResult(entrypoint, "not_applicable", "security_blackbox_entrypoint_not_supported", null);
        }
        URI requestUri = uri(baseUrl, transportDefinition.path("path").asText());
        if (requestUri == null || !allowed(contract.path("targetBoundary"), requestUri)) {
            return caseResult(entrypoint, "blocked", "security_target_boundary_violation", null);
        }
        ExecuteTransportResult result = executeRequest(
                requestUri, entrypoint, entrypoint.path("baseline"), profile, credentials);
        if (!transported(result)) {
            return caseResult(entrypoint, "blocked", result.reasonCode(), result.statusCode());
        }
        return allowedResponse(result)
                ? caseResult(entrypoint, "passed", "ok", result.statusCode())
                : caseResult(entrypoint, "blocked", "security_blackbox_baseline_unexpected", result.statusCode());
    }

    private ExecuteTransportResult executeRequest(
            URI requestUri, JsonNode entrypoint, JsonNode request, JsonNode profile, RequestContext credentials) {
        return executeRequest(requestUri, entrypoint, request, profile, credentials, true);
    }

    private ExecuteTransportResult executeRequest(
            URI requestUri,
            JsonNode entrypoint,
            JsonNode request,
            JsonNode profile,
            RequestContext credentials,
            boolean applyCredentials) {
        try {
            Map<String, Object> payload = requestPayload(requestUri, entrypoint, request, profile, credentials, applyCredentials);
            if (!allowed(credentials.boundary, URI.create(String.valueOf(payload.get("url"))))) {
                return ExecuteTransportResult.blockedInvalid("security_target_boundary_violation",
                        "Keep resolved requests inside the declared target boundary", "http", 1);
            }
            return credentials.budget.execute(transport, payload);
        } catch (IllegalArgumentException exception) {
            return ExecuteTransportResult.blockedInvalid("security_blackbox_fixture_unresolved",
                    "Resolve every declared fixture expression and path parameter before execution", "http", 1);
        }
    }

    private static Map<String, Object> requestPayload(
            URI requestUri, JsonNode entrypoint, JsonNode request, JsonNode profile, RequestContext credentials, boolean applyCredentials) {
        Map<String, Object> payload = new LinkedHashMap<>();
        Map<String, String> fixtures = credentials.fixtureContext(entrypoint);
        JsonNode value = credentials.resolveRequest(request, fixtures);
        String path = resolveTransportPath(transportDefinition(entrypoint).path("path").asText(), fixtures);
        URI resolved = withPathParameters(uri(requestUri, path), value);
        if (resolved == null) {
            throw new IllegalArgumentException("unresolved Security path");
        }
        payload.put("url", withQuery(resolved, query(value)).toString());
        payload.put("method", transportDefinition(entrypoint).path("method").asText());
        Map<String, String> headers = requestHeaders(value.path("headers"));
        if (applyCredentials) {
            credentials.apply(profile, headers);
        }
        if (!headers.isEmpty()) {
            payload.put("headers", Map.copyOf(headers));
        }
        if (!value.path("body").isMissingNode() && !value.path("body").isNull()) {
            JsonNode body = value.path("body");
            payload.put("body", body.isTextual() ? body.asText() : body.toString());
        }
        return payload;
    }

    private static String resolveString(String value, Map<String, String> context) {
        var expressions = CONTEXT_EXPRESSION.matcher(value);
        return expressions.replaceAll(match -> {
            String key = match.group(1);
            String fixtureKey = key.startsWith("fixture.") ? key.substring("fixture.".length()) : key;
            String resolved = context.getOrDefault(key, context.get(fixtureKey));
            if (resolved == null) {
                throw new IllegalArgumentException("unresolved Security fixture expression");
            }
            return java.util.regex.Matcher.quoteReplacement(resolved);
        });
    }

    private static String resolveTransportPath(String template, Map<String, String> context) {
        return CONTEXT_EXPRESSION.matcher(template).replaceAll(match -> {
            String value = resolveString(match.group(), context);
            try {
                // Encode fixture data as path content, preserving the template's existing URI escapes.
                String encoded = new URI(null, null, "/" + value, null).toASCIIString().substring(1);
                return java.util.regex.Matcher.quoteReplacement(encoded);
            } catch (java.net.URISyntaxException exception) {
                throw new IllegalArgumentException("invalid Security transport path fixture");
            }
        });
    }

    private static JsonNode resolveValues(JsonNode value, Map<String, String> context) {
        if (value.isTextual()) {
            return MAPPER.getNodeFactory().textNode(resolveString(value.asText(), context));
        }
        if (value.isObject()) {
            ObjectNode resolved = MAPPER.createObjectNode();
            value.fields().forEachRemaining(entry -> resolved.set(entry.getKey(), resolveValues(entry.getValue(), context)));
            return resolved;
        }
        if (value.isArray()) {
            var resolved = MAPPER.createArrayNode();
            value.forEach(element -> resolved.add(resolveValues(element, context)));
            return resolved;
        }
        return value;
    }

    private static boolean expectedDeny(
            ExecuteTransportResult response, SecurityKnowledgeCatalog.GeneratedCase template) {
        return template.expectedDenyStatusCodes().contains(response.statusCode()) && deniedResponse(response);
    }

    private static SidecarCase generatedFinding(
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
        SidecarCase outcome = generatedResult(entrypoint, template, "confirmed", "security_generated_mutation_allowed", attack.statusCode());
        Map<String, Object> coverage = new LinkedHashMap<>(outcome.coverage());
        coverage.put("findingIds", List.of(findingId));
        return withHttpEvidence(new SidecarCase(Map.copyOf(coverage), List.of(Map.copyOf(finding))),
                generatedCaseId(entrypoint, template, profile), baseline, attack);
    }

    private static JsonNode query(JsonNode request) {
        return request.path("query").isObject() ? request.path("query") : request.path("queryParameters");
    }

    private static JsonNode transportDefinition(JsonNode entrypoint) {
        return entrypoint.path("transport").isObject() ? entrypoint.path("transport") : entrypoint;
    }

    private static URI withPathParameters(URI requestUri, JsonNode request) {
        if (requestUri == null) {
            return null;
        }
        JsonNode pathParameters = request.path("pathParameters");
        var placeholders = PATH_PARAMETER.matcher(requestUri.getRawPath());
        while (placeholders.find()) {
            if (!pathParameters.hasNonNull(placeholders.group(1))) {
                return null;
            }
        }
        if (!pathParameters.isObject() || pathParameters.isEmpty()) {
            return requestUri;
        }
        String path = requestUri.getRawPath();
        var fields = pathParameters.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            String placeholder = "%7B" + entry.getKey() + "%7D";
            path = path.replace(placeholder, java.net.URLEncoder.encode(
                    entry.getValue().asText(), java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20"));
        }
        try {
            String query = requestUri.getRawQuery() == null ? "" : "?" + requestUri.getRawQuery();
            return URI.create(requestUri.getScheme() + "://" + requestUri.getRawAuthority() + path + query);
        } catch (IllegalArgumentException exception) {
            return requestUri;
        }
    }

    private static URI withQuery(URI requestUri, JsonNode query) {
        if (!query.isObject() || query.isEmpty()) {
            return requestUri;
        }
        StringBuilder values = new StringBuilder();
        if (requestUri.getRawQuery() != null) {
            for (String pair : requestUri.getRawQuery().split("&")) {
                String name = java.net.URLDecoder.decode(pair.split("=", 2)[0], java.nio.charset.StandardCharsets.UTF_8);
                if (!query.has(name)) {
                    if (!values.isEmpty()) {
                        values.append('&');
                    }
                    values.append(pair);
                }
            }
        }
        query.fields().forEachRemaining(entry -> appendQuery(values, entry.getKey(), entry.getValue().asText()));
        try {
            return URI.create(requestUri.getScheme() + "://" + requestUri.getRawAuthority()
                    + requestUri.getRawPath() + "?" + values);
        } catch (IllegalArgumentException exception) {
            return requestUri;
        }
    }

    private static void appendQuery(StringBuilder values, String name, String value) {
        if (!values.isEmpty()) {
            values.append('&');
        }
        values.append(java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8));
        values.append('=').append(java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8));
    }

    private static Map<String, String> requestHeaders(JsonNode headers) {
        Map<String, String> values = new LinkedHashMap<>();
        if (headers.isObject()) {
            headers.fields().forEachRemaining(entry -> values.put(entry.getKey(), entry.getValue().asText()));
        }
        return values;
    }

    private static boolean matches(ExecuteTransportResult result, JsonNode expectation) {
        if (!transported(result)) {
            return false;
        }
        String outcome = expectation.path("outcome").asText();
        if (!expectedStatusCode(result.statusCode(), expectation.path("statusCodes"))) {
            return false;
        }
        return switch (outcome) {
            case "allow" -> allowedResponse(result);
            case "deny" -> deniedResponse(result);
            case "error" -> result.statusCode() != null && result.statusCode() >= 500;
            default -> false;
        };
    }

    private static boolean expectedStatusCode(Integer actual, JsonNode expected) {
        if (!expected.isArray() || expected.isEmpty()) {
            return true;
        }
        for (JsonNode value : expected) {
            if (actual != null && actual.intValue() == value.asInt()) {
                return true;
            }
        }
        return false;
    }

    private static boolean allowedResponse(ExecuteTransportResult result) {
        return result.statusCode() != null && result.statusCode() >= 200 && result.statusCode() < 400;
    }

    private static boolean transported(ExecuteTransportResult result) {
        return "pass".equals(result.status()) || "fail_http".equals(result.status());
    }

    private static boolean deniedResponse(ExecuteTransportResult result) {
        return result.statusCode() != null && result.statusCode() >= 400 && result.statusCode() < 500;
    }

    private static JsonNode entrypoint(JsonNode contract, String id) {
        for (JsonNode value : contract.path("entrypoints")) {
            if (id.equals(value.path("id").asText())) {
                return value;
            }
        }
        return null;
    }

    private static JsonNode profile(JsonNode contract, String id) {
        for (JsonNode value : contract.path("authenticationProfiles")) {
            if (id.equals(value.path("id").asText())) {
                return value;
            }
        }
        return null;
    }

    private static List<JsonNode> selectedTargets(JsonNode contract, JsonNode attack, JsonNode entrypoint) {
        List<JsonNode> targets = new ArrayList<>();
        for (JsonNode target : contract.path("runtimeTargets")) {
            if (attack.path("entrypointRef").asText().equals(target.path("entrypointRef").asText())
                    || entrypoint.path("id").asText().equals(target.path("entrypointRef").asText())) {
                targets.add(target);
            }
        }
        return List.copyOf(targets);
    }

    private static ProbeTargetSelector targetSelector(JsonNode target) {
        return new ProbeTargetSelector(target.path("probeId").asText(null), target.path("probeBaseUrl").asText(null));
    }

    private static Map<String, JsonNode> targetsById(List<JsonNode> targets) {
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        for (JsonNode target : targets) {
            byId.put(target.path("id").asText(), target);
        }
        return Map.copyOf(byId);
    }

    private static SidecarCase sidecarPassed(JsonNode attack) {
        return new SidecarCase(sidecarCoverage(attack, "passed", "external", null, List.of()), List.of());
    }

    private static SidecarCase sidecarNotApplicable(JsonNode attack, String reasonCode) {
        return new SidecarCase(sidecarCoverage(attack, "not_applicable", "external", reasonCode, List.of()), List.of());
    }

    private static SidecarCase sidecarBlocked(JsonNode attack, String reasonCode) {
        return new SidecarCase(sidecarCoverage(attack, "blocked", "external", reasonCode, List.of()), List.of());
    }

    private static SidecarCase sidecarFinding(JsonNode attack, String proof, boolean internalWeakness) {
        String findingId = "finding-" + attack.path("id").asText();
        Map<String, Object> finding = new LinkedHashMap<>();
        finding.put("id", findingId);
        finding.put("severity", "medium");
        finding.put("category", attack.path("category").asText());
        finding.put("outcome", "confirmed");
        finding.put("proofClassification", proof);
        finding.put("title", internalWeakness ? "Runtime authorization boundary weakness" : "External security control bypass");
        return new SidecarCase(sidecarCoverage(attack, "confirmed", proof, null, List.of(findingId)), List.of(Map.copyOf(finding)));
    }

    private static SidecarCase withHttpEvidence(
            SidecarCase result, JsonNode attack, ExecuteTransportResult baseline, ExecuteTransportResult attackResult) {
        return withHttpEvidence(result, attack.path("id").asText(), baseline, attackResult);
    }

    private static SidecarCase withHttpEvidence(
            SidecarCase result, String caseId, ExecuteTransportResult baseline, ExecuteTransportResult attackResult) {
        List<Map<String, Object>> evidence = List.of(httpEvidence(caseId, "baseline", baseline),
                httpEvidence(caseId, "attack", attackResult));
        Map<String, Object> coverage = new LinkedHashMap<>(result.coverage());
        coverage.put("evidenceRefIds", evidence.stream().map(value -> value.get("id")).toList());
        coverage.put("evidence", evidence);
        return new SidecarCase(Map.copyOf(coverage), result.findings());
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

    private static SecuritySuiteResult sidecarResult(
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

    private static List<String> packRefs(JsonNode contract) {
        List<String> values = new ArrayList<>();
        contract.path("securityKnowledge").path("packRefs").forEach(value -> values.add(value.asText()));
        return List.copyOf(values);
    }

    private static URI uri(URI baseUrl, String path) {
        if (path == null || path.isBlank() || !path.startsWith("/")) {
            return null;
        }
        try {
            return baseUrl.resolve(path.replace("{", "%7B").replace("}", "%7D"));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static boolean allowed(JsonNode boundary, URI value) {
        int port = port(value);
        return containsText(boundary.path("allowedHosts"), value.getHost())
                && containsPort(boundary.path("allowedPorts"), port);
    }

    private static int port(URI value) {
        if (value.getPort() >= 0) {
            return value.getPort();
        }
        return "https".equals(value.getScheme()) ? 443 : 80;
    }

    private static boolean containsText(JsonNode values, String expected) {
        for (JsonNode value : values) {
            if (expected.equals(value.asText())) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsPort(JsonNode values, int expected) {
        for (JsonNode value : values) {
            if (value.canConvertToInt() && value.asInt() == expected) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Object> caseResult(JsonNode entrypoint, String outcome, String reasonCode, Integer statusCode) {
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

    private static SecuritySuiteResult incomplete(SecurityKnowledgeCatalog.Selection selection, List<Map<String, Object>> cases,
            String reasonCode, int plannedCount, List<Map<String, Object>> findings) {
        return new SecuritySuiteResult("blocked", reasonCode, "resolve the blocked Security case and rerun", Map.of(), Map.of(
                "securityMode", "blackbox", "knowledgeSnapshot", selection.snapshot().digest(), "cases", List.copyOf(cases),
                "coverage", Map.of("plannedCount", plannedCount, "completedCount", cases.size(), "complete", false),
                "findings", List.copyOf(findings)));
    }

    private static String verdict(JsonNode contract, List<Map<String, Object>> findings) {
        JsonNode severities = contract.path("verdictPolicy").path("failOnSeverity");
        boolean failing = findings.stream().anyMatch(finding -> !severities.isArray()
                || containsText(severities, String.valueOf(finding.get("severity"))));
        return failing ? "fail" : "pass";
    }

    private static SecuritySuiteResult blocked(String reasonCode, String nextAction) {
        return SecuritySuiteResult.blocked(reasonCode, nextAction, Map.of("failedStep", "security_preflight"));
    }

    private record Validation(String reasonCode, String nextAction, String mode, URI baseUrl) {
        private static Validation from(JsonNode contract) {
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
            Validation policies = policies(contract);
            if (policies != null) {
                return policies;
            }
            URI baseUrl = baseUrl(contract.path("targetBoundary"));
            return baseUrl == null ? invalid("security_contract_target_boundary_invalid", "provide a bounded local-ci baseUrl")
                    : new Validation(null, null, mode, baseUrl);
        }

        private static Validation policies(JsonNode contract) {
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

        private static Validation sidecar(JsonNode contract) {
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
            return new Validation(null, null, "sidecar_assisted", null);
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

        private static Validation invalid(String reasonCode, String nextAction) {
            return new Validation(reasonCode, nextAction, null, null);
        }
    }

    private record RuntimeEvidence(boolean blocked, String reasonCode, boolean requiredHit, boolean forbiddenHit,
            List<Map<String, Object>> observations) {
    }

    private static class RequestContext {
        private final Map<String, String> values;
        private final String reasonCode;
        private final RequestBudget budget;
        private final JsonNode boundary;

        private RequestContext(Map<String, String> values, String reasonCode, JsonNode contract) {
            this.values = Map.copyOf(values);
            this.reasonCode = reasonCode;
            this.budget = new RequestBudget(contract);
            this.boundary = contract.path("targetBoundary");
        }

        private String reasonCode() {
            return reasonCode;
        }

        private static RequestContext from(JsonNode input, JsonNode contract) {
            Map<String, String> values = new LinkedHashMap<>();
            JsonNode bindings = input.path("credentialBindings");
            Map<String, String> environment = SuiteRuntimeEnvironment.load(input.path("credentialSource"));
            SecurityCredentialRefresh.Result refresh = new SecurityCredentialRefresh()
                    .refresh(input.path("credentialSource"), environment);
            if (!refresh.successful()) {
                return new RequestContext(Map.of(), refresh.reasonCode(), contract);
            }
            environment = SuiteRuntimeEnvironment.load(input.path("credentialSource"));
            if (bindings.isObject()) {
                var bindingEntries = bindings.fields();
                while (bindingEntries.hasNext()) {
                    var binding = bindingEntries.next();
                    String value = environment.get(binding.getValue().asText());
                    if (value != null && !value.isBlank()) {
                        values.put(binding.getKey(), value);
                    }
                }
            }
            for (JsonNode profile : contract.path("authenticationProfiles")) {
                if ("anonymous".equals(profile.path("kind").asText())) {
                    continue;
                }
                String ref = profile.path("credentialRef").asText();
                if (ref.isBlank() || !values.containsKey(ref)) {
                    return new RequestContext(Map.of(), "security_runtime_credentials_unavailable", contract);
                }
            }
            return new RequestContext(values, null, contract);
        }

        private Map<String, String> fixtureContext(JsonNode entrypoint) {
            Map<String, String> context = new LinkedHashMap<>();
            collectFixtures(context, boundary.path("fixtureContext"));
            collectFixtures(context, boundary.path("details").path("fixtureContext"));
            collectFixtures(context, entrypoint.path("details").path("fixtureContext"));
            return context;
        }

        private static void collectFixtures(Map<String, String> context, JsonNode fixtures) {
            fixtures.fields().forEachRemaining(entry -> {
                JsonNode value = entry.getValue();
                if (value.isTextual() || value.isNumber() || value.isBoolean()) {
                    context.put(entry.getKey(), value.asText());
                }
            });
        }

        private JsonNode resolveRequest(JsonNode request, Map<String, String> fixtures) {
            JsonNode value = request == null ? MAPPER.createObjectNode() : request;
            if (value.path("transport").isObject() && "http".equals(value.path("transport").path("type").asText())) {
                value = value.path("transport");
            }
            ObjectNode resolved = value.isObject() ? ((ObjectNode) value).deepCopy() : MAPPER.createObjectNode();
            for (String field : List.of("pathParameters", "query", "queryParameters", "body")) {
                if (resolved.has(field)) {
                    resolved.set(field, resolveValues(resolved.path(field), fixtures));
                }
            }
            Map<String, String> headerContext = new LinkedHashMap<>(fixtures);
            headerContext.putAll(values);
            ObjectNode headers = resolved.putObject("headers");
            requestHeaders(value.path("headers")).forEach((name, candidate) -> {
                String credential = resolve(candidate);
                headers.put(name, credential.equals(candidate) ? resolveString(candidate, headerContext) : credential);
            });
            return resolved;
        }

        private String resolve(String candidate) {
            for (Map.Entry<String, String> credential : values.entrySet()) {
                String placeholder = "${" + credential.getKey() + "}";
                if (candidate.equals(placeholder)) {
                    return credential.getValue();
                }
                if (candidate.equals("Bearer " + placeholder)) {
                    return bearer(credential.getValue());
                }
                if (candidate.equals("Basic " + placeholder)) {
                    return prefixed(credential.getValue(), "Basic");
                }
            }
            return candidate;
        }

        private void apply(JsonNode profile, Map<String, String> headers) {
            String kind = profile.path("kind").asText();
            if ("anonymous".equals(kind)) {
                return;
            }
            String header = "api_key".equals(kind) ? "X-API-Key" : "Authorization";
            if (headers.keySet().stream().anyMatch(name -> header.equalsIgnoreCase(name))) {
                return;
            }
            String credential = values.get(profile.path("credentialRef").asText());
            if ("bearer".equals(kind)) {
                headers.put("Authorization", bearer(credential));
            } else if ("basic".equals(kind)) {
                headers.put("Authorization", prefixed(credential, "Basic"));
            } else if ("api_key".equals(kind)) {
                headers.put("X-API-Key", credential);
            } else {
                headers.put("Authorization", credential);
            }
        }

        private static String bearer(String credential) {
            return prefixed(credential, "Bearer");
        }

        private static String prefixed(String credential, String scheme) {
            String prefix = scheme + " ";
            return credential.regionMatches(true, 0, prefix, 0, prefix.length()) ? credential : prefix + credential;
        }
    }

    /** Per-invocation request budget; no mutable execution state is shared between plans. */
    private static class RequestBudget {
        // The operation's five-minute bound and Transport's twenty-second request ceiling
        // remain enforced even when a persisted plan requests larger values.
        private static final int MAXIMUM_EXECUTION_MILLIS = 300_000;
        private static final int MAXIMUM_REQUEST_MILLIS = 20_000;
        private final long deadline;
        private final long intervalNanos;
        private final String reason;
        private long nextRequest;

        private RequestBudget(JsonNode contract) {
            JsonNode policy = contract.path("safetyPolicy");
            deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
                    Math.min(MAXIMUM_EXECUTION_MILLIS, policy.path("maxDurationMs").asLong(MAXIMUM_EXECUTION_MILLIS)));
            intervalNanos = policy.path("maxRequestsPerSecond").isIntegralNumber()
                    ? java.util.concurrent.TimeUnit.SECONDS.toNanos(1) / policy.path("maxRequestsPerSecond").asLong() : 0;
            reason = "blackbox".equals(contract.path("securityMode").asText())
                    ? "security_blackbox_duration_exceeded" : "security_sidecar_duration_exceeded";
        }

        private ExecuteTransportResult execute(TransportExecutionFeature transport, Map<String, Object> payload) {
            try {
                long delay = Math.min(nextRequest, deadline) - System.nanoTime();
                if (delay > 0) {
                    java.util.concurrent.TimeUnit.NANOSECONDS.sleep(delay);
                }
                long remaining = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remaining < 1) {
                    return expired();
                }
                payload.put("timeoutMs", Math.min(MAXIMUM_REQUEST_MILLIS, remaining));
                nextRequest = System.nanoTime() + intervalNanos;
                ExecuteTransportResult result = transport.execute(new ExecuteTransportRequest(TransportProtocol.HTTP, payload, true));
                return System.nanoTime() >= deadline || (remaining < MAXIMUM_REQUEST_MILLIS
                        && "transport_request_timeout".equals(result.reasonCode())) ? expired() : result;
            } catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
                return ExecuteTransportResult.blockedRuntime("security_execution_interrupted", "Security request interrupted", "http", 1);
            }
        }

        private ExecuteTransportResult expired() {
            return ExecuteTransportResult.blockedRuntime(reason, "Security request budget exhausted", "http", 1);
        }

        private boolean expiredNow() {
            return System.nanoTime() >= deadline;
        }

        private String reasonOr(String fallback) {
            return expiredNow() ? reason : fallback;
        }

        private Duration probeTimeout() {
            long remaining = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            return Duration.ofMillis(Math.max(1, Math.min(10_000, remaining)));
        }
    }

    private record SidecarCase(Map<String, Object> coverage, List<Map<String, Object>> findings) {
    }

    private record GeneratedRequest(URI uri, JsonNode request, boolean removeCredential) {
    }
}
