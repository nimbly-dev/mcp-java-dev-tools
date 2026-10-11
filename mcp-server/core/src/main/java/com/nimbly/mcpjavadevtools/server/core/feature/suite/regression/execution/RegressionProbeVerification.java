package com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.ProbeFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.reset.ProbeBatchResetRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.status.ProbeBatchStatusRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.status.ProbeStatusResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.key.ProbeKeyBatchSelector;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResultStatus;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.ProbeTargetSelector;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.discovery.FileSystemJavaSourceDiscovery;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.discovery.JavaSourceFile;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.discovery.JavaSourceMethod;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.workspace.RouteSynthesisWorkspaceSnapshot;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Executes Regression-owned Strict Line reset and post-trigger Line Hit verification. */
final class RegressionProbeVerification {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final ProbeFeature probe;

    RegressionProbeVerification(ProbeFeature probe) {
        this.probe = probe;
    }

    Resolution resolve(JsonNode input) {
        if (!enabled(input) || input.path("metadata").path("execution")
                .path("pinStrictProbeKey").asBoolean(false)) {
            return Resolution.success(input);
        }
        com.fasterxml.jackson.databind.node.ObjectNode resolved = input.deepCopy();
        for (JsonNode target : resolved.path("contract").path("targets")) {
            JsonNode runtime = target.path("runtimeVerification");
            if (text(runtime.path("strictProbeKey")) != null) {
                continue;
            }
            SourceResolution source = resolveSource(resolved, target);
            if (!source.success()) {
                return Resolution.failure(source.reasonCode(), source.details());
            }
            ((com.fasterxml.jackson.databind.node.ObjectNode) target)
                    .withObject("runtimeVerification")
                    .put("strictProbeKey", source.strictLineKey());
        }
        return Resolution.success(resolved);
    }

    Result prepare(JsonNode input) {
        if (!enabled(input)) {
            return Result.notConfigured();
        }
        if (probe == null) {
            return Result.failure("probe_verification_unavailable", Map.of());
        }
        List<Target> targets = targets(input);
        if (targets.isEmpty()) {
            return Result.failure("probe_verification_target_missing", Map.of());
        }
        for (Target target : targets) {
            ProbeResult reset = probe.execute(new ProbeBatchResetRequest(
                    target.selector(), new ProbeKeyBatchSelector(List.of(target.strictLineKey())), TIMEOUT));
            if (reset.status() != ProbeResultStatus.SUCCESS) {
                return Result.failure("probe_verification_reset_failed", Map.of(
                        "strictLineKey", target.strictLineKey(),
                        "probeReasonCode", reset.reasonCode().name()));
            }
        }
        return Result.success(Map.of("status", "prepared", "targetCount", targets.size()));
    }

    Result verify(JsonNode input) {
        if (!enabled(input)) {
            return Result.notConfigured();
        }
        if (probe == null) {
            return Result.failure("probe_verification_unavailable", Map.of());
        }
        List<Map<String, Object>> evidence = new ArrayList<>();
        for (Target target : targets(input)) {
            ProbeResult result = probe.execute(new ProbeBatchStatusRequest(
                    target.selector(), new ProbeKeyBatchSelector(List.of(target.strictLineKey())), TIMEOUT));
            if (result.status() != ProbeResultStatus.SUCCESS
                    || result.actionResult().filter(ProbeStatusResult.class::isInstance).isEmpty()) {
                return Result.failure("probe_verification_status_failed", Map.of(
                        "strictLineKey", target.strictLineKey(),
                        "probeReasonCode", result.reasonCode().name()));
            }
            ProbeStatusResult status = (ProbeStatusResult) result.actionResult().orElseThrow();
            if (status.entries().size() != 1
                    || !target.strictLineKey().equals(status.entries().getFirst().requestedKey())
                    || !status.entries().getFirst().lineHit()) {
                return Result.failure("probe_verification_line_not_hit", Map.of(
                        "strictLineKey", target.strictLineKey()));
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("strictLineKey", target.strictLineKey());
            row.put("lineHit", true);
            if (target.selector().probeId() != null) {
                row.put("probeId", target.selector().probeId());
            }
            evidence.add(Map.copyOf(row));
        }
        return Result.success(Map.of("status", "pass", "lineHits", List.copyOf(evidence)));
    }

    private boolean enabled(JsonNode input) {
        return input.path("metadata").path("execution").path("probeVerification").asBoolean(false);
    }

    private List<Target> targets(JsonNode input) {
        List<Target> targets = new ArrayList<>();
        for (JsonNode target : input.path("contract").path("targets")) {
            JsonNode runtime = target.path("runtimeVerification");
            String key = text(runtime.path("strictProbeKey"));
            if (key == null) {
                continue;
            }
            String probeId = text(runtime.path("probeId"));
            String baseUrl = text(runtime.path("probeBaseUrl"));
            if (baseUrl == null) {
                baseUrl = text(input.path("probeBaseUrl"));
            }
            targets.add(new Target(new ProbeTargetSelector(probeId, baseUrl), key));
        }
        return List.copyOf(targets);
    }

    private SourceResolution resolveSource(JsonNode input, JsonNode target) {
        String workspaceValue = text(input.path("workspaceRootAbs"));
        String fqcn = text(target.path("selectors").path("fqcn"));
        String methodName = text(target.path("selectors").path("method"));
        if (workspaceValue == null || fqcn == null || methodName == null) {
            return missingSourceTarget(workspaceValue, fqcn, methodName);
        }
        try {
            Path workspace = Path.of(workspaceValue).toAbsolutePath().normalize();
            if (!Files.isDirectory(workspace)) {
                return SourceResolution.failure("probe_verification_workspace_unavailable", Map.of());
            }
            Path sourceRoot = sourceRoot(workspace, target.path("selectors").path("sourceRoot"));
            if (sourceRoot == null) {
                return SourceResolution.failure("probe_verification_source_root_invalid", Map.of());
            }
            var discovery = new FileSystemJavaSourceDiscovery(new RouteSynthesisWorkspaceSnapshot(workspace));
            List<MethodMatch> matches = new ArrayList<>();
            for (JavaSourceFile source : discovery.discover(sourceRoot, List.of(), fqcn).files()) {
                if (!fqcn.equals(source.fqcn())) {
                    continue;
                }
                for (JavaSourceMethod method : source.methods()) {
                    if (methodName.equals(method.name()) && matchesSignature(target, method)) {
                        matches.add(new MethodMatch(source.fqcn(), method));
                    }
                }
            }
            if (matches.isEmpty()) {
                return SourceResolution.failure("probe_verification_target_missing", Map.of(
                        "fqcn", fqcn, "method", methodName));
            }
            if (matches.size() != 1) {
                return SourceResolution.failure("probe_verification_target_ambiguous", Map.of(
                        "fqcn", fqcn, "method", methodName, "candidateCount", matches.size()));
            }
            MethodMatch match = matches.getFirst();
            return SourceResolution.success(match.fqcn() + "#" + methodName + ":"
                    + match.method().firstExecutableLine());
        } catch (InvalidPathException exception) {
            return SourceResolution.failure("probe_verification_workspace_unavailable", Map.of());
        }
    }

    private SourceResolution missingSourceTarget(String workspace, String fqcn, String method) {
        return SourceResolution.failure("probe_verification_target_missing", Map.of(
                "workspaceRootProvided", workspace != null,
                "fqcnProvided", fqcn != null,
                "methodProvided", method != null));
    }

    private Path sourceRoot(Path workspace, JsonNode sourceRootNode) {
        String value = text(sourceRootNode);
        Path candidate = value == null ? workspace : workspace.resolve(value).normalize();
        return candidate.startsWith(workspace) && Files.isDirectory(candidate) ? candidate : null;
    }

    private boolean matchesSignature(JsonNode target, JavaSourceMethod method) {
        String expected = text(target.path("selectors").path("signature"));
        if (expected == null) {
            return true;
        }
        String normalizedExpected = expected.replaceAll("\\s+", "");
        String normalizedActual = method.signature().replaceAll("\\s+", "");
        return normalizedActual.contains(method.name() + normalizedExpected);
    }

    private static String text(JsonNode node) {
        return node.isTextual() && !node.asText().isBlank() ? node.asText().trim() : null;
    }

    record Result(boolean configured, boolean success, String reasonCode, Map<String, Object> details) {
        private static Result notConfigured() {
            return new Result(false, true, "ok", Map.of());
        }

        private static Result success(Map<String, Object> details) {
            return new Result(true, true, "ok", Map.copyOf(details));
        }

        private static Result failure(String reasonCode, Map<String, Object> details) {
            return new Result(true, false, reasonCode, Map.copyOf(details));
        }
    }

    record Resolution(boolean success, JsonNode input, String reasonCode, Map<String, Object> details) {
        private static Resolution success(JsonNode input) {
            return new Resolution(true, input, "ok", Map.of());
        }

        private static Resolution failure(String reasonCode, Map<String, Object> details) {
            return new Resolution(false, null, reasonCode, Map.copyOf(details));
        }
    }

    private record SourceResolution(boolean success, String strictLineKey, String reasonCode,
            Map<String, Object> details) {
        private static SourceResolution success(String strictLineKey) {
            return new SourceResolution(true, strictLineKey, "ok", Map.of());
        }

        private static SourceResolution failure(String reasonCode, Map<String, Object> details) {
            return new SourceResolution(false, null, reasonCode, Map.copyOf(details));
        }
    }

    private record MethodMatch(String fqcn, JavaSourceMethod method) {
    }

    private record Target(ProbeTargetSelector selector, String strictLineKey) {
    }
}
