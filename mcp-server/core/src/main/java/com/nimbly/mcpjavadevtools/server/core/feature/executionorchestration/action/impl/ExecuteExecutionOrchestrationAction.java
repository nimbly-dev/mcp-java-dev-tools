package com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.action.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.ArtifactManagementFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactType;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.request.ArtifactManagementRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.action.ExecutionOrchestrationActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.model.action.ExecutionOrchestrationAction;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.model.request.ExecutionOrchestrationRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.model.result.ExecutionOrchestrationResult;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.lease.ExecutionRunLease;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.lifecycle.ExecutionRuntimeLifecycle;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.lifecycle.NoopExecutionRuntimeLifecycle;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.persistence.ExecutionRunDirectoryProvider;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.persistence.ExecutionSuiteStateStore;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.PerformanceSuiteFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.RegressionSuiteFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.correlation.RegressionCrossPlanCorrelation;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.SecuritySuiteFeature;
import com.nimbly.mcpjavadevtools.server.core.operation.execution.OperationExecutionContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.model.action.execute.ExecutionInput;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.model.action.execute.ResolvedExecutionProfile;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.model.action.execute.ExecutionPlanContext;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.persistence.ExecutionSuiteCheckpoint;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.persistence.ExecutionPlanRunArtifacts;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.context.ExecutionSuiteContext;

/** Resolves persisted execution profiles and routes plans through public Suite Core Features. */
public final class ExecuteExecutionOrchestrationAction implements ExecutionOrchestrationActionHandler {

    private final ArtifactManagementFeature artifacts;
    private final PerformanceSuiteFeature performance;
    private final SecuritySuiteFeature security;
    private final RegressionSuiteFeature regression;
    private final ObjectMapper mapper;
    private final ExecutionRunDirectoryProvider runDirectories;
    private final ExecutionRunLease lease;
    private final ExecutionRuntimeLifecycle runtimeLifecycle;
    private final RegressionCrossPlanCorrelation crossPlanCorrelation = new RegressionCrossPlanCorrelation();
    private final ExecutionSuiteCheckpoint checkpoints;
    private final ExecutionPlanRunArtifacts planRuns;
    private final ExecutionSuiteContext contextPolicy = new ExecutionSuiteContext(crossPlanCorrelation);

    /** Creates the action from intentional public Core Feature boundaries. */
    public ExecuteExecutionOrchestrationAction(
            ArtifactManagementFeature artifacts,
            PerformanceSuiteFeature performance,
            SecuritySuiteFeature security,
            RegressionSuiteFeature regression,
            ObjectMapper mapper,
            ExecutionRunDirectoryProvider runDirectories,
            ExecutionRunLease lease,
            ExecutionSuiteStateStore suiteState) {
        this(artifacts, performance, security, regression, mapper, runDirectories, lease, suiteState,
                new NoopExecutionRuntimeLifecycle());
    }

    /** Creates the action with application-owned runtime lifecycle coordination. */
    public ExecuteExecutionOrchestrationAction(
            ArtifactManagementFeature artifacts,
            PerformanceSuiteFeature performance,
            SecuritySuiteFeature security,
            RegressionSuiteFeature regression,
            ObjectMapper mapper,
            ExecutionRunDirectoryProvider runDirectories,
            ExecutionRunLease lease,
            ExecutionSuiteStateStore suiteState,
            ExecutionRuntimeLifecycle runtimeLifecycle) {
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts must not be null");
        this.performance = Objects.requireNonNull(performance, "performance must not be null");
        this.security = Objects.requireNonNull(security, "security must not be null");
        this.regression = Objects.requireNonNull(regression, "regression must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
        this.runDirectories = Objects.requireNonNull(runDirectories, "runDirectories must not be null");
        this.lease = Objects.requireNonNull(lease, "lease must not be null");
        this.runtimeLifecycle = Objects.requireNonNull(runtimeLifecycle, "runtimeLifecycle must not be null");
        this.checkpoints = new ExecutionSuiteCheckpoint(
                Objects.requireNonNull(suiteState, "suiteState must not be null"), mapper);
        this.planRuns = new ExecutionPlanRunArtifacts(artifacts, mapper);
    }

    @Override
    public ExecutionOrchestrationAction action() {
        return ExecutionOrchestrationAction.EXECUTE;
    }

    @Override
    public ExecutionOrchestrationResult execute(ExecutionOrchestrationRequest request) {
        checkpoint();
        ExecutionInput input = ExecutionInput.from(request.input());
        if (input.failure() != null) {
            return input.failure();
        }
        input = input.withAllocatedSuiteRunId();
        if (!lease.acquire(input.projectName(), input.suiteRunId())) {
            return blocked("execution_suite_run_active", "wait for the active suiteRunId call to finish", input);
        }
        try {
            checkpoint();
            ResolvedExecutionProfile resolvedProfile = profile(input);
            if (resolvedProfile == null) {
                return blocked("runtime_suite_missing", "add the requested executionProfile to the selected project", input);
            }
            JsonNode profile = resolvedProfile.profile();
            if (!profile.path("plans").isArray() || profile.path("plans").isEmpty()) {
                return blocked("execution_profile_invalid", "configure at least one ordered plan", input);
            }
            ExecutionOrchestrationResult invalidCheckpoint = checkpoints.validate(input, profile);
            if (invalidCheckpoint != null) {
                return invalidCheckpoint;
            }
            ExecutionOrchestrationResult recoveryFailure = recoverPersistedRuns(input, profile);
            if (recoveryFailure != null) {
                return recoveryFailure;
            }
            return executeProfile(input, profile, resolvedProfile.workspace());
        } finally {
            lease.release(input.projectName(), input.suiteRunId());
        }
    }

    private ExecutionOrchestrationResult executeProfile(ExecutionInput input, JsonNode profile, JsonNode workspace) {
        checkpoint();
        Map<String, Object> terminalEvidence = checkpoints.completedLifecycle(input);
        if (terminalEvidence != null) {
            return checkpoints.persist(input, profile, withLifecycle(runProfileSafely(input, profile, workspace), terminalEvidence));
        }
        ExecutionRuntimeLifecycle.RuntimeLifecycleRequest lifecycleRequest = lifecycleRequest(input, profile, workspace);
        var prepared = runtimeLifecycle.prepare(lifecycleRequest);
        if (!prepared.successful()) {
            return checkpoints.persist(input, profile, lifecycleBlocked(input, prepared));
        }
        ExecutionRuntimeLifecycle.RuntimeLifecycleRequest cleanupRequest = withEvidence(lifecycleRequest, prepared.evidence());
        try {
            if (prepared.enabled() && !checkpoints.persistLifecycle(input, profile, prepared.evidence())) {
                var cleaned = runtimeLifecycle.cleanup(cleanupRequest);
                if (!cleaned.successful()) {
                    return lifecycleBlocked(input, cleaned);
                }
                return blocked(
                        "execution_suite_checkpoint_persist_failed",
                        "restore suite checkpoint storage and retry",
                        input);
            }
            checkpoint();
            ExecutionOrchestrationResult executed = runProfileSafely(input, profile, workspace);
            if (!prepared.enabled()) {
                return checkpoints.persist(input, profile, executed);
            }
            if ("in_progress".equals(executed.status())) {
                return checkpoints.persist(input, profile, withLifecycle(executed, prepared.evidence()));
            }
            var cleaned = runtimeLifecycle.cleanup(cleanupRequest);
            if (!cleaned.successful()) {
                return checkpoints.persist(input, profile, lifecycleBlocked(input, cleaned));
            }
            return checkpoints.persist(input, profile, withLifecycle(executed, cleaned.evidence()));
        } catch (CancellationException exception) {
            cleanupAfterCancellation(input, profile, prepared, cleanupRequest, exception);
            throw exception;
        }
    }

    private void cleanupAfterCancellation(
            ExecutionInput input,
            JsonNode profile,
            ExecutionRuntimeLifecycle.RuntimeLifecycleResult prepared,
            ExecutionRuntimeLifecycle.RuntimeLifecycleRequest cleanupRequest,
            CancellationException cancellation) {
        if (!prepared.enabled()) {
            return;
        }
        try {
            var cleaned = runtimeLifecycle.cleanup(cleanupRequest);
            if (!checkpoints.persistLifecycle(input, profile, cleaned.evidence())) {
                cancellation.addSuppressed(new IllegalStateException(
                        "cancelled runtime cleanup evidence could not be persisted"));
            }
            if (!cleaned.successful()) {
                cancellation.addSuppressed(new IllegalStateException(
                        "cancelled runtime cleanup was not verified: " + cleaned.reasonCode()));
            }
        } catch (RuntimeException cleanupFailure) {
            cancellation.addSuppressed(cleanupFailure);
        }
    }

    private ExecutionOrchestrationResult runProfileSafely(
            ExecutionInput input,
            JsonNode profile,
            JsonNode workspace) {
        try {
            return runProfile(input, profile, workspace);
        } catch (CancellationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            return blocked("execution_runtime_failed", "inspect the suite execution failure before retrying", input);
        }
    }

    private ExecutionRuntimeLifecycle.RuntimeLifecycleRequest lifecycleRequest(
            ExecutionInput input, JsonNode profile, JsonNode workspace) {
        Map<String, Object> persisted = checkpoints.lifecycleEvidence(input);
        return new ExecutionRuntimeLifecycle.RuntimeLifecycleRequest(
                input.projectName(), input.suiteRunId(), workspace, profile, persisted);
    }

    private static ExecutionRuntimeLifecycle.RuntimeLifecycleRequest withEvidence(
            ExecutionRuntimeLifecycle.RuntimeLifecycleRequest request, Map<String, Object> evidence) {
        return new ExecutionRuntimeLifecycle.RuntimeLifecycleRequest(
                request.projectName(), request.suiteRunId(), request.workspace(), request.profile(), evidence);
    }

    private static ExecutionOrchestrationResult lifecycleBlocked(
            ExecutionInput input, ExecutionRuntimeLifecycle.RuntimeLifecycleResult result) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("action", "execute");
        details.put("suiteRunId", input.suiteRunId());
        details.put("runtimeLifecycle", result.evidence());
        return new ExecutionOrchestrationResult(
                "blocked", result.reasonCode(), "inspect persisted lifecycle evidence before retrying", input.metadata(), details);
    }

    private static ExecutionOrchestrationResult withLifecycle(
            ExecutionOrchestrationResult result, Map<String, Object> evidence) {
        Map<String, Object> details = new LinkedHashMap<>(result.details());
        details.put("runtimeLifecycle", evidence);
        return new ExecutionOrchestrationResult(
                result.status(), result.reasonCode(), result.nextAction(), result.reasonMeta(), details);
    }

    private ResolvedExecutionProfile profile(ExecutionInput input) {
        ObjectNode request = mapper.createObjectNode();
        request.put("projectName", input.projectName());
        request.putObject("query").putArray("select").add("artifact");
        var result = artifacts.execute(new ArtifactManagementRequest(ArtifactType.PROJECT_CONTEXT, ArtifactAction.READ, request));
        if (!"ok".equals(result.status())) {
            return null;
        }
        JsonNode artifact = mapper.valueToTree(result.details().get("artifact"));
        return selectedProfile(artifact.path("workspaces"), input.executionProfile());
    }

    private static ResolvedExecutionProfile selectedProfile(JsonNode workspaces, String executionProfile) {
        ResolvedExecutionProfile selected = null;
        for (JsonNode workspace : workspaces) {
            for (JsonNode profile : workspace.path("executionProfiles")) {
                if (executionProfile.equals(profile.path("executionProfile").asText())) {
                    if (selected != null) {
                        return null;
                    }
                    selected = new ResolvedExecutionProfile(profile, workspace);
                }
            }
        }
        return selected;
    }

    private ExecutionOrchestrationResult runProfile(ExecutionInput input, JsonNode profile, JsonNode workspace) {
        String suiteType = profile.path("suiteType").asText();
        Map<String, Object> suiteContext = suiteContext(input);
        List<Map<String, Object>> prior = terminalOutcomes(
                input, suiteType, profile.path("plans"), suiteContext);
        ExecutionOrchestrationResult stopped = priorStop(input, profile, prior);
        if (stopped != null) {
            return withSuiteContext(stopped, suiteContext);
        }
        List<JsonNode> pending = pendingPlans(input, suiteType, profile.path("plans"));
        if (pending.isEmpty()) {
            return withSuiteContext(completed(input, profile, prior, false), suiteContext);
        }
        List<JsonNode> plans = limited(pending, input.maxPlansPerCall());
        List<Map<String, Object>> outcomes = new ArrayList<>();
        for (JsonNode plan : plans) {
            checkpoint();
            Map<String, Object> outcome = runPlan(input, new ExecutionPlanContext(profile, workspace, suiteContext), suiteType, plan);
            checkpoint();
            outcome = ExecutionSuiteContext.redactOutcomeContext(outcome);
            outcome = planRuns.persist(input, suiteType, plan, outcome);
            if ("execution_run_artifact_persist_failed".equals(outcome.get("reasonCode"))) {
                return blocked("execution_run_artifact_persist_failed", "restore run storage and retry", input);
            }
            outcomes.add(outcome);
            promoteContext(suiteContext, plan, outcome);
            ExecutionOrchestrationResult progress = checkpoints.persist(
                    input, profile, withSuiteContext(
                            completed(input, profile, merged(prior, outcomes), true), suiteContext));
            if ("execution_suite_checkpoint_persist_failed".equals(progress.reasonCode())) {
                return progress;
            }
            if ("in_progress".equals(outcome.get("status"))) {
                return progress;
            }
            checkpoint();
            if (shouldStop(profile, plan, outcome)) {
                return withSuiteContext(failed(input, profile, merged(prior, outcomes), String.valueOf(outcome.get("reasonCode"))), suiteContext);
            }
        }
        boolean partial = pending.size() > plans.size();
        return withSuiteContext(completed(input, profile, merged(prior, outcomes), partial), suiteContext);
    }

    private static ExecutionOrchestrationResult priorStop(
            ExecutionInput input, JsonNode profile, List<Map<String, Object>> prior) {
        for (JsonNode plan : ordered(profile.path("plans"))) {
            for (Map<String, Object> outcome : prior) {
                if (Objects.equals(outcome.get("order"), plan.path("order").asInt()) && shouldStop(profile, plan, outcome)) {
                    return failed(input, profile, prior, String.valueOf(outcome.get("reasonCode")));
                }
            }
        }
        return null;
    }

    private Map<String, Object> suiteContext(ExecutionInput input) {
        return ExecutionSuiteContext.safeContext(checkpoints.suiteContext(input));
    }

    private List<Map<String, Object>> terminalOutcomes(
            ExecutionInput input,
            String suiteType,
            JsonNode plans,
            Map<String, Object> suiteContext) {
        List<Map<String, Object>> outcomes = new ArrayList<>();
        for (JsonNode plan : ordered(plans)) {
            Map<String, Object> outcome = planRuns.terminalOutcome(input, suiteType, plan);
            if (outcome != null) {
                outcomes.add(outcome);
                promoteContext(suiteContext, plan, outcome);
            }
        }
        return List.copyOf(outcomes);
    }

    private static List<Map<String, Object>> merged(
            List<Map<String, Object>> prior, List<Map<String, Object>> current) {
        List<Map<String, Object>> merged = new ArrayList<>(prior);
        merged.addAll(current);
        return List.copyOf(merged);
    }

    private List<JsonNode> pendingPlans(ExecutionInput input, String suiteType, JsonNode values) {
        List<JsonNode> pending = new ArrayList<>();
        for (JsonNode plan : ordered(values)) {
            if (!persistedTerminal(input, suiteType, plan)) {
                pending.add(plan);
            }
        }
        return List.copyOf(pending);
    }

    private boolean persistedTerminal(ExecutionInput input, String suiteType, JsonNode plan) {
        return planRuns.terminalOutcome(input, suiteType, plan) != null;
    }

    private ExecutionOrchestrationResult recoverPersistedRuns(ExecutionInput input, JsonNode profile) {
        for (JsonNode plan : ordered(profile.path("plans"))) {
            checkpoint();
            ExecutionOrchestrationResult failure = planRuns.recover(input, profile.path("suiteType").asText(), plan);
            if (failure != null) {
                return failure;
            }
        }
        return null;
    }

    private static List<JsonNode> ordered(JsonNode values) {
        List<JsonNode> plans = new ArrayList<>();
        values.forEach(plans::add);
        plans.sort(Comparator.comparingInt(plan -> plan.path("order").asInt()));
        return List.copyOf(plans);
    }

    private static List<JsonNode> limited(List<JsonNode> plans, int maxPlansPerCall) {
        int limit = maxPlansPerCall == 0 ? plans.size() : Math.min(maxPlansPerCall, plans.size());
        return List.copyOf(plans.subList(0, limit));
    }

    private Map<String, Object> runPlan(ExecutionInput input, ExecutionPlanContext context, String suiteType, JsonNode plan) {
        String planName = ExecutionInput.text(plan.path("planName"));
        if (planName == null) {
            return outcome(plan, "blocked", "execution_profile_invalid", Map.of());
        }
        JsonNode artifact = planArtifact(input.projectName(), suiteType, planName);
        if (artifact == null) {
            return outcome(plan, "blocked", "plan_artifact_missing", Map.of("planName", planName));
        }
        String runId = input.runId(plan.path("order").asInt());
        ObjectNode envelope = envelope(input, context, suiteType, plan, planName, artifact);
        if ("regression".equals(suiteType)) {
            planRuns.addRegressionContinuation(envelope, input, planName, runId);
        }
        if ("performance".equals(suiteType) && !addRunDirectory(envelope, input, suiteType, planName, runId)) {
            return outcome(plan, "blocked", "execution_run_directory_unavailable", Map.of("runId", runId));
        }
        return route(plan, suiteType, envelope);
    }

    private boolean addRunDirectory(
            ObjectNode envelope, ExecutionInput input, String suiteType, String planName, String runId) {
        return runDirectories.resolve(input.projectName(), suiteType, planName, runId)
                .map(directory -> {
                    envelope.put("runDirectory", directory);
                    return true;
                }).orElse(false);
    }

    private JsonNode planArtifact(String projectName, String suiteType, String planName) {
        ArtifactType type = artifactType(suiteType);
        if (type == null) {
            return null;
        }
        ObjectNode input = mapper.createObjectNode();
        input.put("projectName", projectName);
        input.put("planName", planName);
        input.putObject("query").putArray("select").add("metadata").add("contract");
        var result = artifacts.execute(new ArtifactManagementRequest(type, ArtifactAction.READ, input));
        return "ok".equals(result.status()) ? mapper.valueToTree(result.details().get("artifact")) : null;
    }

    private static ArtifactType artifactType(String suiteType) {
        return switch (suiteType) {
            case "performance" -> ArtifactType.PERFORMANCE_PLAN;
            case "security" -> ArtifactType.SECURITY_PLAN;
            case "regression" -> ArtifactType.REGRESSION_PLAN;
            default -> null;
        };
    }

    private ObjectNode envelope(ExecutionInput input, ExecutionPlanContext context, String suiteType,
            JsonNode plan, String planName, JsonNode artifact) {
        ObjectNode value = mapper.createObjectNode();
        value.put("projectName", input.projectName());
        value.put("executionProfile", input.executionProfile());
        value.put("planName", planName);
        value.set("metadata", artifact.path("metadata"));
        value.set("contract", artifact.path("contract"));
        value.set("suiteContext", mapper.valueToTree(context.suiteContext()));
        ObjectNode providedContext = mapper.createObjectNode();
        copyFields(context.profile().path("providedContext"), providedContext);
        copyFields(plan.path("providedContext"), providedContext);
        context.suiteContext().forEach((key, entry) -> providedContext.set(key, mapper.valueToTree(entry)));
        if (!providedContext.isEmpty()) {
            value.set("providedContext", providedContext);
        }
        if (input.suiteRunId() != null) {
            value.put("suiteRunId", input.suiteRunId());
        }
        boolean boundRuntimeContext = ("regression".equals(suiteType) || "performance".equals(suiteType))
                && context.workspace().path("variables").path("contextBindings").isObject()
                && !context.workspace().path("variables").path("contextBindings").isEmpty();
        if ("security".equals(suiteType) || boundRuntimeContext) {
            value.set("credentialBindings", context.workspace().path("variables").path("contextBindings"));
            ObjectNode source = value.putObject("credentialSource");
            source.put("workspaceRoot", context.workspace().path("projectRoot").asText());
            source.put("envFile", context.workspace().path("envFile").asText());
            source.set("scripts", context.workspace().path("scripts"));
            source.set("profileScriptRefs", context.profile().path("scriptRefs"));
        }
        return value;
    }

    private static void copyFields(JsonNode source, ObjectNode target) {
        if (source.isObject()) {
            source.fields().forEachRemaining(entry -> target.set(entry.getKey(), entry.getValue()));
        }
    }

    private void promoteContext(Map<String, Object> suiteContext, JsonNode plan, Map<String, Object> outcome) {
        if (!suiteFailed(outcome) && !"in_progress".equals(outcome.get("status"))) {
            contextPolicy.promoteSuccessful(suiteContext, plan, outcome);
        }
    }

    private static ExecutionOrchestrationResult withSuiteContext(
            ExecutionOrchestrationResult result, Map<String, Object> suiteContext) {
        Map<String, Object> details = new LinkedHashMap<>(result.details());
        details.put("suiteContext", Map.copyOf(suiteContext));
        return new ExecutionOrchestrationResult(
                result.status(), result.reasonCode(), result.nextAction(), result.reasonMeta(), details);
    }

    private Map<String, Object> route(JsonNode plan, String suiteType, JsonNode input) {
        return switch (suiteType) {
            case "performance" -> performance(plan, input);
            case "security" -> security(plan, input);
            case "regression" -> regression(plan, input);
            default -> outcome(plan, "blocked", "execution_route_invalid", Map.of());
        };
    }

    private Map<String, Object> performance(JsonNode plan, JsonNode input) {
        var result = performance.executePlan(input);
        return outcome(plan, result.status(), result.reasonCode(), result.details());
    }

    private Map<String, Object> security(JsonNode plan, JsonNode input) {
        var result = security.executePlan(input);
        return outcome(plan, result.status(), result.reasonCode(), result.details());
    }

    private Map<String, Object> regression(JsonNode plan, JsonNode input) {
        var result = regression.executePlan(input);
        return outcome(plan, result.status(), result.reasonCode(), result.details());
    }

    private static boolean shouldStop(JsonNode profile, JsonNode plan, Map<String, Object> outcome) {
        if (!suiteFailed(outcome)) {
            return false;
        }
        String onFail = plan.path("onFail").asText("inherit");
        return "stop".equals(onFail) || ("inherit".equals(onFail)
                && "stop_on_fail".equals(profile.path("executionPolicy").asText()));
    }

    private static Map<String, Object> outcome(JsonNode plan, String status, String reasonCode, Map<String, Object> details) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("order", plan.path("order").asInt());
        output.put("planName", plan.path("planName").asText());
        output.put("status", status);
        output.put("reasonCode", reasonCode);
        output.put("details", details);
        return Map.copyOf(output);
    }

    private ExecutionOrchestrationResult completed(
            ExecutionInput input, JsonNode profile, List<Map<String, Object>> outcomes, boolean partial) {
        List<Map<String, Object>> correlations = crossPlanCorrelation.summarize(outcomes);
        boolean aggregateFailure = outcomes.stream().anyMatch(ExecuteExecutionOrchestrationAction::suiteFailed)
                || crossPlanCorrelation.failed(correlations);
        String status = resultStatus(partial, aggregateFailure);
        String code = resultCode(partial, aggregateFailure);
        String next = partial ? "resume with suiteRunId to execute remaining plans" : null;
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("action", "execute");
        details.put("executionProfile", input.executionProfile());
        details.put("executionPolicy", profile.path("executionPolicy").asText());
        details.put("suiteRunId", input.suiteRunId());
        Map<String, Object> progress = new LinkedHashMap<>();
        progress.put("completedPlanCount", outcomes.stream().filter(row -> !"in_progress".equals(row.get("status"))).count());
        progress.put("status", status);
        progress.put("failedPlanCount", outcomes.stream().filter(ExecuteExecutionOrchestrationAction::suiteFailed).count());
        addActivePhase(progress, outcomes);
        details.put("progressSummary", progress);
        details.put("progress", progress);
        details.put("planRuns", outcomes);
        if (!correlations.isEmpty()) {
            details.put("correlations", correlations);
        }
        if (partial && !outcomes.isEmpty()) {
            int increment = "in_progress".equals(outcomes.getLast().get("status")) ? 0 : 1;
            details.put("nextPlanOrder", ((Number) outcomes.getLast().get("order")).intValue() + increment);
        }
        return new ExecutionOrchestrationResult(status, code, next, input.metadata(), details);
    }

    private void addActivePhase(Map<String, Object> progress, List<Map<String, Object>> outcomes) {
        if (!outcomes.isEmpty() && "in_progress".equals(outcomes.getLast().get("status"))) {
            JsonNode active = mapper.valueToTree(outcomes.getLast());
            progress.put("activePlan", Map.of("planName", active.path("planName").asText(),
                    "order", active.path("order").asInt(),
                    "phase", active.path("details").path("continuation").path("phase").asText()));
        }
    }

    private static String resultStatus(boolean partial, boolean aggregateFailure) {
        if (aggregateFailure) {
            return "partial_fail";
        }
        return partial ? "in_progress" : "pass";
    }

    private static String resultCode(boolean partial, boolean aggregateFailure) {
        if (partial) {
            return "execution_plan_slice_complete";
        }
        return aggregateFailure ? "execution_suite_partial_fail" : "ok";
    }

    private static boolean suiteFailed(Map<String, Object> outcome) {
        Object details = outcome.get("details");
        if (details instanceof Map<?, ?> values && "fail".equals(values.get("runStatus"))) {
            return true;
        }
        return !List.of("pass", "completed", "ready", "skipped", "in_progress").contains(String.valueOf(outcome.get("status")));
    }

    private static void checkpoint() {
        if (Thread.currentThread().isInterrupted()
                || OperationExecutionContext.current().cancellationRequested()) {
            throw new CancellationException("execution orchestration was cancelled");
        }
    }

    private static ExecutionOrchestrationResult failed(
            ExecutionInput input, JsonNode profile, List<Map<String, Object>> outcomes, String reasonCode) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("action", "execute");
        details.put("executionProfile", input.executionProfile());
        details.put("executionPolicy", profile.path("executionPolicy").asText());
        details.put("suiteRunId", input.suiteRunId());
        Map<String, Object> progress = Map.of("completedPlanCount", outcomes.size(), "status", "fail",
                "failedPlanCount", outcomes.stream().filter(ExecuteExecutionOrchestrationAction::suiteFailed).count());
        details.put("progressSummary", progress);
        details.put("progress", progress);
        details.put("planRuns", outcomes);
        return new ExecutionOrchestrationResult("fail", reasonCode, "inspect the failed plan before resuming", input.metadata(), details);
    }

    private static ExecutionOrchestrationResult blocked(String reasonCode, String nextAction, ExecutionInput input) {
        return ExecutionOrchestrationResult.blocked(reasonCode, nextAction, input.metadata());
    }

}
