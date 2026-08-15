package com.nimbly.mcpjavadevtools.server.mcp.tools.routesynthesis;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Schema-only create_recipe branch matching the TypeScript request union. */
public record RouteSynthesisMcpCreateRecipeInputSchema(
        @JsonProperty(required = true) String projectRootAbs,
        @Nullable List<String> additionalSourceRoots,
        @JsonProperty(required = true) String classHint,
        @JsonProperty(required = true) String methodHint,
        @Nullable @Schema(minimum = "1", requiredMode = Schema.RequiredMode.NOT_REQUIRED) Integer lineHint,
        @Nullable String probeId,
        @Nullable String probeBaseUrl,
        @Nullable String mappingsBaseUrl,
        @Nullable @Schema(allowableValues = {"static_only", "runtime_first", "runtime_only"},
                requiredMode = Schema.RequiredMode.NOT_REQUIRED) String discoveryPreference,
        @Nullable String apiBasePath,
        @JsonProperty(required = true)
        @Schema(allowableValues = {"line_probe", "regression"}) String intentMode,
        @Nullable String authToken,
        @Nullable String authUsername,
        @Nullable String authPassword,
        @Nullable Boolean actuationEnabled,
        @Nullable Boolean actuationReturnBoolean,
        @Nullable String actuationActuatorId,
        @Nullable String outputTemplate) {
}
