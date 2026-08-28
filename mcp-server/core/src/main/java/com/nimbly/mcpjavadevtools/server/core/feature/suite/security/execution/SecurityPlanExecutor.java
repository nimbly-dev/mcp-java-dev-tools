package com.nimbly.mcpjavadevtools.server.core.feature.suite.security.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.knowledge.SecurityKnowledgeCatalog;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.ProbeFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.reset.ProbeBatchResetRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.status.ProbeSingleStatusRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.status.ProbeStatusResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.waitforhit.ProbeWaitForHitRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.key.ProbeKeyBatchSelector;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.key.ProbeKeySelector;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.ProbeTargetSelector;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.model.result.SecuritySuiteResult;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.TransportExecutionFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportResult;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.TransportProtocol;
import java.net.URI;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.time.Duration;

/** Executes bounded Black-box HTTP baselines through the wrapped Transport Core Feature. */
public final class SecurityPlanExecutor {

    private static final ObjectMapper MAPPER = new ObjectMapper();
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
    public SecuritySuiteResult execute(JsonNode input) {
        JsonNode contract = input.path("contract").isObject() ? input.path("contract") : input;
        Validation validation = Validation.from(contract);
        if (validation.reasonCode() != null) {
            return blocked(validation.reasonCode(), validation.nextAction());
        }
        CredentialContext credentials = CredentialContext.from(input, contract);
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

    private SecuritySuiteResult runSidecar(JsonNode contract, URI baseUrl, CredentialContext credentials) {
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
        return sidecarResult(selected, cases, findings);
    }

    private SidecarCase executeSidecarCase(JsonNode contract, URI baseUrl, JsonNode attack, CredentialContext credentials) {
        JsonNode entrypoint = entrypoint(contract, attack.path("entrypointRef").asText());
        JsonNode profile = profile(contract, attack.path("authenticationProfileRef").asText());
        if (entrypoint == null || profile == null) {
            return sidecarBlocked(attack, "security_sidecar_reference_missing");
        }
        if (!"http".equals(entrypoint.path("transport").path("type").asText())) {
            return sidecarNotApplicable(attack, "security_sidecar_entrypoint_not_supported");
        }
        URI requestUri = uri(baseUrl, entrypoint.path("transport").path("path").asText());
        if (requestUri == null || !allowed(contract.path("targetBoundary"), requestUri)) {
            return sidecarBlocked(attack, "security_target_boundary_violation");
        }
        return executeSidecarRequests(contract, attack, entrypoint, requestUri, profile, credentials);
    }

    private SidecarCase executeSidecarRequests(
            JsonNode contract, JsonNode attack, JsonNode entrypoint, URI requestUri, JsonNode profile, CredentialContext credentials) {
        if (!resetTargets(contract, attack, entrypoint)) {
            return sidecarBlocked(attack, "security_sidecar_probe_reset_failed");
        }
        ExecuteTransportResult baseline = executeRequest(requestUri, entrypoint, attack.path("baseline"), profile, credentials);
        if (!matches(baseline, attack.path("baseline").path("expect"))) {
            return sidecarBlocked(attack, "security_sidecar_baseline_unexpected");
        }
        RuntimeEvidence baselineEvidence = evaluateRuntimeEvidence(
                contract, attack, entrypoint, attack.path("baseline").path("expect"));
        if (baselineEvidence.blocked()) {
            return sidecarBlocked(attack, baselineEvidence.reasonCode());
        }
        if (!resetTargets(contract, attack, entrypoint)) {
            return sidecarBlocked(attack, "security_sidecar_probe_reset_failed");
        }
        ExecuteTransportResult attackResult = executeRequest(requestUri, entrypoint, attack.path("attack"), profile, credentials);
        if (!transported(attackResult)) {
            return sidecarBlocked(attack, "security_sidecar_transport_blocked");
        }
        RuntimeEvidence evidence = evaluateRuntimeEvidence(contract, attack, entrypoint, attack.path("attack").path("expect"));
        if (evidence.blocked()) {
            return sidecarBlocked(attack, evidence.reasonCode());
        }
        return withHttpEvidence(classifySidecarCase(attack, attackResult, evidence), attack, baseline, attackResult);
    }

    private boolean resetTargets(JsonNode contract, JsonNode attack, JsonNode entrypoint) {
        for (JsonNode target : selectedTargets(contract, attack, entrypoint)) {
            var result = probe.execute(new ProbeBatchResetRequest(
                    targetSelector(target),
                    new ProbeKeyBatchSelector(List.of(target.path("strictLineKey").asText())), Duration.ofSeconds(10)));
            if (result.status() != com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResultStatus.SUCCESS) {
                return false;
            }
        }
        return true;
    }

    private RuntimeEvidence evaluateRuntimeEvidence(
            JsonNode contract, JsonNode attack, JsonNode entrypoint, JsonNode expectation) {
        Map<String, JsonNode> targets = targetsById(selectedTargets(contract, attack, entrypoint));
        if (!requiredTargetsHit(targets, expectation.path("mustHitRuntimeTargets"))) {
            return new RuntimeEvidence(true, "security_sidecar_required_line_not_hit", false, false);
        }
        boolean forbiddenHit = forbiddenTargetHit(targets, expectation.path("mustNotHitRuntimeTargets"));
        return new RuntimeEvidence(false, null, expectation.path("mustHitRuntimeTargets").size() > 0, forbiddenHit);
    }

    private boolean requiredTargetsHit(Map<String, JsonNode> targets, JsonNode required) {
        for (JsonNode targetId : required) {
            JsonNode target = targets.get(targetId.asText());
            if (target == null || !waitForHit(target)) {
                return false;
            }
        }
        return true;
    }

    private boolean waitForHit(JsonNode target) {
        var result = probe.execute(new ProbeWaitForHitRequest(
                targetSelector(target),
                new ProbeKeySelector(target.path("strictLineKey").asText(), null), Duration.ofSeconds(10),
                Duration.ofMillis(100), 100));
        return result.status() == com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResultStatus.SUCCESS;
    }

    private boolean forbiddenTargetHit(Map<String, JsonNode> targets, JsonNode forbidden) {
        for (JsonNode targetId : forbidden) {
            JsonNode target = targets.get(targetId.asText());
            if (target == null || targetHit(target)) {
                return true;
            }
        }
        return false;
    }

    private boolean targetHit(JsonNode target) {
        var result = probe.execute(new ProbeSingleStatusRequest(
                targetSelector(target),
                new ProbeKeySelector(target.path("strictLineKey").asText(), null), Duration.ofSeconds(10)));
        if (result.status() != com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResultStatus.SUCCESS) {
            return true;
        }
        return result.actionResult().filter(ProbeStatusResult.class::isInstance).map(ProbeStatusResult.class::cast)
                .map(status -> status.entries().stream().anyMatch(entry -> entry.lineHit())).orElse(true);
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
            JsonNode contract, URI baseUrl, SecurityKnowledgeCatalog.Selection selected, CredentialContext credentials) {
        List<Map<String, Object>> cases = new ArrayList<>();
        List<Map<String, Object>> findings = new ArrayList<>();
        for (JsonNode entrypoint : contract.path("entrypoints")) {
            for (JsonNode profile : contract.path("authenticationProfiles")) {
                Map<String, Object> outcome = baseline(contract, baseUrl, entrypoint, profile, credentials);
                cases.add(outcome);
                if ("blocked".equals(outcome.get("outcome"))) {
                    return incomplete(selected, cases, String.valueOf(outcome.get("reasonCode")));
                }
            }
        }
        for (JsonNode attack : contract.path("customCases")) {
            SidecarCase outcome = executeBlackboxCase(contract, baseUrl, attack, credentials);
            cases.add(outcome.coverage());
            findings.addAll(outcome.findings());
            if ("blocked".equals(outcome.coverage().get("outcome"))) {
                return incomplete(selected, cases, String.valueOf(outcome.coverage().get("reasonCode")));
            }
        }
        for (SecurityKnowledgeCatalog.GeneratedCase template : knowledge.generatedCases(selected)) {
            for (JsonNode entrypoint : contract.path("entrypoints")) {
                for (JsonNode profile : contract.path("authenticationProfiles")) {
                    SidecarCase outcome = executeGeneratedCase(contract, entrypoint, profile, baseUrl, template, credentials);
                    cases.add(outcome.coverage());
                    findings.addAll(outcome.findings());
                    if ("blocked".equals(outcome.coverage().get("outcome"))) {
                        return incomplete(selected, cases, String.valueOf(outcome.coverage().get("reasonCode")));
                    }
                }
            }
        }
        return SecuritySuiteResult.completed(Map.of("runStatus", findings.isEmpty() ? "pass" : "fail", "securityMode", "blackbox",
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
            CredentialContext credentials) {
        URI requestUri = uri(baseUrl, entrypoint.path("transport").path("path").asText());
        if (requestUri == null || !"http".equals(entrypoint.path("transport").path("type").asText())) {
            return generatedResult(entrypoint, template, "not_applicable", "security_generated_entrypoint_not_supported", null);
        }
        if (!applicable(contract, profile, template)) {
            return generatedResult(entrypoint, template, "not_applicable", "security_generated_rule_not_applicable", null);
        }
        GeneratedRequest mutation = generatedRequest(entrypoint, requestUri, contract, template);
        if (mutation == null) {
            return generatedResult(entrypoint, template, "not_applicable", "security_generated_mutation_target_missing", null);
        }
        ExecuteTransportResult baseline = executeRequest(requestUri, entrypoint, entrypoint.path("baseline"), profile, credentials);
        if (!allowedResponse(baseline)) {
            return generatedResult(entrypoint, template, "blocked", "security_generated_baseline_unexpected", baseline.statusCode());
        }
        ExecuteTransportResult response = executeRequest(
                mutation.uri(), entrypoint, mutation.request(), profile, credentials, !mutation.removeCredential());
        if (!transported(response)) {
            return generatedResult(entrypoint, template, "blocked", "security_generated_transport_blocked", response.statusCode());
        }
        if (expectedDeny(response, template)) {
            return generatedResult(entrypoint, template, "passed", "security_generated_mutation_denied", response.statusCode());
        }
        if (allowedResponse(response)) {
            return generatedFinding(entrypoint, template, baseline, response);
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

    private static GeneratedRequest generatedRequest(
            JsonNode entrypoint, URI uri, JsonNode contract, SecurityKnowledgeCatalog.GeneratedCase template) {
        ObjectNode request = entrypoint.path("baseline").isObject()
                ? ((ObjectNode) entrypoint.path("baseline")).deepCopy() : MAPPER.createObjectNode();
        String payload = payload(contract, template);
        if (payload == null && !template.removeCredential()) {
            return null;
        }
        return switch (template.mutationBoundary()) {
            case "query_parameter" -> queryMutation(uri, request, payload);
            case "header" -> headerMutation(uri, request, template.removeCredential());
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

    private static GeneratedRequest headerMutation(URI uri, ObjectNode request, boolean removeCredential) {
        if (!removeCredential) {
            return null;
        }
        ObjectNode headers = request.path("headers").isObject() ? (ObjectNode) request.path("headers") : request.putObject("headers");
        headers.remove("Authorization");
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

    private SidecarCase executeBlackboxCase(JsonNode contract, URI baseUrl, JsonNode attack, CredentialContext credentials) {
        JsonNode entrypoint = entrypoint(contract, attack.path("entrypointRef").asText());
        JsonNode profile = profile(contract, attack.path("authenticationProfileRef").asText());
        if (entrypoint == null || profile == null) {
            return sidecarBlocked(attack, "security_blackbox_reference_missing");
        }
        if (!"http".equals(entrypoint.path("transport").path("type").asText())) {
            return sidecarNotApplicable(attack, "security_blackbox_entrypoint_not_supported");
        }
        URI requestUri = uri(baseUrl, entrypoint.path("transport").path("path").asText());
        if (requestUri == null || !allowed(contract.path("targetBoundary"), requestUri)) {
            return sidecarBlocked(attack, "security_target_boundary_violation");
        }
        return executeBlackboxRequests(attack, entrypoint, requestUri, profile, credentials);
    }

    private SidecarCase executeBlackboxRequests(
            JsonNode attack, JsonNode entrypoint, URI requestUri, JsonNode profile, CredentialContext credentials) {
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
            JsonNode contract, URI baseUrl, JsonNode entrypoint, JsonNode profile, CredentialContext credentials) {
        JsonNode transportDefinition = entrypoint.path("transport");
        if (!"http".equals(transportDefinition.path("type").asText())) {
            return caseResult(entrypoint, "not_applicable", "security_blackbox_entrypoint_not_supported", null);
        }
        URI requestUri = uri(baseUrl, transportDefinition.path("path").asText());
        if (requestUri == null || !allowed(contract.path("targetBoundary"), requestUri)) {
            return caseResult(entrypoint, "blocked", "security_target_boundary_violation", null);
        }
        ExecuteTransportResult result = executeRequest(requestUri, entrypoint, null, profile, credentials);
        return transported(result) ? caseResult(entrypoint, "passed", "ok", result.statusCode())
                : caseResult(entrypoint, "blocked", result.reasonCode(), result.statusCode());
    }

    private ExecuteTransportResult executeRequest(
            URI requestUri, JsonNode entrypoint, JsonNode request, JsonNode profile, CredentialContext credentials) {
        return executeRequest(requestUri, entrypoint, request, profile, credentials, true);
    }

    private ExecuteTransportResult executeRequest(
            URI requestUri,
            JsonNode entrypoint,
            JsonNode request,
            JsonNode profile,
            CredentialContext credentials,
            boolean applyCredentials) {
        Map<String, Object> payload = new LinkedHashMap<>();
        JsonNode value = request == null ? com.fasterxml.jackson.databind.node.MissingNode.getInstance() : request;
        payload.put("url", withQuery(withPathParameters(requestUri, value), query(value)).toString());
        payload.put("method", entrypoint.path("transport").path("method").asText());
        Map<String, String> headers = requestHeaders(value.path("headers"));
        if (applyCredentials) {
            credentials.apply(profile, headers);
        }
        if (!headers.isEmpty()) {
            payload.put("headers", Map.copyOf(headers));
        }
        if (!value.path("body").isMissingNode() && !value.path("body").isNull()) {
            payload.put("body", value.path("body").toString());
        }
        return transport.execute(new ExecuteTransportRequest(TransportProtocol.HTTP, payload, true));
    }

    private static boolean expectedDeny(
            ExecuteTransportResult response, SecurityKnowledgeCatalog.GeneratedCase template) {
        return template.expectedDenyStatusCodes().contains(response.statusCode()) && deniedResponse(response);
    }

    private static SidecarCase generatedFinding(
            JsonNode entrypoint,
            SecurityKnowledgeCatalog.GeneratedCase template,
            ExecuteTransportResult baseline,
            ExecuteTransportResult attack) {
        String findingId = "finding-" + template.ruleId() + "-" + entrypoint.path("id").asText();
        Map<String, Object> finding = new LinkedHashMap<>();
        finding.put("id", findingId);
        finding.put("severity", template.severity());
        finding.put("category", template.category());
        finding.put("outcome", "confirmed");
        finding.put("proofClassification", "external");
        finding.put("title", template.title());
        finding.put("cwe", template.ruleId());
        SidecarCase outcome = generatedResult(entrypoint, template, "confirmed", "security_generated_mutation_allowed", attack.statusCode());
        Map<String, Object> coverage = new LinkedHashMap<>(outcome.coverage());
        coverage.put("findingIds", List.of(findingId));
        coverage.put("evidence", List.of(httpEvidence(findingId, "baseline", baseline), httpEvidence(findingId, "attack", attack)));
        return new SidecarCase(Map.copyOf(coverage), List.of(Map.copyOf(finding)));
    }

    private static JsonNode query(JsonNode request) {
        return request.path("query").isObject() ? request.path("query") : request.path("queryParameters");
    }

    private static URI withPathParameters(URI requestUri, JsonNode request) {
        JsonNode pathParameters = request.path("pathParameters");
        if (!pathParameters.isObject() || pathParameters.isEmpty()) {
            return requestUri;
        }
        String path = requestUri.getPath();
        var fields = pathParameters.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            path = path.replace("{" + entry.getKey() + "}", java.net.URLEncoder.encode(
                    entry.getValue().asText(), java.nio.charset.StandardCharsets.UTF_8));
        }
        try {
            return new URI(requestUri.getScheme(), requestUri.getAuthority(), path, requestUri.getQuery(), null);
        } catch (java.net.URISyntaxException exception) {
            return requestUri;
        }
    }

    private static URI withQuery(URI requestUri, JsonNode query) {
        if (!query.isObject() || query.isEmpty()) {
            return requestUri;
        }
        StringBuilder values = new StringBuilder(requestUri.getQuery() == null ? "" : requestUri.getQuery());
        query.fields().forEachRemaining(entry -> appendQuery(values, entry.getKey(), entry.getValue().asText()));
        try {
            return new URI(requestUri.getScheme(), requestUri.getAuthority(), requestUri.getPath(), values.toString(), null);
        } catch (java.net.URISyntaxException exception) {
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
        String caseId = attack.path("id").asText();
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
            SecurityKnowledgeCatalog.Selection selected, List<Map<String, Object>> cases, List<Map<String, Object>> findings) {
        long blocked = cases.stream().filter(value -> "blocked".equals(value.get("outcome"))).count();
        Map<String, Object> coverage = Map.of("plannedCount", cases.size(), "executedCount", cases.size(),
                "blockedCount", blocked, "complete", blocked == 0);
        if (blocked > 0) {
            return new SecuritySuiteResult("blocked", "security_sidecar_coverage_incomplete",
                    "resolve blocked Security cases and rerun", Map.of(), Map.of("securityMode", "sidecar_assisted",
                            "knowledgeSnapshot", selected.snapshot().digest(), "coverage", coverage, "cases", List.copyOf(cases),
                            "findings", List.copyOf(findings)));
        }
        return SecuritySuiteResult.completed(Map.of("runStatus", findings.isEmpty() ? "pass" : "fail", "securityMode",
                "sidecar_assisted", "knowledgeSnapshot", Map.of("packs", selected.snapshot().packs(), "digest",
                        selected.snapshot().digest()), "coverage", coverage, "cases", List.copyOf(cases), "findings", List.copyOf(findings)));
    }

    private static List<String> packRefs(JsonNode contract) {
        List<String> values = new ArrayList<>();
        contract.path("securityKnowledge").path("packRefs").forEach(value -> values.add(value.asText()));
        return List.copyOf(values);
    }

    private static URI uri(URI baseUrl, String path) {
        return path == null || path.isBlank() || !path.startsWith("/") ? null : baseUrl.resolve(path);
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
            String reasonCode) {
        return new SecuritySuiteResult("blocked", reasonCode, "resolve the blocked Security case and rerun", Map.of(), Map.of(
                "securityMode", "blackbox", "knowledgeSnapshot", selection.snapshot().digest(), "cases", List.copyOf(cases),
                "coverage", Map.of("plannedCount", cases.size(), "complete", false)));
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
            if (!contract.path("entrypoints").isArray() || contract.path("entrypoints").isEmpty()) {
                return invalid("security_contract_entrypoints_invalid", "provide at least one declared entrypoint");
            }
            if (!contract.path("authenticationProfiles").isArray() || contract.path("authenticationProfiles").isEmpty()) {
                return invalid("security_contract_authentication_profiles_invalid", "provide authenticationProfiles");
            }
            URI baseUrl = baseUrl(contract.path("targetBoundary"));
            return baseUrl == null ? invalid("security_contract_target_boundary_invalid", "provide a bounded local-ci baseUrl")
                    : new Validation(null, null, mode, baseUrl);
        }

        private static Validation sidecar(JsonNode contract) {
            JsonNode attacks = contract.path("attackProfiles");
            if (!attacks.isArray() || attacks.isEmpty()) {
                return invalid("security_contract_attack_profiles_invalid", "provide non-empty sidecar attackProfiles");
            }
            JsonNode targets = contract.path("runtimeTargets");
            if (!targets.isArray() || targets.isEmpty()) {
                return invalid("security_contract_sidecar_runtime_target_required", "provide non-empty runtimeTargets");
            }
            for (JsonNode target : targets) {
                boolean hasProbe = target.path("probeId").isTextual() || target.path("probeBaseUrl").isTextual();
                if (!hasProbe || !target.path("strictLineKey").isTextual()) {
                    return invalid("security_contract_runtime_targets_invalid",
                            "provide probeId or probeBaseUrl and strictLineKey for every runtimeTarget");
                }
            }
            return new Validation(null, null, "sidecar_assisted", null);
        }

        private static URI baseUrl(JsonNode boundary) {
            try {
                URI value = URI.create(boundary.path("baseUrl").asText());
                return "local-ci".equals(boundary.path("environment").asText()) && value.isAbsolute() ? value : null;
            } catch (IllegalArgumentException exception) {
                return null;
            }
        }

        private static Validation invalid(String reasonCode, String nextAction) {
            return new Validation(reasonCode, nextAction, null, null);
        }
    }

    private record RuntimeEvidence(boolean blocked, String reasonCode, boolean requiredHit, boolean forbiddenHit) {
    }

    private record CredentialContext(Map<String, String> values, String reasonCode) {
        private static CredentialContext from(JsonNode input, JsonNode contract) {
            Map<String, String> values = new LinkedHashMap<>();
            JsonNode bindings = input.path("credentialBindings");
            Map<String, String> environment = environment(input.path("credentialSource"));
            SecurityCredentialRefresh.Result refresh = new SecurityCredentialRefresh()
                    .refresh(input.path("credentialSource"), environment);
            if (!refresh.successful()) {
                return new CredentialContext(Map.of(), refresh.reasonCode());
            }
            environment = environment(input.path("credentialSource"));
            for (JsonNode profile : contract.path("authenticationProfiles")) {
                if ("anonymous".equals(profile.path("kind").asText())) {
                    continue;
                }
                String ref = profile.path("credentialRef").asText();
                String environmentKey = bindings.path(ref).asText();
                String value = environmentKey.isBlank() ? null : environment.get(environmentKey);
                if (ref.isBlank() || !bindings.isObject() || value == null || value.isBlank()) {
                    return new CredentialContext(Map.of(), "security_runtime_credentials_unavailable");
                }
                values.put(ref, value);
            }
            return new CredentialContext(Map.copyOf(values), null);
        }

        private static Map<String, String> environment(JsonNode source) {
            Map<String, String> values = new LinkedHashMap<>(System.getenv());
            String workspaceRoot = source.path("workspaceRoot").asText();
            String envFile = source.path("envFile").asText();
            if (workspaceRoot.isBlank() || envFile.isBlank()) {
                return Map.copyOf(values);
            }
            try {
                Path file = Path.of(envFile);
                if (!file.isAbsolute()) {
                    file = Path.of(workspaceRoot).resolve(file).normalize();
                }
                for (String line : Files.readAllLines(file)) {
                    addEnvironmentValue(values, line);
                }
            } catch (IOException | SecurityException | IllegalArgumentException ignored) {
                // Missing or unreadable optional dotenv sources are handled by the declared-binding check.
            }
            return Map.copyOf(values);
        }

        private static void addEnvironmentValue(Map<String, String> values, String rawLine) {
            String line = rawLine.trim();
            int separator = line.indexOf('=');
            if (line.isEmpty() || line.startsWith("#") || separator < 1) {
                return;
            }
            String key = line.substring(0, separator).trim();
            String value = line.substring(separator + 1).trim();
            if (key.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                values.put(key, unquote(value));
            }
        }

        private static String unquote(String value) {
            return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                    ? value.substring(1, value.length() - 1) : value;
        }

        private void apply(JsonNode profile, Map<String, String> headers) {
            String kind = profile.path("kind").asText();
            if ("anonymous".equals(kind)) {
                return;
            }
            String credential = values.get(profile.path("credentialRef").asText());
            if ("bearer".equals(kind)) {
                headers.putIfAbsent("Authorization", credential.startsWith("Bearer ") ? credential : "Bearer " + credential);
            } else if ("basic".equals(kind)) {
                headers.putIfAbsent("Authorization", credential.startsWith("Basic ") ? credential : "Basic " + credential);
            } else if ("api_key".equals(kind)) {
                headers.putIfAbsent("X-API-Key", credential);
            } else {
                headers.putIfAbsent("Authorization", credential);
            }
        }
    }

    private record SidecarCase(Map<String, Object> coverage, List<Map<String, Object>> findings) {
    }

    private record GeneratedRequest(URI uri, JsonNode request, boolean removeCredential) {
    }
}
