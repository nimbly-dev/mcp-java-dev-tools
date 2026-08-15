package com.nimbly.mcpjavadevtools.server.mcp.tools.routesynthesis;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Schema-only target action branch matching the TypeScript request union. */
public record RouteSynthesisMcpTargetInputSchema(
        @JsonProperty(required = true) String projectRootAbs,
        @Nullable List<String> additionalSourceRoots,
        @Nullable String classHint,
        @Nullable String methodHint,
        @Nullable @Schema(minimum = "1", requiredMode = Schema.RequiredMode.NOT_REQUIRED) Integer lineHint,
        @Nullable @Schema(minimum = "1", requiredMode = Schema.RequiredMode.NOT_REQUIRED) Integer maxCandidates,
        @Nullable String probeId,
        @Nullable String probeBaseUrl) {
}
