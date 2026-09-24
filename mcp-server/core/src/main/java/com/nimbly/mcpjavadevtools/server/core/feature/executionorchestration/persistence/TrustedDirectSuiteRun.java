package com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactManagementAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactType;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.request.ArtifactManagementRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.operation.ArtifactOperationCatalog;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.TrustedSuiteExecution;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

/** Trusted plan and run-state boundary for new direct Suite CDE operations. */
public class TrustedDirectSuiteRun implements TrustedSuiteExecution {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,125}");
    private final ArtifactOperationCatalog artifacts;
    private final ExecutionRunDirectoryProvider directories;
    private final ObjectMapper mapper;

    public TrustedDirectSuiteRun(ArtifactOperationCatalog artifacts,
            ExecutionRunDirectoryProvider directories, ObjectMapper mapper) {
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts must not be null");
        this.directories = Objects.requireNonNull(directories, "directories must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    /** Executes only a persisted, workspace-bound plan; caller JSON is never forwarded as a plan. */
    @Override
    public <T> T execute(String suiteType, JsonNode selector, Class<T> resultType,
            Function<JsonNode, T> owner, Function<String, T> blocked, boolean persist) {
        String project = selector.path("projectName").asText();
        String profile = selector.path("executionProfile").asText();
        String plan = selector.path("planName").asText();
        String suiteRunId = selector.path("suiteRunId").asText();
        if (!identifier(project) || !identifier(profile) || !identifier(plan) || !identifier(suiteRunId)) {
            return blocked.apply("direct_suite_selector_invalid");
        }
        String runId = suiteRunId + "-1";
        if (!persist) {
            ObjectNode input = resolvedInput(suiteType, project, profile, plan, suiteRunId);
            if (input == null) {
                return blocked.apply("direct_suite_plan_unavailable");
            }
            return owner.apply(input);
        }
        var directory = directories.resolve(project, suiteType, plan, runId);
        if (directory.isEmpty()) {
            return blocked.apply("execution_run_directory_unavailable");
        }
        try {
            Path runDirectory = Path.of(directory.get());
            Files.createDirectories(runDirectory);
            try (FileChannel channel = FileChannel.open(
                    runDirectory.resolve(".direct-execution.lock"), CREATE, WRITE)) {
                try (FileLock lock = channel.tryLock()) {
                    if (lock == null) {
                        return blocked.apply("direct_suite_run_in_progress");
                    }
                    return executeLocked(suiteType, selector, resultType, owner, blocked, runDirectory);
                }
            }
        } catch (OverlappingFileLockException exception) {
            return blocked.apply("direct_suite_run_in_progress");
        } catch (IOException | InvalidPathException exception) {
            return blocked.apply("execution_run_directory_unavailable");
        }
    }

    private <T> T executeLocked(String suiteType, JsonNode selector, Class<T> resultType,
            Function<JsonNode, T> owner, Function<String, T> blocked, Path runDirectory) {
        String project = selector.path("projectName").asText();
        String profile = selector.path("executionProfile").asText();
        String plan = selector.path("planName").asText();
        String suiteRunId = selector.path("suiteRunId").asText();
        String runId = suiteRunId + "-1";
        ObjectNode input = resolvedInput(suiteType, project, profile, plan, suiteRunId);
        if (input == null) {
            return blocked.apply("direct_suite_plan_unavailable");
        }
        JsonNode prior = priorResult(suiteType, project, plan, runId);
        if (prior != null) {
            if (!profile.equals(prior.path("executionProfile").asText())
                    || !suiteRunId.equals(prior.path("suiteRunId").asText())
                    || !plan.equals(prior.path("planName").asText())
                    || !prior.path("directResult").isObject()) {
                return blocked.apply("direct_suite_run_conflict");
            }
            return mapper.convertValue(prior.path("directResult"), resultType);
        }
        if ("performance".equals(suiteType)) {
            input.put("runDirectory", runDirectory.toString());
        }
        T result = owner.apply(input);
        if (!persist(selector, suiteType, runId, result)) {
            return blocked.apply("execution_run_artifact_persist_failed");
        }
        return result;
    }

    private ObjectNode resolvedInput(String suiteType, String project, String profile,
            String plan, String suiteRunId) {
        ObjectNode projectQuery = mapper.createObjectNode().put("projectName", project);
        projectQuery.putObject("query").putArray("select").add("artifact");
        var context = artifacts.execute(ArtifactManagementAction.PROJECT_CONTEXT_READ,
                new ArtifactManagementRequest(ArtifactType.PROJECT_CONTEXT, ArtifactAction.READ, projectQuery));
        if (!"ok".equals(context.status())) {
            return null;
        }
        JsonNode workspaces = mapper.valueToTree(context.details().get("artifact")).path("workspaces");
        ProfileSelection selected = selectProfile(workspaces, suiteType, profile, plan);
        if (selected == null) {
            return null;
        }
        ArtifactType type = planType(suiteType);
        ObjectNode planQuery = mapper.createObjectNode().put("projectName", project).put("planName", plan);
        planQuery.putObject("query").putArray("select").add("metadata").add("contract");
        var loaded = artifacts.execute(planAction(suiteType),
                new ArtifactManagementRequest(type, ArtifactAction.READ, planQuery));
        if (!"ok".equals(loaded.status())) {
            return null;
        }
        JsonNode artifact = mapper.valueToTree(loaded.details().get("artifact"));
        if (!artifact.path("contract").isObject()) {
            return null;
        }
        ObjectNode input = mapper.createObjectNode();
        input.put("projectName", project);
        input.put("executionProfile", profile);
        input.put("planName", plan);
        input.put("suiteRunId", suiteRunId);
        input.set("metadata", artifact.path("metadata"));
        input.set("contract", artifact.path("contract"));
        if ("security".equals(suiteType)) {
            input.set("credentialBindings", selected.workspace().path("variables").path("contextBindings"));
            ObjectNode source = input.putObject("credentialSource");
            source.put("workspaceRoot", selected.workspace().path("projectRoot").asText());
            source.put("envFile", selected.workspace().path("envFile").asText());
            source.set("scripts", selected.workspace().path("scripts"));
            source.set("profileScriptRefs", selected.profile().path("scriptRefs"));
        }
        return input;
    }

    private static ProfileSelection selectProfile(JsonNode workspaces, String suiteType,
            String profile, String plan) {
        ProfileSelection selected = null;
        for (JsonNode workspace : workspaces) {
            for (JsonNode candidate : workspace.path("executionProfiles")) {
                if (profile.equals(candidate.path("executionProfile").asText())
                        && suiteType.equals(candidate.path("suiteType").asText())
                        && hasPlan(candidate, plan)) {
                    if (selected != null) {
                        return null;
                    }
                    selected = new ProfileSelection(workspace, candidate);
                }
            }
        }
        return selected;
    }

    private record ProfileSelection(JsonNode workspace, JsonNode profile) {
    }

    private static boolean hasPlan(JsonNode profile, String plan) {
        for (JsonNode candidate : profile.path("plans")) {
            if (plan.equals(candidate.path("planName").asText())) {
                return true;
            }
        }
        return false;
    }

    private static boolean identifier(String value) {
        return IDENTIFIER.matcher(value).matches() && !".".equals(value) && !"..".equals(value);
    }

    private JsonNode priorResult(String suiteType, String project, String plan, String runId) {
        ObjectNode request = runRequest(suiteType, project, plan, runId);
        var read = artifacts.execute(ArtifactManagementAction.RUN_RESULT_READ,
                new ArtifactManagementRequest(ArtifactType.RUN_RESULT, ArtifactAction.READ, request));
        JsonNode artifact = mapper.valueToTree(read.details().get("artifact"));
        return "ok".equals(read.status()) && artifact.isObject() ? artifact : null;
    }

    private boolean persist(JsonNode selector, String suiteType, String runId, Object result) {
        String project = selector.path("projectName").asText();
        String profile = selector.path("executionProfile").asText();
        String plan = selector.path("planName").asText();
        String suiteRunId = selector.path("suiteRunId").asText();
        ObjectNode payload = mapper.createObjectNode();
        JsonNode resultNode = mapper.valueToTree(result);
        payload.put("status", resultNode.path("details").path("runStatus")
                .asText(resultNode.path("status").asText()));
        payload.put("reasonCode", resultNode.path("reasonCode").asText());
        payload.put("executionProfile", profile);
        payload.put("suiteRunId", suiteRunId);
        payload.put("runId", runId);
        payload.put("planName", plan);
        payload.put("order", 1);
        payload.set("details", resultNode.path("details"));
        payload.set("directResult", resultNode);
        payload.put("startedAt", Instant.now().toString());
        payload.put("endedAt", Instant.now().toString());
        ObjectNode request = runRequest(suiteType, project, plan, runId);
        request.set("payload", payload);
        var saved = artifacts.execute(ArtifactManagementAction.RUN_RESULT_UPSERT,
                new ArtifactManagementRequest(ArtifactType.RUN_RESULT, ArtifactAction.UPSERT, request));
        return "ok".equals(saved.status());
    }

    private ObjectNode runRequest(String suiteType, String project, String plan, String runId) {
        return mapper.createObjectNode().put("projectName", project)
                .put("suiteType", suiteType).put("planName", plan).put("runId", runId);
    }

    private static ArtifactType planType(String suiteType) {
        return switch (suiteType) {
            case "performance" -> ArtifactType.PERFORMANCE_PLAN;
            case "security" -> ArtifactType.SECURITY_PLAN;
            case "regression" -> ArtifactType.REGRESSION_PLAN;
            default -> throw new IllegalArgumentException("unknown Suite type");
        };
    }

    private static ArtifactManagementAction planAction(String suiteType) {
        return switch (suiteType) {
            case "performance" -> ArtifactManagementAction.PERFORMANCE_PLAN_READ;
            case "security" -> ArtifactManagementAction.SECURITY_PLAN_READ;
            case "regression" -> ArtifactManagementAction.REGRESSION_PLAN_READ;
            default -> throw new IllegalArgumentException("unknown Suite type");
        };
    }
}
