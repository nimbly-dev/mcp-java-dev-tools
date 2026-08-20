package com.nimbly.mcpjavadevtools.server.mcp.tools.artifactmanagement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactManagementAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactType;

/** Builds the action-correlated public schema for artifact_management. */
public final class ArtifactManagementMcpSchema {

    private ArtifactManagementMcpSchema() {
    }

    /** @return JSON Schema with one typed branch for every approved family/action pair */
    public static String publicInputSchema(ObjectMapper mapper) {
        ObjectNode root = object(mapper);
        ObjectNode properties = root.putObject("properties");
        enumString(properties, "artifactType", artifactTypes());
        enumString(properties, "action", artifactActions());
        // The branch schemas own the action-specific input shape.  Keeping the
        // root envelope permissive avoids applying an empty root object schema
        // before oneOf selects the correlated action branch.
        properties.putObject("input").put("type", "object").put("additionalProperties", true);
        required(root, "artifactType", "action", "input");
        ArrayNode branches = root.putArray("oneOf");
        for (ArtifactManagementAction action : ArtifactManagementAction.values()) {
            addBranch(branches, action, mapper);
        }
        return root.toPrettyString();
    }

    private static void addBranch(
            ArrayNode branches,
            ArtifactManagementAction action,
            ObjectMapper mapper) {
        ObjectNode branch = object(mapper);
        ObjectNode properties = branch.putObject("properties");
        properties.putObject("artifactType").put("const", action.artifactType().value());
        properties.putObject("action").put("const", action.action().value());
        properties.set("input", inputSchema(action, mapper));
        required(branch, "artifactType", "action", "input");
        branches.add(branch);
    }

    private static ObjectNode inputSchema(ArtifactManagementAction action, ObjectMapper mapper) {
        ObjectNode input = object(mapper);
        switch (action) {
            case PROBE_CONFIG_READ, PROBE_CONFIG_VALIDATE, PROBE_CONFIG_UPSERT, PROBE_CONFIG_RELOAD ->
                    addOptionalObject(input, "payload");
            case PROJECT_CONTEXT_READ, PROJECT_CONTEXT_VALIDATE, PROJECT_CONTEXT_UPSERT, PROJECT_CONTEXT_LIST ->
                    addProjectInput(input);
            case PERFORMANCE_PLAN_READ, PERFORMANCE_PLAN_VALIDATE,
                    REGRESSION_PLAN_READ, REGRESSION_PLAN_VALIDATE,
                    SECURITY_PLAN_READ, SECURITY_PLAN_VALIDATE,
                    PERFORMANCE_PLAN_LIST, REGRESSION_PLAN_LIST, SECURITY_PLAN_LIST -> addPlanSelector(input);
            case PERFORMANCE_PLAN_UPSERT, REGRESSION_PLAN_UPSERT, SECURITY_PLAN_UPSERT -> addPlanUpsert(input);
            case RUN_RESULT_READ, RUN_RESULT_LIST, RUN_RESULT_REBUILD, RUN_RESULT_BACKFILL,
                    RUN_RESULT_CUTOVER, RUN_RESULT_QUERY, RUN_RESULT_CLEANUP -> addRunInput(input, action);
            case EXECUTION_EXPORT_READ, EXECUTION_EXPORT_LIST, EXECUTION_EXPORT_GENERATE ->
                    addExportInput(input, action);
            default -> {
                // Actions without additional fields accept the empty object only.
            }
        }
        return input;
    }

    private static void addProjectInput(ObjectNode input) {
        addString(input, "projectName");
        addString(input, "projectRootAbs");
        addOptionalObject(input, "payload");
        addBooleanDefault(input, "replace", false);
        addQuery(input);
    }

    private static void addPlanUpsert(ObjectNode input) {
        addPlanSelector(input);
        addRequiredObject(input, "payload");
        required(input, "planName", "payload");
    }

    private static void addRunInput(
            ObjectNode input,
            ArtifactManagementAction action) {
        addRunCommonSelectors(input);
        if (action == ArtifactManagementAction.RUN_RESULT_CLEANUP) {
            required(input, "projectName");
        } else if (action == ArtifactManagementAction.RUN_RESULT_BACKFILL) {
            input.with("properties").with("stateSurface").put("const", "correlation_state");
            required(input, "projectName", "stateSurface");
        } else if (action != ArtifactManagementAction.RUN_RESULT_READ
                && action != ArtifactManagementAction.RUN_RESULT_LIST) {
            required(input, "projectName");
        }
    }

    private static void addExportInput(
            ObjectNode input,
            ArtifactManagementAction action) {
        addString(input, "projectName");
        addEnum(input, "mode", "ps1", "sh", "postman");
        addString(input, "planName");
        addString(input, "executionProfile");
        addBooleanDefault(input, "includeResolvedSecrets", false);
        addBooleanDefault(input, "includeRuntimeStartup", false);
        addBooleanDefault(input, "includeHealthcheckGate", false);
        addString(input, "when");
        addStringMap(input, "contextBindings");
        addStringMap(input, "contextValues");
        addQuery(input);
        if (action == ArtifactManagementAction.EXECUTION_EXPORT_READ) {
            required(input, "projectName", "query");
        } else if (action == ArtifactManagementAction.EXECUTION_EXPORT_LIST) {
            required(input, "projectName");
        } else {
            required(input, "projectName", "mode");
        }
    }

    private static void addPlanSelector(ObjectNode input) {
        addString(input, "projectName");
        addString(input, "planName");
        addOptionalObject(input, "payload");
        addPlanQuery(input);
    }

    private static void addRunCommonSelectors(ObjectNode input) {
        addString(input, "projectName");
        addEnum(input, "suiteType", "regression", "performance", "security");
        addString(input, "planName");
        addString(input, "runId");
        addString(input, "projectRootAbs");
        addString(input, "executionProfile");
        addBooleanDefault(input, "strict", false);
        addEnum(input, "stateSurface", "run_state", "correlation_state", "watcher_state",
                "external_verification_state");
        addScope(input);
        addRetention(input);
        addQuery(input);
    }

    private static void addScope(ObjectNode input) {
        ObjectNode scope = addObject(input, "scope");
        ObjectNode stateSurfaces = scope.with("properties").putObject("stateSurfaces");
        stateSurfaces.put("type", "array").put("minItems", 1).put("maxItems", 4).put("uniqueItems", true);
        ArrayNode surfaces = stateSurfaces.putObject("items").put("type", "string").putArray("enum");
        surfaces.add("run_state").add("correlation_state").add("watcher_state").add("external_verification_state");
    }

    private static void addRetention(ObjectNode input) {
        ObjectNode retention = addObject(input, "retention");
        addBooleanDefault(retention, "dryRun", true);
        addIntegerDefault(retention, "terminalOlderThanDays", 90);
        addIntegerDefault(retention, "keepMostRecentTerminalRuns", 1000);
        addIntegerDefault(retention, "maxDeleteBatch", 500);
    }

    private static void addQuery(ObjectNode input) {
        ObjectNode query = addObject(input, "query");
        query.put("additionalProperties", true);
        addQuerySelectors(query);
        addQueryFilters(query);
        addQueryDetails(query);
        addQueryPaging(query);
    }

    private static void addQuerySelectors(ObjectNode query) {
        ObjectNode properties = query.with("properties");
        properties.putObject("select").put("type", "array").putObject("items").put("type", "string");
        for (String field : new String[] {
                "exportId", "executionProfile", "planName", "runId", "suiteRunId", "activePhase"}) {
            properties.putObject(field).put("type", "string");
        }
        for (String field : new String[] {
                "startedFromEpochMs", "startedToEpochMs", "completedFromEpochMs", "completedToEpochMs"}) {
            addInteger(query, field);
        }
        addInteger(query, "pageSize");
        addString(query, "cursor");
        addEnum(query, "sortDirection", "asc", "desc").put("default", "desc");
        addStringOrArray(query, "status");
    }

    private static void addQueryFilters(ObjectNode query) {
        addObject(query, "filters").put("additionalProperties", true);
        ObjectNode filters = query.with("properties").with("filters");
        for (String field : new String[] {
                "projectName", "suiteType", "planName", "runId", "status", "reasonCode",
                "correlationSessionId", "watcherName", "watcherStatus", "providerType",
                "suiteRunId", "keyType", "keyValueExact", "keyValueSha256", "strictLineKey",
                "probeId", "logicalServiceId", "runtimeInstanceId", "outcome"}) {
            addString(filters, field);
        }
        for (String field : new String[] {
                "startedFromEpochMs", "startedToEpochMs", "correlatedFromEpochMs", "correlatedToEpochMs",
                "deadlineFromEpochMs", "deadlineToEpochMs", "completedFromEpochMs", "completedToEpochMs"}) {
            addInteger(filters, field);
        }
        addStringOrArray(filters, "status");
        addStringOrArray(filters, "outcome");
    }

    private static void addQueryDetails(ObjectNode query) {
        ObjectNode detail = addObject(query, "detail");
        detail.put("additionalProperties", true);
        addStringArray(detail, "select");
        addWindow(detail, "keys");
        addWindow(detail, "lineExpectations");
        addWindow(detail, "probeObservations");
        addBooleanDefault(detail, "continuation", false);
        addBooleanDefault(detail, "lastObservation", false);
        addBooleanDefault(detail, "lastAssertion", false);
        addBooleanDefault(detail, "ownerLease", false);
        addBooleanDefault(detail, "observations", false);
        addBooleanDefault(detail, "assertions", false);
        addBooleanDefault(detail, "owner", false);
        addBooleanDefault(detail, "lease", false);
        addWindow(detail, "attempts");
    }

    private static void addQueryPaging(ObjectNode query) {
        ObjectNode page = addObject(query, "page");
        page.put("additionalProperties", false);
        addIntegerDefault(page, "pageSize", 25);
        ObjectNode pageCursor = page.with("properties").putObject("cursor");
        pageCursor.putArray("type").add("string").add("null");
        ObjectNode sort = addObject(query, "sort");
        addEnum(sort, "field", "startedAtEpochMs", "updatedAtEpochMs");
        addEnum(sort, "direction", "asc", "desc");
        ObjectNode watchers = addObject(query, "watchers");
        addIntegerDefault(watchers, "offset", 0);
        addIntegerDefault(watchers, "limit", 25);
        ObjectNode watcherEvidence = addObject(query, "watcherEvidence");
        addIntegerDefault(watcherEvidence, "offset", 0);
        addIntegerDefault(watcherEvidence, "limit", 25);
        ObjectNode watcherFilter = addObject(query, "watcherFilter");
        addString(watcherFilter, "watcherId");
        addEnum(watcherFilter, "watcherStatus", "pass", "fail_assertion", "blocked_dependency", "blocked_runtime");
        query.with("properties").with("pageSize").put("minimum", 1).put("maximum", 100).put("default", 10);
    }

    private static void addPlanQuery(ObjectNode input) {
        ObjectNode query = addObject(input, "query");
        query.put("additionalProperties", true);
        addStringArray(query, "select");
        addWindow(query, "prerequisites");
        addWindow(query, "steps");
    }

    private static ObjectNode addObject(ObjectNode parent, String name) {
        ObjectNode value = parent.with("properties").putObject(name);
        value.put("type", "object");
        value.put("additionalProperties", false);
        return value;
    }

    private static void addRequiredObject(ObjectNode parent, String name) {
        ObjectNode value = parent.with("properties").putObject(name);
        value.put("type", "object");
        value.put("additionalProperties", true);
        required(parent, name);
    }

    private static void addOptionalObject(ObjectNode parent, String name) {
        parent.with("properties").putObject(name)
                .put("type", "object")
                .put("additionalProperties", true);
    }

    private static void addInteger(ObjectNode parent, String name) {
        parent.with("properties").putObject(name).put("type", "integer").put("minimum", 0);
    }

    private static void addStringArray(ObjectNode parent, String name) {
        parent.with("properties").putObject(name).put("type", "array")
                .putObject("items").put("type", "string");
    }

    private static void addStringMap(ObjectNode parent, String name) {
        parent.with("properties").putObject(name).put("type", "object")
                .put("additionalProperties", true);
    }

    private static void addWindow(ObjectNode parent, String name) {
        ObjectNode window = addObject(parent, name);
        addInteger(window, "offset");
        addInteger(window, "limit");
    }

    private static void addStringOrArray(ObjectNode parent, String name) {
        ObjectNode value = parent.with("properties").putObject(name);
        value.putArray("type").add("string").add("array");
        value.putObject("items").put("type", "string");
    }

    private static void addString(ObjectNode parent, String name) {
        parent.with("properties").putObject(name).put("type", "string");
    }

    private static ObjectNode addEnum(ObjectNode parent, String name, String... values) {
        ObjectNode property = parent.with("properties").putObject(name);
        property.put("type", "string");
        ArrayNode allowed = property.putArray("enum");
        for (String value : values) {
            allowed.add(value);
        }
        return property;
    }

    private static void addBooleanDefault(ObjectNode parent, String name, boolean value) {
        parent.with("properties").putObject(name).put("type", "boolean").put("default", value);
    }

    private static void addIntegerDefault(ObjectNode parent, String name, int value) {
        parent.with("properties").putObject(name).put("type", "integer").put("default", value);
    }

    private static ObjectNode object(ObjectMapper mapper) {
        ObjectNode value = mapper.createObjectNode();
        value.put("type", "object");
        value.put("additionalProperties", false);
        return value;
    }

    private static void required(ObjectNode object, String... fields) {
        ArrayNode values = object.putArray("required");
        for (String field : fields) {
            values.add(field);
        }
    }

    private static String[] artifactTypes() {
        String[] values = new String[ArtifactType.values().length];
        for (int index = 0; index < ArtifactType.values().length; index++) {
            values[index] = ArtifactType.values()[index].value();
        }
        return values;
    }

    private static String[] artifactActions() {
        String[] values = new String[ArtifactAction.values().length];
        for (int index = 0; index < ArtifactAction.values().length; index++) {
            values[index] = ArtifactAction.values()[index].value();
        }
        return values;
    }

    private static void enumString(ObjectNode properties, String name, String... values) {
        ObjectNode property = properties.putObject(name).put("type", "string");
        ArrayNode allowed = property.putArray("enum");
        for (String value : values) {
            allowed.add(value);
        }
    }
}
