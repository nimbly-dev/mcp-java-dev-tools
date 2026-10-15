package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** #649 Failure Lens, Bug Drill and proposal/report consumer operation sequences. */
@Tag("failure-analysis")
@Timeout(180)
class McpServerFailureAnalysisIT extends McpServerStdioSupport {
    @ParameterizedTest
    @ValueSource(strings = {"guided", "hands_off"})
    @Tag("failure-smoke")
    @Tag("skill-workflows")
    void realTraceTargetTriggerCaptureAndProposalEvidence(String mode) throws Exception {
        Path original = repositoryRoot().resolve(JvmProbeScenario.SOURCE
                + FailureAnalysisScenario.SERVICE.replace('.', '/') + ".java");
        String sourceBefore = Files.readString(original);
        try (FailureAnalysisScenario s = FailureAnalysisScenario.start()) {
            s.attachAndRecord();
            JsonNode analyzed = s.result("analyze_trace", s.analysis(mode));
            assertAnalysis(analyzed, s);
            assertThat(analyzed.path("investigation")).isEqualTo(JSON.valueToTree(FailureAnalysisScenario.investigation(mode)));
            assertTargetAndRecipe(s, analyzed);
            s.trigger();
            Map<String, Object> input = s.verification();
            input.put("investigation", FailureAnalysisScenario.investigation(mode));
            JsonNode reproduced = s.result("verify_reproduction", input);
            assertThat(reproduced.path("outcome").asText()).isEqualTo("REPRODUCED");
            assertThat(reproduced.path("reasonCode").asText()).isEqualTo("ok");
            assertThat(reproduced.path("diagnosisClaimed").asBoolean()).isTrue();
            assertThat(reproduced.path("attemptEvidence").path("sidecarOutcome").asText()).isEqualTo("matched");
            assertThat(reproduced.path("attemptEvidence").path("captureId"))
                    .isEqualTo(s.capture.path("captureId"));
            assertThat(reproduced.path("lineHit").path("strictLineKey").asText()).isEqualTo(s.key);
            assertThat(reproduced.path("lineHit").path("hitCount").asLong()).isEqualTo(s.line.path("hitCount").asLong());
            assertThat(reproduced.path("observedFingerprint").path("nearestApplicationMethodKey").asText())
                    .isEqualTo(FailureAnalysisScenario.METHOD);
            assertThat(reproduced.path("cleanupStatus").asText()).isEqualTo("external_workflow_owned");
            assertLocalReport(s, reproduced);
            s.cleanup();
            assertThat(s.runtime.workspace.resolve(".mcpjvm")).doesNotExist();
            assertThat(Files.readString(original)).as("Bug Fix consumer remains proposal-only").isEqualTo(sourceBefore);
            JavaItScenario.observe("failure_analysis.verify_reproduction", "ASSERTED",
                    "real_trace_route_trigger_capture_proposal_report_cleanup_" + mode);
        }
    }

    private void assertAnalysis(JsonNode analyzed, FailureAnalysisScenario s) {
        assertThat(analyzed.path("outcome").asText()).isEqualTo("ANALYZED");
        assertThat(analyzed.path("reasonCode").asText()).isEqualTo("ok");
        assertThat(analyzed.path("diagnosisClaimed").asBoolean()).isFalse();
        JsonNode fingerprint = analyzed.path("fingerprint");
        assertThat(fingerprint.path("complete").asBoolean()).isTrue();
        assertThat(fingerprint.path("exceptionType").asText()).isEqualTo("java.lang.IllegalStateException");
        assertThat(fingerprint.path("rootCauseType")).isEqualTo(fingerprint.path("exceptionType"));
        assertThat(fingerprint.path("nearestApplicationMethodKey").asText()).isEqualTo(FailureAnalysisScenario.METHOD);
        JsonNode frame = fingerprint.path("nearestApplicationFrame");
        assertThat(frame.path("lineNumber").asInt()).isEqualTo(Integer.parseInt(s.key.substring(s.key.lastIndexOf(':') + 1)));
        assertThat(frame.path("ownership").asText()).isEqualTo("application");
        assertThat(frame.path("sourceFile").asText()).isEqualTo("TagLifecycleService.java");
        assertThat(frame.path("methodDescriptor").asText()).isNotBlank();
        assertThat(frame.path("codeSource").asText()).doesNotContain(repositoryRoot().toString());
        assertThat(analyzed.path("investigationCandidates").size()).isBetween(1, 2);
        assertThat(analyzed.path("exceptionSections")).isNotEmpty();
    }

    private void assertTargetAndRecipe(FailureAnalysisScenario s, JsonNode analyzed) throws Exception {
        JsonNode candidate = analyzed.path("investigationCandidates").get(0);
        assertThat(candidate.path("className").asText()).isEqualTo(FailureAnalysisScenario.SERVICE);
        Map<String, Object> selection = s.routes.arguments(candidate.path("className").asText());
        selection.putAll(Map.of("methodHint", candidate.path("methodName").asText(),
                "lineHint", candidate.path("lineNumber").asInt()));
        JsonNode target = s.routes.result("infer_target", selection);
        assertThat(target.path("status").asText()).isEqualTo("ok");
        assertThat(target.path("actionResult").path("candidates")).hasSize(1);
        assertThat(target.path("actionResult").path("candidates").get(0).path("key").asText())
                .isEqualTo(FailureAnalysisScenario.METHOD);
        JsonNode recipe = s.routes.recipe(s.routes.recipeArguments(RouteSynthesisScenario.TAGS, "deleteTag"));
        assertThat(recipe.path("requestCandidates")).hasSize(1);
        assertThat(recipe.path("requestCandidates").get(0).path("method").asText()).isEqualTo("DELETE");
        assertThat(recipe.path("requestCandidates").get(0).path("path").asText())
                .isEqualTo("/api/v2/tenant/{tenantId}/tags/{tagName}");
    }

    private void assertLocalReport(FailureAnalysisScenario s, JsonNode result) throws Exception {
        // Test evidence, not a new product Artifact or an automated source fix/publication.
        Map<String, Object> issueInput = proposalInputs();
        assertProposalInputContract(JSON.valueToTree(issueInput));
        Map<String, Object> report = Map.of("classification", "proposed", "applied", false,
                "request", Map.of("method", "DELETE", "path", "/api/v2/tenant/{tenantId}/tags/{tagName}"),
                "observedStatus", 500, "issueInput", issueInput,
                "exceptionType", result.path("observedFingerprint").path("exceptionType").asText(),
                "lineHit", result.path("lineHit"), "evidenceClass", "live", "proposal", proposal());
        Path evidence = s.runtime.workspace.resolve("investigation-input.json");
        JSON.writerWithDefaultPrettyPrinter().writeValue(evidence.toFile(), report);
        JsonNode persisted = JSON.readTree(evidence.toFile());
        assertThat(persisted.path("observedStatus").asInt()).isEqualTo(500);
        assertThat(persisted.path("applied").asBoolean()).isFalse();
        assertProposalInputContract(persisted.path("issueInput"));
        assertThat(persisted.path("proposal").path("reasonCode").asText()).isEqualTo("bug_fix_proposal_ready");
        assertThat(persisted.path("proposal").path("sourceArea").asText()).isEqualTo(FailureAnalysisScenario.METHOD);
        Path output = s.runtime.workspace.resolve("local-issue-report.md");
        Files.writeString(output, issueReport(persisted));
        assertThat(Files.readString(output)).contains("Steps to Reproduce", "DELETE", "500", "java.lang.IllegalStateException")
                .contains((String) issueInput.get("expectedBehavior"))
                .doesNotContain("alice-token", "Bearer ", "com.example.social", repositoryRoot().toString(), "http://127.0.0.1");
        JavaItScenario.observe("failure_analysis.verify_reproduction", "ASSERTED", "sanitized_local_report_repro_fields_no_apply");
    }

    private Map<String, Object> proposalInputs() {
        // Explicit controlled test input, not inferred product intent or a downstream correctness claim.
        return Map.of("issueContext", "Deleting a tag returns HTTP 500 and prevents the caller completing deletion.",
                "expectedBehavior", "A rejected tag DELETE should return a deliberate HTTP 405 instead of an unclassified HTTP 500.",
                "behavioralReproduction", List.of(
                        "Start the owned post-service fixture and attach its Sidecar.",
                        "Reset the selected Strict Line Key and begin the bounded fresh-hit wait.",
                        "DELETE /api/v2/tenant/failure-it/tags/controlled; observe HTTP 500.",
                        "Read status and the supplied capture; compare the exception fingerprint."));
    }

    private void assertProposalInputContract(JsonNode input) {
        assertThat(input.path("issueContext").asText()).as("bug_fix_input_required").isNotBlank();
        assertThat(input.path("expectedBehavior").asText()).as("bug_fix_expected_behavior_required")
                .isNotBlank().doesNotContain("Not explicitly confirmed", "unknown");
        assertThat(input.path("behavioralReproduction").isArray()).isTrue();
        assertThat(input.path("behavioralReproduction")).as("bug_fix_reproduction_required")
                .hasSize(4).allSatisfy(step -> assertThat(step.asText()).isNotBlank());
    }

    private Map<String, Object> proposal() {
        return Map.of("reasonCode", "bug_fix_proposal_ready", "sourceArea", FailureAnalysisScenario.METHOD,
                "change", "Proposed, not applied: represent the controlled rejection with a typed failure and map it to HTTP 405 at the API boundary.",
                "evidenceBoundary", "Live: HTTP 500, strict hit and matching exception. Inferred: generic exception causes the API failure. The fixture simulates downstream rejection; no real downstream call or deletion is proven.",
                "compatibilityAndRollback", "Planned: preserve unrelated error handling; rollback by reverting the rejection mapping.",
                "focusedValidation", "Planned: repeat the same rejected DELETE and assert HTTP 405; check unrelated failures retain their existing status.",
                "unknowns", "Actual downstream behavior and successful deletion remain unproven; the expectation is supplied by this controlled scenario.");
    }

    private String issueReport(JsonNode persisted) {
        return """
                Issue Title
                Controlled delete fails with a downstream method error

                Issue Description
                A DELETE request reaches the service and returns HTTP %s.

                Observed Behavior
                %s at com.example.service.TagLifecycleService#deleteTag.

                Expected Behavior
                %s

                Steps to Reproduce
                1. Start the owned post-service fixture and attach its Sidecar.
                2. DELETE /api/v2/tenant/{tenantId}/tags/{tagName} with local fixture values.
                3. Observe HTTP %s.

                Supporting Evidence
                Strict Line Hit count %s; exception fingerprint matched the actual capture.

                Impact
                Impact not fully established in session

                Sanitization Note
                Class names, package names, enterprise identifiers, hosts, and credentials were anonymized or redacted.
                """.formatted(persisted.path("observedStatus"), persisted.path("exceptionType").asText(),
                        persisted.path("issueInput").path("expectedBehavior").asText(),
                        persisted.path("observedStatus"), persisted.path("lineHit").path("hitCount"));
    }

    @Test
    void actualCaptureMismatchNoExceptionMissingCaptureAndIncompleteTrace() throws Exception {
        try (FailureAnalysisScenario s = FailureAnalysisScenario.start()) {
            s.attachAndRecord();
            assertAnalysis(s.result("analyze_trace", s.analysis(null)), s);
            assertSensitiveExpectedFingerprint(s);
            for (var mismatch : Map.of("exceptionType", "different_exception", "rootCauseType", "different_root_cause",
                    "nearestApplicationMethodKey", "different_application_frame").entrySet()) {
                Map<String, Object> expected = new LinkedHashMap<>(FailureAnalysisScenario.expected());
                expected.put(mismatch.getKey(), "example.Different#method");
                Map<String, Object> input = s.verification();
                input.put("expectedFingerprint", expected);
                assertNoDiagnosis(s.result("verify_reproduction", input), "NOT_REPRODUCED", mismatch.getValue());
            }
            s.runtime.hit(s.runtime.postKey(), "/api/v1/posts/101");
            JsonNode normal = s.runtime.status(s.runtime.postKey());
            Map<String, Object> input = s.verification();
            input.put("captureId", normal.path("capturePreview").path("captureId").asText());
            input.put("lineHit", Map.of("strictLineKey", s.runtime.postKey(), "hitCount", normal.path("hitCount").asLong()));
            assertNoDiagnosis(s.result("verify_reproduction", input), "NOT_REPRODUCED", "target_reached_no_exception");
            input.put("captureId", "explicitly-absent-capture");
            assertNoDiagnosis(s.result("verify_reproduction", input), "INCONCLUSIVE", "capture_not_found");
            input.put("captureId", "password=649-capture-secret-sentinel");
            JsonNode missingSensitive = s.result("verify_reproduction", input);
            assertNoDiagnosis(missingSensitive, "INCONCLUSIVE", "capture_not_found");
            assertThat(missingSensitive.toString()).doesNotContain("649-capture-secret-sentinel");
            for (String trace : List.of("not a Java trace", s.trace.replaceAll("\\.java:[0-9]+", ".java"),
                    s.trace + "\t... 10 more\n")) {
                Map<String, Object> analysis = s.analysis("guided");
                analysis.put("trace", trace);
                JsonNode incomplete = s.result("analyze_trace", analysis);
                assertNoDiagnosis(incomplete, "INCONCLUSIVE", "failure_fingerprint_incomplete");
                assertThat(incomplete.path("incompleteReasons")).isNotEmpty();
            }
            s.cleanup();
            JavaItScenario.observe("failure_analysis.verify_reproduction", "ASSERTED", "three_mismatches_normal_missing_capture_incomplete_trace");
        }
    }

    static void assertNoDiagnosis(JsonNode result, String outcome, String reason) {
        assertThat(result.path("outcome").asText()).as(result.toPrettyString()).isEqualTo(outcome);
        assertThat(result.path("reasonCode").asText()).isEqualTo(reason);
        assertThat(result.path("diagnosisClaimed").asBoolean()).isFalse();
    }

    private void assertSensitiveExpectedFingerprint(FailureAnalysisScenario s) throws Exception {
        for (String field : List.of("exceptionType", "rootCauseType", "nearestApplicationMethodKey")) {
            Map<String, Object> expected = new LinkedHashMap<>(FailureAnalysisScenario.expected());
            expected.put(field, "password=649-expected-secret-sentinel");
            Map<String, Object> input = s.verification();
            input.put("expectedFingerprint", expected);
            JsonNode result = s.result("verify_reproduction", input);
            assertThat(result.path("outcome").asText()).isEqualTo("NOT_REPRODUCED");
            assertThat(result.toString()).doesNotContain("649-expected-secret-sentinel");
        }
    }

    @Test
    void pastedCausalSectionsAndConfiguredEvidenceBounds() throws Exception {
        try (FailureAnalysisScenario s = FailureAnalysisScenario.start()) {
            s.attachAndRecord();
            Map<String, Object> input = s.analysis("guided");
            // Pasted-trace parser variants based on actual frames; no invented successful captures.
            input.put("trace", s.trace + "Caused by: java.lang.IllegalArgumentException: supplied cause\n");
            JsonNode causal = s.result("analyze_trace", input);
            assertThat(causal.path("fingerprint").path("rootCauseType").asText()).isEqualTo("java.lang.IllegalArgumentException");
            assertThat(causal.path("exceptionSections")).hasSize(2);
            input.put("trace", s.trace + "Suppressed: java.lang.IllegalArgumentException: supplied suppressed\n");
            JsonNode suppressed = s.result("analyze_trace", input);
            assertThat(suppressed.path("fingerprint").path("rootCauseType").asText()).isEqualTo("java.lang.IllegalStateException");
            assertThat(suppressed.path("exceptionSections").get(1).path("suppressed").asBoolean()).isTrue();
            assertConfiguredLimits(s, input);
            s.runtime.success("reset", Map.of("key", s.key));
            assertThat(s.runtime.probe("wait_for_hit", Map.of("key", s.key, "timeoutMs", 200,
                    "pollIntervalMs", 25, "maxRetries", 1)).path("reasonCode").asText()).isEqualTo("WAIT_TIMEOUT");
            assertThat(s.runtime.status(s.key).path("hitCount").asLong()).isZero();
            s.cleanup();
            JavaItScenario.observe("failure_analysis.analyze_trace", "ASSERTED", "causal_suppressed_bounds_no_trigger_timeout_cleanup");
        }
    }

    private void assertConfiguredLimits(FailureAnalysisScenario s, Map<String, Object> input) throws Exception {
        try (McpServerProcess bounded = JvmProbeScenario.startServer(s.runtime.workspace,
                "--mcpjvm.failure-analysis.endpoint.maximum-frames=1",
                "--mcpjvm.failure-analysis.endpoint.maximum-sections=1",
                "--mcpjvm.failure-analysis.endpoint.maximum-string-length=48")) {
            initialize(bounded);
            JsonNode result = analyzeWith(bounded, input);
            assertThat(result.path("outcome").asText()).isEqualTo("ANALYZED");
            assertThat(result.path("investigationCandidates")).hasSize(1);
            assertThat(result.path("exceptionSections")).hasSize(1);
            assertThat(result.path("fingerprint").path("normalizedMessage").asText()).hasSize(48);
        }
        try (McpServerProcess bounded = JvmProbeScenario.startServer(s.runtime.workspace,
                "--mcpjvm.failure-analysis.endpoint.maximum-trace-characters=8")) {
            initialize(bounded);
            assertNoDiagnosis(analyzeWith(bounded, s.analysis(null)), "INCONCLUSIVE", "failure_analysis_request_invalid");
        }
        try (McpServerProcess bounded = JvmProbeScenario.startServer(s.runtime.workspace,
                "--mcpjvm.failure-analysis.endpoint.maximum-response-payload-bytes=64")) {
            initialize(bounded);
            assertThat(analyzeWith(bounded, s.analysis(null)).path("outcome").asText()).isEqualTo("BLOCKED_SIDECAR_UNAVAILABLE");
        }
    }

    private JsonNode analyzeWith(McpServerProcess server, Map<String, Object> input) throws Exception {
        server.send(executeRequest(901, "failure_analysis.analyze_trace", input, false));
        JsonNode payload = toolPayload(server.responseFor(901));
        assertThat(payload.path("status").asText()).isEqualTo("succeeded");
        return payload.path("result");
    }
}
