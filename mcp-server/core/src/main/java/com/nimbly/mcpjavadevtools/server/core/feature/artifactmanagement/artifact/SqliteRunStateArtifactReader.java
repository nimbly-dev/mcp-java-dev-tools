package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Reads and normalizes bounded run and legacy correlation Artifacts. */
final class SqliteRunStateArtifactReader {

    private static final int MAX_SCAN_ENTRIES = 10_000;
    private final SqliteRunStateJson json;

    SqliteRunStateArtifactReader(SqliteRunStateJson json) {
        this.json = json;
    }

    List<SqliteRunProjection> scanRunArtifacts(Path databasePath) {
        Path projectDirectory = databasePath.getParent();
        Path workspaceRoot = projectDirectory.getParent().getParent();
        String projectName = projectDirectory.getFileName().toString();
        ArtifactPathPolicy policy = new ArtifactPathPolicy(workspaceRoot);
        List<SqliteRunProjection> projections = new ArrayList<>();
        for (String suite : List.of("regression", "performance", "security")) {
            Path plans = policy.resolve(".mcpjvm", projectName, "plans", suite);
            for (Path plan : safeDirectories(plans, policy)) {
                Path runs = policy.check(plan.resolve("runs"));
                for (Path run : safeDirectories(runs, policy)) {
                    if (projections.size() >= MAX_SCAN_ENTRIES) {
                        throw new ArtifactOperationException(
                                "state_store_scan_limit_exceeded", "Run Artifact scan exceeds the bounded limit");
                    }
                    Path executionResult = policy.check(run.resolve("execution.result.json"));
                    Path legacyResult = policy.check(run.resolve("result.json"));
                    Path artifact = Files.isRegularFile(executionResult) ? executionResult : legacyResult;
                    projections.add(readProjection(
                            suite,
                            plan.getFileName().toString(),
                            run.getFileName().toString(),
                            artifact,
                            relativeToWorkspace(databasePath, run),
                            policy));
                }
            }
        }
        return projections;
    }

    List<Path> safeDirectories(Path root, ArtifactPathPolicy policy) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var stream = Files.list(root)) {
            List<Path> values = stream.filter(Files::isDirectory)
                    .map(policy::check)
                    .sorted(Comparator.naturalOrder())
                    .limit(MAX_SCAN_ENTRIES + 1L)
                    .toList();
            if (values.size() > MAX_SCAN_ENTRIES) {
                throw new ArtifactOperationException(
                        "state_store_scan_limit_exceeded", "Artifact directory scan exceeds the bounded limit");
            }
            return values;
        } catch (IOException exception) {
            throw new ArtifactOperationException("state_store_scan_failed", "Run Artifacts could not be scanned");
        }
    }

    SqliteRunProjection readProjection(
            String suiteType,
            String planName,
            String runId,
            Path artifact,
            String relativePath,
            ArtifactPathPolicy policy) {
        if (!Files.isRegularFile(artifact)) {
            return SqliteRunProjection.invalid(suiteType, planName, runId, relativePath, "run_artifact_missing");
        }
        try {
            JsonNode root = json.readBoundedJson(artifact);
            if (root == null || !root.isObject()) {
                return SqliteRunProjection.invalid(suiteType, planName, runId, relativePath, "run_artifact_invalid");
            }
            root = includeSidecarState(artifact.getParent(), root, policy);
            JsonNode steps = root.path("steps");
            int stepCount = steps.isArray() ? steps.size() : 0;
            int failedStepCount = 0;
            if (steps.isArray()) {
                for (JsonNode step : steps) {
                    String status = step.path("status").asText("");
                    if (!List.of("passed", "pass", "ok", "skipped_condition_false").contains(status)) {
                        failedStepCount++;
                    }
                }
            }
            return new SqliteRunProjection(
                    suiteType,
                    planName,
                    runId,
                    root.path("status").asText("blocked"),
                    stepCount,
                    failedStepCount,
                    epoch(root.get("startedAt")),
                    epoch(root.get("endedAt")),
                    root.path("reasonCode").asText(null),
                    root.path("executionProfile").asText(null),
                    root.path("suiteRunId").asText(null),
                    root.path("activePhase").asText(null),
                    relativePath,
                    root,
                    true,
                    "");
        } catch (IOException | RuntimeException exception) {
            return SqliteRunProjection.invalid(suiteType, planName, runId, relativePath, "run_artifact_invalid");
        }
    }

    JsonNode readBoundedJson(Path path) throws IOException {
        return json.readBoundedJson(path);
    }

    JsonNode includeSidecarState(Path runDirectory, JsonNode root, ArtifactPathPolicy policy)
            throws IOException {
        ObjectNode enriched = (ObjectNode) root.deepCopy();
        for (String sidecar : List.of(
                "continuation.json", "correlation.json", "watchers.json", "external-verification.json")) {
            Path path = policy.check(runDirectory.resolve(sidecar));
            if (Files.isRegularFile(path)) {
                String field = switch (sidecar) {
                    case "continuation.json" -> "continuation";
                    case "correlation.json" -> "correlations";
                    case "watchers.json" -> "watchers";
                    default -> "externalVerification";
                };
                enriched.set(field, json.readBoundedJson(path));
            }
        }
        return enriched;
    }

    List<Path> findFiles(Path root, String fileName) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var stream = Files.walk(root)) {
            List<Path> files = stream.filter(path -> !Files.isSymbolicLink(path) && Files.isRegularFile(path)
                            && fileName.equals(path.getFileName().toString()))
                    .sorted(Comparator.naturalOrder())
                    .limit(MAX_SCAN_ENTRIES + 1L)
                    .toList();
            if (files.size() > MAX_SCAN_ENTRIES) {
                throw new ArtifactOperationException(
                        "state_store_scan_limit_exceeded", "Legacy Artifact scan exceeds the bounded limit");
            }
            return files;
        } catch (IOException exception) {
            throw new ArtifactOperationException("state_store_scan_failed", "Run Artifacts could not be scanned");
        }
    }

    SqliteLegacyCorrelation legacyCorrelation(Path correlation) {
        Path run = correlation.getParent();
        Path runs = run == null ? null : run.getParent();
        Path plan = runs == null ? null : runs.getParent();
        if (run == null || plan == null || run.getFileName() == null || plan.getFileName() == null) {
            throw new ArtifactOperationException(
                    "state_store_backfill_source_invalid", "Legacy correlation path is not a plan run Artifact");
        }
        return new SqliteLegacyCorrelation(plan.getFileName().toString(), run.getFileName().toString());
    }

    String relativeToWorkspace(Path databasePath, Path path) {
        Path workspace = databasePath.getParent().getParent().getParent();
        return workspace.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    Long epoch(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isIntegralNumber()) {
            return value.longValue();
        }
        if (value.isTextual()) {
            try {
                return Instant.parse(value.asText()).toEpochMilli();
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }
}
