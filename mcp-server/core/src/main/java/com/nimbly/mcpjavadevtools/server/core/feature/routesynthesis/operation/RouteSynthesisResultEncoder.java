package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.operation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.RouteSynthesisAction;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.result.RouteSynthesisResult;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.BoundedOperationResultEncoder;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

/**
 * Applies the MCPJVM-621 compatibility mapping from Java owner {@code spring_http} to the released
 * TypeScript/CDE {@code spring} synthesizer identity at the Core directory boundary.
 */
final class RouteSynthesisResultEncoder implements BoundedOperationResultEncoder<RouteSynthesisResult> {

    private final RouteSynthesisAction action;
    private final ObjectMapper mapper;
    private final BoundedOperationResultEncoder<RouteSynthesisResult> delegate;

    RouteSynthesisResultEncoder(
            RouteSynthesisAction action,
            ObjectMapper mapper,
            BoundedOperationResultEncoder<RouteSynthesisResult> delegate) {
        this.action = Objects.requireNonNull(action, "action must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    }

    @Override
    public JsonNode encode(RouteSynthesisResult result) {
        JsonNode encoded = delegate.encode(result);
        if (action == RouteSynthesisAction.CREATE_RECIPE
                && encoded.path("actionResult") instanceof ObjectNode actionResult
                && "spring_http".equals(actionResult.path("synthesizerUsed").asText())) {
            actionResult.put("synthesizerUsed", "spring");
        }
        return encoded;
    }

    @Override
    public void write(RouteSynthesisResult result, OutputStream output) throws IOException {
        if (action == RouteSynthesisAction.CREATE_RECIPE) {
            mapper.writeValue(output, encode(result));
        } else {
            delegate.write(result, output);
        }
    }
}
