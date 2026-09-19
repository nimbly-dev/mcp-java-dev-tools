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
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.model.action.PerformanceSuiteAction;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.model.request.PerformanceSuiteRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.RegressionSuiteFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.model.action.RegressionSuiteAction;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.model.request.RegressionSuiteRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.SecuritySuiteFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.model.action.SecuritySuiteAction;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.model.request.SecuritySuiteRequest;
import com.nimbly.mcpjavadevtools.server.core.operation.execution.OperationExecutionContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.regex.Pattern;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Resolves persisted execution profiles and routes plans through public Suite Core Features. */
public final class ExecuteExecutionOrchestrationAction implements ExecutionOrchestrationActionHandler {

    private static final Pattern RUN_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Set<String> PROMOTABLE_CONTEXT_KEYS = Set.of(
            "traceid", "requestid", "messageid", "correlationid", "tenantid", "resourceid", "runlabel",
            "environmentname", "runtimecontextname");
    private static final int MAX_CONTEXT_VALUE_LENGTH = 256;
    private final ArtifactManagementFeature artifacts;
    private final PerformanceSuiteFeature performance;
    private final SecuritySuiteFeature security;
    private final RegressionSuiteFeature regression;
    private final ObjectMapper mapper;
    private final ExecutionRunDirectoryProvider runDirectories;
    private final ExecutionRunLease lease;
    private final ExecutionSuiteStateStore suiteState;
    private final ExecutionRuntimeLifecycle runtimeLifecycle;

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
        this.suiteState = Objects.requireNonNull(suiteState, "suiteState must not be null");
        this.runtimeLifecycle = Objects.requireNonNull(runtimeLifecycle, "runtimeLifecycle must not be null");
    }

    @Override
    public ExecutionOrchestrationAction action() {
        return ExecutionOrchestrationAction.EXECUTE;
    }

    @Override
    public ExecutionOrchestrationResult execute(ExecutionOrchestrationRequest request) {
        checkpoint();
        Input input = Input.from(request.input());
        if (input.failure() != null) {
            return input.failure();
        }
        input = input.withAllocatedSuiteRunId();
        if (!lease.acquire(input.projectName(), input.suiteRunId())) {
            return blocked("execution_suite_run_active", "wait for the active suiteRunId call to finish", input);
        }
        try {
            checkpoint();
            ProfileContext resolvedProfile = profile(input);
            if (resolvedProfile == null) {
                return blocked("runtime_suite_missing", "add the requested executionProfile to the selected project", input);
            }
            JsonNode profile = resolvedProfile.profile();
            ExecutionOrchestrationResult invalidCheckpoint = validateCheckpoint(input, profile);
            if (invalidCheckpoint != null) {
                return invalidCheckpoint;
            }
            return executeProfile(input, profile, resolvedProfile.workspace());
        } finally {
            lease.release(input.projectName(), input.suiteRunId());
        }
    }

    private ExecutionOrchestrationResult executeProfile(Input input, JsonNode profile, JsonNode workspace) {
        checkpoint();
        ExecutionRuntimeLifecycle.RuntimeLifecycleRequest lifecycleRequest = lifecycleRequest(input, profile, workspace);
        var prepared = runtimeLifecycle.prepare(lifecycleRequest);
        if (!prepared.successful()) {
            return persistCheckpoint(input, profile, lifecycleBlocked(input, prepared));
        }
        ExecutionRuntimeLifecycle.RuntimeLifecycleRequest cleanupRequest = withEvidence(lifecycleRequest, prepared.evidence());
        try {
            if (prepared.enabled() && !persistLifecycle(input, profile, prepared.evidence())) {
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
                return persistCheckpoint(input, profile, executed);
            }
            if ("in_progress".equals(executed.status())) {
                return persistCheckpoint(input, profile, withLifecycle(executed, prepared.evidence()));
            }
            var cleaned = runtimeLifecycle.cleanup(cleanupRequest);
            if (!cleaned.successful()) {
                return persistCheckpoint(input, profile, lifecycleBlocked(input, cleaned));
            }
            return persistCheckpoint(input, profile, withLifecycle(executed, cleaned.evidence()));
        } catch (CancellationException exception) {
            cleanupAfterCancellation(input, profile, prepared, cleanupRequest, exception);
            throw exception;
        }
    }

    private void cleanupAfterCancellation(
            Input input,
            JsonNode profile,
            ExecutionRuntimeLifecycle.RuntimeLifecycleResult prepared,
            ExecutionRuntimeLifecycle.RuntimeLifecycleRequest cleanupRequest,
            CancellationException cancellation) {
        if (!prepared.enabled()) {
            return;
        }
        try {
            var cleaned = runtimeLifecycle.cleanup(cleanupRequest);
            if (!persistLifecycle(input, profile, cleaned.evidence())) {
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
            Input input,
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
            Input input, JsonNode profile, JsonNode workspace) {
        Map<String, Object> persisted = suiteState.read(input.projectName(), input.suiteRunId())
                .map(value -> value.get("runtimeLifecycle"))
                .filter(Map.class::isInstance).map(Map.class::cast).orElse(Map.of());
        return new ExecutionRuntimeLifecycle.RuntimeLifecycleRequest(
                input.projectName(), input.suiteRunId(), workspace, profile, persisted);
    }

    private static ExecutionRuntimeLifecycle.RuntimeLifecycleRequest withEvidence(
            ExecutionRuntimeLifecycle.RuntimeLifecycleRequest request, Map<String, Object> evidence) {
        return new ExecutionRuntimeLifecycle.RuntimeLifecycleRequest(
                request.projectName(), request.suiteRunId(), request.workspace(), request.profile(), evidence);
    }

    private boolean persistLifecycle(Input input, JsonNode profile, Map<String, Object> evidence) {
        Map<String, Object> payload = suiteState.read(input.projectName(), input.suiteRunId())
                .map(LinkedHashMap::new).orElseGet(LinkedHashMap::new);
        payload.put("resultType", "execution_orchestration");
        payload.put("action", "execute");
        payload.put("projectName", input.projectName());
        payload.put("executionProfile", input.executionProfile());
        payload.put("suiteRunId", input.suiteRunId());
        payload.put("suiteType", profile.path("suiteType").asText());
        payload.put("profileFingerprint", profileFingerprint(profile));
        payload.put("status", "in_progress");
        payload.put("statusArtifactPath", statusArtifactPath(input));
        payload.put("runtimeLifecycle", evidence);
        return suiteState.write(input.projectName(), input.suiteRunId(), Map.copyOf(payload)).isPresent();
    }

    private static ExecutionOrchestrationResult lifecycleBlocked(
            Input input, ExecutionRuntimeLifecycle.RuntimeLifecycleResult result) {
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

    private ProfileContext profile(Input input) {
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

    private ExecutionOrchestrationResult validateCheckpoint(Input input, JsonNode profile) {
        var checkpoint = suiteState.read(input.projectName(), input.suiteRunId());
        if (checkpoint.isEmpty()) {
            return null;
        }
        JsonNode artifact = mapper.valueToTree(checkpoint.get());
        boolean matches = input.projectName().equals(artifact.path("projectName").asText())
                && input.executionProfile().equals(artifact.path("executionProfile").asText())
                && profile.path("suiteType").asText().equals(artifact.path("suiteType").asText());
        if (!matches) {
            return blocked("execution_suite_checkpoint_context_mismatch",
                "resume the suiteRunId only with its original project and executionProfile", input);
        }
        String fingerprint = Input.text(artifact.path("profileFingerprint"));
        if (fingerprint == null || !fingerprint.equals(profileFingerprint(profile))) {
            return blocked("execution_suite_checkpoint_definition_mismatch",
                    "start a new suiteRunId after changing the execution profile definition", input);
        }
        return null;
    }

    private ExecutionOrchestrationResult persistCheckpoint(
            Input input, JsonNode profile, ExecutionOrchestrationResult result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("resultType", "execution_orchestration");
        payload.put("action", "execute");
        payload.put("projectName", input.projectName());
        payload.put("executionProfile", input.executionProfile());
        payload.put("suiteRunId", input.suiteRunId());
        payload.put("suiteType", profile.path("suiteType").asText());
        payload.put("profileFingerprint", profileFingerprint(profile));
        payload.put("status", result.status());
        payload.put("reasonCode", result.reasonCode());
        payload.put("executionPolicy", profile.path("executionPolicy").asText());
        payload.put("statusArtifactPath", statusArtifactPath(input));
        payload.putAll(result.details());
        payload.put("nextPlanOrder", nextPlanOrder(result.details()));
        if (suiteState.write(input.projectName(), input.suiteRunId(), Map.copyOf(payload)).isEmpty()) {
            return blocked("execution_suite_checkpoint_persist_failed", "restore suite checkpoint storage and retry", input);
        }
        Map<String, Object> details = new LinkedHashMap<>(result.details());
        details.put("statusArtifactPath", statusArtifactPath(input));
        return new ExecutionOrchestrationResult(
                result.status(), result.reasonCode(), result.nextAction(), result.reasonMeta(), details);
    }

    private static String statusArtifactPath(Input input) {
        return ".mcpjvm/" + input.projectName() + "/suite-runs/" + input.suiteRunId()
                + "/execution_orchestration.result.json";
    }

    private static int nextPlanOrder(Map<String, Object> details) {
        Object value = details.get("nextPlanOrder");
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static String profileFingerprint(JsonNode profile) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical(profile).getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder(digest.length * 2);
            for (byte entry : digest) {
                value.append(String.format("%02x", entry));
            }
            return value.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    private static String canonical(JsonNode node) {
        if (node.isObject()) {
            List<String> fields = new ArrayList<>();
            node.fieldNames().forEachRemaining(fields::add);
            fields.sort(String::compareTo);
            StringBuilder value = new StringBuilder("{");
            for (String field : fields) {
                if (value.length() > 1) {
                    value.append(',');
                }
                value.append(new ObjectMapper().valueToTree(field).toString()).append(':')
                        .append(canonical(node.path(field)));
            }
            return value.append('}').toString();
        }
        if (node.isArray()) {
            StringBuilder value = new StringBuilder("[");
            for (JsonNode entry : node) {
                if (value.length() > 1) {
                    value.append(',');
                }
                value.append(canonical(entry));
            }
            return value.append(']').toString();
        }
        return node.toString();
    }

    private static ProfileContext selectedProfile(JsonNode workspaces, String executionProfile) {
        ProfileContext selected = null;
        for (JsonNode workspace : workspaces) {
            for (JsonNode profile : workspace.path("executionProfiles")) {
                if (executionProfile.equals(profile.path("executionProfile").asText())) {
                    if (selected != null) {
                        return null;
                    }
                    selected = new ProfileContext(profile, workspace);
                }
            }
        }
        return selected;
    }

    private ExecutionOrchestrationResult runProfile(Input input, JsonNode profile, JsonNode workspace) {
        if (!profile.path("plans").isArray() || profile.path("plans").isEmpty()) {
            return blocked("execution_profile_invalid", "configure at least one ordered plan", input);
        }
        String suiteType = profile.path("suiteType").asText();
        Map<String, Object> suiteContext = suiteContext(input);
        List<Map<String, Object>> prior = terminalOutcomes(
                input, suiteType, profile.path("plans"), suiteContext);
        List<JsonNode> pending = pendingPlans(input, suiteType, profile.path("plans"));
        if (pending.isEmpty()) {
            return withSuiteContext(completed(input, profile, prior, false), suiteContext);
        }
        List<JsonNode> plans = limited(pending, input.maxPlansPerCall());
        List<Map<String, Object>> outcomes = new ArrayList<>();
        for (JsonNode plan : plans) {
            checkpoint();
            Map<String, Object> outcome = runPlan(input, new ExecutionContext(profile, workspace, suiteContext), suiteType, plan);
            checkpoint();
            outcome = redactOutcomeContext(outcome);
            outcome = persist(input, suiteType, plan, outcome);
            outcomes.add(outcome);
            promoteContext(suiteContext, plan, outcome);
            ExecutionOrchestrationResult progress = persistCheckpoint(
                    input, profile, withSuiteContext(
                            completed(input, profile, merged(prior, outcomes), true), suiteContext));
            if ("execution_suite_checkpoint_persist_failed".equals(progress.reasonCode())) {
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

    private Map<String, Object> suiteContext(Input input) {
        return suiteState.read(input.projectName(), input.suiteRunId()).map(value -> value.get("suiteContext"))
                .filter(Map.class::isInstance).map(Map.class::cast).map(ExecuteExecutionOrchestrationAction::safeContext)
                .orElseGet(LinkedHashMap::new);
    }

    private List<Map<String, Object>> terminalOutcomes(
            Input input,
            String suiteType,
            JsonNode plans,
            Map<String, Object> suiteContext) {
        List<Map<String, Object>> outcomes = new ArrayList<>();
        for (JsonNode plan : ordered(plans)) {
            Map<String, Object> outcome = persistedOutcome(input, suiteType, plan);
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

    private List<JsonNode> pendingPlans(Input input, String suiteType, JsonNode values) {
        List<JsonNode> pending = new ArrayList<>();
        for (JsonNode plan : ordered(values)) {
            if (!persistedTerminal(input, suiteType, plan)) {
                pending.add(plan);
            }
        }
        return List.copyOf(pending);
    }

    private boolean persistedTerminal(Input input, String suiteType, JsonNode plan) {
        return persistedOutcome(input, suiteType, plan) != null;
    }

    private Map<String, Object> persistedOutcome(Input input, String suiteType, JsonNode plan) {
        String planName = Input.text(plan.path("planName"));
        if (planName == null) {
            return null;
        }
        ObjectNode request = mapper.createObjectNode();
        request.put("projectName", input.projectName());
        request.put("suiteType", suiteType);
        request.put("planName", planName);
        request.put("runId", runId(input, plan.path("order").asInt()));
        var result = artifacts.execute(new ArtifactManagementRequest(ArtifactType.RUN_RESULT, ArtifactAction.READ, request));
        if (!"ok".equals(result.status())) {
            return null;
        }
        JsonNode artifact = mapper.valueToTree(result.details().get("artifact"));
        if (!List.of("pass", "fail", "blocked", "completed", "skipped", "partial_fail")
                .contains(artifact.path("status").asText())) {
            return null;
        }
        Map<String, Object> outcome = mapper.convertValue(artifact, Map.class);
        outcome.put("order", plan.path("order").asInt());
        outcome.put("planName", planName);
        outcome.put("runId", runId(input, plan.path("order").asInt()));
        return Map.copyOf(outcome);
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

    private Map<String, Object> runPlan(Input input, ExecutionContext context, String suiteType, JsonNode plan) {
        String planName = Input.text(plan.path("planName"));
        if (planName == null) {
            return outcome(plan, "blocked", "execution_profile_invalid", Map.of());
        }
        JsonNode artifact = planArtifact(input.projectName(), suiteType, planName);
        if (artifact == null) {
            return outcome(plan, "blocked", "plan_artifact_missing", Map.of("planName", planName));
        }
        String runId = runId(input, plan.path("order").asInt());
        ObjectNode envelope = envelope(input, context, suiteType, planName, artifact);
        if ("performance".equals(suiteType) && !addRunDirectory(envelope, input, suiteType, planName, runId)) {
            return outcome(plan, "blocked", "execution_run_directory_unavailable", Map.of("runId", runId));
        }
        return route(plan, suiteType, envelope);
    }

    private boolean addRunDirectory(
            ObjectNode envelope, Input input, String suiteType, String planName, String runId) {
        return runDirectories.resolve(input.projectName(), suiteType, planName, runId)
                .map(directory -> {
                    envelope.put("runDirectory", directory);
                    return true;
                }).orElse(false);
    }

    private Map<String, Object> persist(Input input, String suiteType, JsonNode plan, Map<String, Object> outcome) {
        String planName = plan.path("planName").asText();
        String runId = runId(input, plan.path("order").asInt());
        ObjectNode request = mapper.createObjectNode();
        request.put("projectName", input.projectName());
        request.put("suiteType", suiteType);
        request.put("planName", planName);
        request.put("runId", runId);
        request.set("payload", runPayload(input, outcome, runId));
        var persisted = artifacts.execute(new ArtifactManagementRequest(
                ArtifactType.RUN_RESULT, ArtifactAction.UPSERT, request));
        if (!"ok".equals(persisted.status())) {
            return outcome(plan, "blocked", "execution_run_artifact_persist_failed", Map.of("runId", runId));
        }
        Map<String, Object> details = new LinkedHashMap<>(outcome);
        details.put("runId", runId);
        return Map.copyOf(details);
    }

    private ObjectNode runPayload(Input input, Map<String, Object> outcome, String runId) {
        ObjectNode payload = mapper.valueToTree(outcome);
        payload.put("status", persistedStatus(outcome));
        payload.put("executionProfile", input.executionProfile());
        payload.put("suiteRunId", input.suiteRunIdOr(runId));
        payload.put("startedAt", Instant.now().toString());
        payload.put("endedAt", Instant.now().toString());
        return payload;
    }

    private static String persistedStatus(Map<String, Object> outcome) {
        Object details = outcome.get("details");
        if (details instanceof Map<?, ?> values && values.get("runStatus") instanceof String status) {
            return status;
        }
        return String.valueOf(outcome.get("status"));
    }

    private static String runId(Input input, int order) {
        return input.suiteRunId() == null ? "orchestration-" + order : input.suiteRunId() + "-" + order;
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

    private ObjectNode envelope(Input input, ExecutionContext context, String suiteType, String planName, JsonNode artifact) {
        ObjectNode value = mapper.createObjectNode();
        value.put("projectName", input.projectName());
        value.put("executionProfile", input.executionProfile());
        value.put("planName", planName);
        value.set("metadata", artifact.path("metadata"));
        value.set("contract", artifact.path("contract"));
        value.set("suiteContext", mapper.valueToTree(context.suiteContext()));
        if (input.suiteRunId() != null) {
            value.put("suiteRunId", input.suiteRunId());
        }
        if ("security".equals(suiteType)) {
            value.set("credentialBindings", context.workspace().path("variables").path("contextBindings"));
            ObjectNode source = value.putObject("credentialSource");
            source.put("workspaceRoot", context.workspace().path("projectRoot").asText());
            source.put("envFile", context.workspace().path("envFile").asText());
            source.set("scripts", context.workspace().path("scripts"));
            source.set("profileScriptRefs", context.profile().path("scriptRefs"));
        }
        return value;
    }

    private static void promoteContext(Map<String, Object> suiteContext, JsonNode plan, Map<String, Object> outcome) {
        suiteContext.putAll(safeContext(plan.path("providedContext")));
        Object details = outcome.get("details");
        if (details instanceof Map<?, ?> map) {
            Object promoted = map.get("suiteContext");
            if (promoted instanceof Map<?, ?> values) {
                suiteContext.putAll(safeContext(values));
            }
        }
    }

    private static Map<String, Object> redactOutcomeContext(Map<String, Object> outcome) {
        if (!(outcome.get("details") instanceof Map<?, ?> values)) {
            return outcome;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        values.forEach((key, value) -> details.put(String.valueOf(key), value));
        if (details.containsKey("suiteContext")) {
            details.put("suiteContext", safeContext(details.get("suiteContext")));
        }
        Map<String, Object> redacted = new LinkedHashMap<>(outcome);
        redacted.put("details", Map.copyOf(details));
        return Map.copyOf(redacted);
    }

    private static Map<String, Object> safeContext(Object value) {
        Map<String, Object> safe = new LinkedHashMap<>();
        if (value instanceof JsonNode node && node.isObject()) {
            node.fields().forEachRemaining(entry -> addSafeContext(safe, entry.getKey(), nodeValue(entry.getValue())));
        } else if (value instanceof Map<?, ?> map) {
            map.forEach((key, entry) -> addSafeContext(safe, String.valueOf(key), entry));
        }
        return safe;
    }

    private static void addSafeContext(Map<String, Object> safe, String key, Object value) {
        String normalized = key.toLowerCase(java.util.Locale.ROOT);
        if (PROMOTABLE_CONTEXT_KEYS.contains(normalized) && safeValue(value)) {
            safe.put(key, value);
        }
    }

    private static Object nodeValue(JsonNode value) {
        if (value.isTextual()) {
            return value.asText();
        }
        if (value.isNumber() || value.isBoolean()) {
            return value;
        }
        return null;
    }

    private static boolean safeValue(Object value) {
        if (!(value instanceof String) && !(value instanceof Number) && !(value instanceof Boolean)) {
            return false;
        }
        return !(value instanceof String text) || text.length() <= MAX_CONTEXT_VALUE_LENGTH;
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
        var result = performance.execute(new PerformanceSuiteRequest(PerformanceSuiteAction.EXECUTE_PLAN, input));
        return outcome(plan, result.status(), result.reasonCode(), result.details());
    }

    private Map<String, Object> security(JsonNode plan, JsonNode input) {
        var result = security.execute(new SecuritySuiteRequest(SecuritySuiteAction.EXECUTE_PLAN, input));
        return outcome(plan, result.status(), result.reasonCode(), result.details());
    }

    private Map<String, Object> regression(JsonNode plan, JsonNode input) {
        var result = regression.execute(new RegressionSuiteRequest(RegressionSuiteAction.EXECUTE_PLAN, input));
        return outcome(plan, result.status(), result.reasonCode(), result.details());
    }

    private static boolean shouldStop(JsonNode profile, JsonNode plan, Map<String, Object> outcome) {
        String status = String.valueOf(outcome.get("status"));
        Object details = outcome.get("details");
        boolean suiteFailed = details instanceof Map<?, ?> values && "fail".equals(values.get("runStatus"));
        if (("completed".equals(status) || "ready".equals(status)) && !suiteFailed) {
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

    private static ExecutionOrchestrationResult completed(
            Input input, JsonNode profile, List<Map<String, Object>> outcomes, boolean partial) {
        boolean aggregateFailure = outcomes.stream().anyMatch(ExecuteExecutionOrchestrationAction::suiteFailed);
        String status = resultStatus(partial, aggregateFailure);
        String code = resultCode(partial, aggregateFailure);
        String next = partial ? "resume with suiteRunId to execute remaining plans" : null;
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("action", "execute");
        details.put("executionProfile", input.executionProfile());
        details.put("executionPolicy", profile.path("executionPolicy").asText());
        details.put("suiteRunId", input.suiteRunId());
        Map<String, Object> progress = Map.of("completedPlanCount", outcomes.size(), "status", status,
                "failedPlanCount", outcomes.stream().filter(ExecuteExecutionOrchestrationAction::suiteFailed).count());
        details.put("progressSummary", progress);
        details.put("progress", progress);
        details.put("planRuns", outcomes);
        if (partial && !outcomes.isEmpty()) {
            details.put("nextPlanOrder", ((Number) outcomes.getLast().get("order")).intValue() + 1);
        }
        return new ExecutionOrchestrationResult(status, code, next, input.metadata(), details);
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
        return !List.of("pass", "completed", "ready", "skipped").contains(String.valueOf(outcome.get("status")));
    }

    private static void checkpoint() {
        if (Thread.currentThread().isInterrupted()
                || OperationExecutionContext.current().cancellationRequested()) {
            throw new CancellationException("execution orchestration was cancelled");
        }
    }

    private static ExecutionOrchestrationResult failed(
            Input input, JsonNode profile, List<Map<String, Object>> outcomes, String reasonCode) {
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

    private static ExecutionOrchestrationResult blocked(String reasonCode, String nextAction, Input input) {
        return ExecutionOrchestrationResult.blocked(reasonCode, nextAction, input.metadata());
    }

    private record Input(String projectName, String executionProfile, String suiteRunId, int maxPlansPerCall,
            ExecutionOrchestrationResult failure) {
        private static Input from(JsonNode node) {
            String project = text(node.path("projectName"));
            String profile = text(node.path("executionProfile"));
            if (project == null) {
                return invalid("project_name_required", "provide a non-empty projectName");
            }
            if (profile == null) {
                return invalid("execution_profile_required", "provide a non-empty executionProfile");
            }
            String runId = text(node.path("suiteRunId"));
            if (runId != null && !RUN_ID.matcher(runId).matches()) {
                return invalid("suite_run_id_invalid", "provide a safe suiteRunId");
            }
            int maximum = maximum(node.path("maxPlansPerCall"));
            return maximum < 0 ? invalid("max_plans_per_call_invalid", "provide a positive integer maxPlansPerCall")
                    : new Input(project, profile, runId, maximum, null);
        }

        private static int maximum(JsonNode node) {
            if (node.isMissingNode() || node.isNull()) {
                return 0;
            }
            return node.canConvertToInt() && node.asInt() > 0 ? node.asInt() : -1;
        }

        private static Input invalid(String code, String nextAction) {
            return new Input(null, null, null, 0, ExecutionOrchestrationResult.blocked(code, nextAction, Map.of()));
        }

        private static String text(JsonNode node) {
            return node.isTextual() && !node.asText().isBlank() ? node.asText().trim() : null;
        }

        private Map<String, Object> metadata() {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("projectName", projectName);
            metadata.put("executionProfile", executionProfile);
            if (suiteRunId != null) {
                metadata.put("suiteRunId", suiteRunId);
            }
            return Map.copyOf(metadata);
        }

        private String suiteRunIdOr(String fallback) {
            return suiteRunId == null ? fallback : suiteRunId;
        }

        private Input withAllocatedSuiteRunId() {
            if (suiteRunId != null) {
                return this;
            }
            String allocated = "suite-" + Instant.now().toEpochMilli();
            return new Input(projectName, executionProfile, allocated, maxPlansPerCall, failure);
        }
    }

    private record ProfileContext(JsonNode profile, JsonNode workspace) {
    }

    private record ExecutionContext(JsonNode profile, JsonNode workspace, Map<String, Object> suiteContext) {
    }
}
