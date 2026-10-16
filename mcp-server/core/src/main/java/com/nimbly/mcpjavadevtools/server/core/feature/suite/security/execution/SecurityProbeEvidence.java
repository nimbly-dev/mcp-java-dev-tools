package com.nimbly.mcpjavadevtools.server.core.feature.suite.security.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.ProbeFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.reset.ProbeBatchResetRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.status.ProbeSingleStatusRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.status.ProbeStatusEntry;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.status.ProbeStatusResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.key.ProbeKeyBatchSelector;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.key.ProbeKeySelector;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.ProbeTargetSelector;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.model.execution.SecurityRuntimeEvidence;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Collects fresh required and forbidden Probe observations with runtime identity. */
class SecurityProbeEvidence {

    private static final Duration RUNTIME_EVIDENCE_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration RUNTIME_EVIDENCE_POLL_INTERVAL = Duration.ofMillis(100);
    private final ProbeFeature probe;

    SecurityProbeEvidence(ProbeFeature probe) {
        this.probe = probe;
    }

    boolean resetTargets(JsonNode contract, JsonNode attack, JsonNode entrypoint, SecurityRequestBudget budget) {
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

    SecurityRuntimeEvidence evaluateRuntimeEvidence(
            JsonNode contract, JsonNode attack, JsonNode entrypoint, JsonNode expectation, long phaseStarted, SecurityRequestBudget budget) {
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
            if (containsTarget(expectation.path("mustHitRuntimeTargets"), target.getKey()) && !freshHit) {
                return runtimeBlocked("security_sidecar_required_line_not_hit");
            }
            forbiddenHit |= containsTarget(expectation.path("mustNotHitRuntimeTargets"), target.getKey()) && freshHit;
            observations.add(runtimeObservation(target.getKey(), status, freshHit));
        }
        for (JsonNode forbidden : expectation.path("mustNotHitRuntimeTargets")) {
            if (!targets.containsKey(forbidden.asText())) {
                return runtimeBlocked("security_sidecar_runtime_target_ambiguous");
            }
        }
        return new SecurityRuntimeEvidence(false, null, expectation.path("mustHitRuntimeTargets").size() > 0,
                forbiddenHit, List.copyOf(observations));
    }

    private ProbeStatusEntry observedStatus(JsonNode target, SecurityRequestBudget budget) {
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

    private static SecurityRuntimeEvidence runtimeBlocked(String reason) {
        return new SecurityRuntimeEvidence(true, reason, false, false, List.of());
    }

    static boolean sameRuntime(SecurityRuntimeEvidence baseline, SecurityRuntimeEvidence attack) {
        return baseline.observations().stream().allMatch(before -> attack.observations().stream().anyMatch(after ->
                before.get("targetId").equals(after.get("targetId"))
                        && before.get("runtimeInstanceId").equals(after.get("runtimeInstanceId"))));
    }

    private boolean requiredTargetsHit(Map<String, JsonNode> targets, JsonNode required, SecurityRequestBudget budget) {
        for (JsonNode targetId : required) {
            JsonNode target = targets.get(targetId.asText());
            if (target == null || !awaitObservedTargetHit(target, budget)) {
                return false;
            }
        }
        return true;
    }

    private boolean awaitObservedTargetHit(JsonNode target, SecurityRequestBudget budget) {
        long deadline = Math.min(budget.deadline(), System.nanoTime() + RUNTIME_EVIDENCE_TIMEOUT.toNanos());
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

    private ProbeStatusResult targetStatus(JsonNode target, SecurityRequestBudget budget) {
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

    static List<JsonNode> selectedTargets(JsonNode contract, JsonNode attack, JsonNode entrypoint) {
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

    private static boolean containsTarget(JsonNode values, String expected) {
        for (JsonNode value : values) {
            if (expected.equals(value.asText())) {
                return true;
            }
        }
        return false;
    }
}
