package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.operation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.RouteSynthesisAction;
import com.nimbly.mcpjavadevtools.server.core.operation.schema.CanonicalOperationSchema;
import com.nimbly.mcpjavadevtools.server.core.operation.schema.OperationSchema;

/** Builds the typed request schemas owned by the Route Synthesis operations. */
final class RouteSynthesisOperationSchemas {

    private RouteSynthesisOperationSchemas() {
    }

    static OperationSchema forAction(RouteSynthesisAction action) {
        ObjectNode root = CanonicalOperationSchema.object();
        CanonicalOperationSchema.string(root, "projectRootAbs");
        ObjectNode roots = root.with("properties").putObject("additionalSourceRoots").put("type", "array");
        roots.put("maxItems", 10).putObject("items").put("type", "string");
        CanonicalOperationSchema.required(root, "projectRootAbs");
        addActionProperties(root, action);
        return CanonicalOperationSchema.schema(root);
    }

    static void addActionProperties(ObjectNode root, RouteSynthesisAction action) {
        switch (action) {
            case INFER_TARGET -> addInferTarget(root);
            case CLASS_METHODS, DISCOVER_HANDLERS -> addDiscovery(root);
            case CREATE_RECIPE -> addCreateRecipe(root);
        }
    }

    static void addInferTarget(ObjectNode root) {
        CanonicalOperationSchema.string(root, "classHint");
        CanonicalOperationSchema.string(root, "methodHint");
        root.with("properties").putObject("lineHint").put("type", "integer").put("minimum", 1);
        root.with("properties").putObject("maxCandidates").put("type", "integer").put("minimum", 1);
        addProbeSelector(root);
    }

    static void addDiscovery(ObjectNode root) {
        CanonicalOperationSchema.string(root, "classHint");
        addProbeSelector(root);
    }

    static void addCreateRecipe(ObjectNode root) {
        CanonicalOperationSchema.string(root, "classHint");
        CanonicalOperationSchema.string(root, "methodHint");
        root.with("properties").putObject("lineHint").put("type", "integer").put("minimum", 1);
        CanonicalOperationSchema.string(root, "mappingsBaseUrl");
        CanonicalOperationSchema.enumString(root, "discoveryPreference",
                "static_only", "runtime_first", "runtime_only");
        CanonicalOperationSchema.string(root, "apiBasePath");
        CanonicalOperationSchema.enumString(root, "intentMode", "line_probe", "regression");
        CanonicalOperationSchema.string(root, "authToken");
        CanonicalOperationSchema.string(root, "authUsername");
        CanonicalOperationSchema.string(root, "authPassword");
        CanonicalOperationSchema.booleanValue(root, "actuationEnabled");
        CanonicalOperationSchema.booleanValue(root, "actuationReturnBoolean");
        CanonicalOperationSchema.string(root, "actuationActuatorId");
        CanonicalOperationSchema.string(root, "outputTemplate");
        addProbeSelector(root);
        CanonicalOperationSchema.required(root, "projectRootAbs", "classHint", "methodHint", "intentMode");
    }

    static void addProbeSelector(ObjectNode root) {
        CanonicalOperationSchema.string(root, "probeId");
        CanonicalOperationSchema.string(root, "probeBaseUrl");
    }
}
