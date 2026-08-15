package com.nimbly.mcpjavadevtools.server.mcp.tools.routesynthesis;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Superset MCP input mapped into one typed Route Synthesis action request.
 */
@Schema(oneOf = {RouteSynthesisMcpTargetInputSchema.class, RouteSynthesisMcpCreateRecipeInputSchema.class})
public record RouteSynthesisMcpActionInput(
        @JsonProperty(required = true) String projectRootAbs,
        @Nullable List<String> additionalSourceRoots,
        @Nullable String classHint,
        @Nullable String methodHint,
        @Nullable @Schema(minimum = "1", requiredMode = Schema.RequiredMode.NOT_REQUIRED) Integer lineHint,
        @Nullable @Schema(minimum = "1", requiredMode = Schema.RequiredMode.NOT_REQUIRED) Integer maxCandidates,
        @Nullable String probeId,
        @Nullable String probeBaseUrl,
        @Nullable String mappingsBaseUrl,
        @Nullable @Schema(allowableValues = {"static_only", "runtime_first", "runtime_only"},
                requiredMode = Schema.RequiredMode.NOT_REQUIRED) String discoveryPreference,
        @Nullable String apiBasePath,
        @Nullable @Schema(allowableValues = {"line_probe", "regression"},
                requiredMode = Schema.RequiredMode.NOT_REQUIRED) String intentMode,
        @Nullable String authToken,
        @Nullable String authUsername,
        @Nullable String authPassword,
        @Nullable Boolean actuationEnabled,
        @Nullable Boolean actuationReturnBoolean,
        @Nullable String actuationActuatorId,
        @Nullable String outputTemplate) {
}
