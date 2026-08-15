package com.nimbly.mcpjavadevtools.server.mcp.tools.routesynthesis;

import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.RouteSynthesisAction;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.classmethods.ClassMethodsRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.createrecipe.CreateRecipeRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.discoverhandlers.DiscoverHandlersRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.infertarget.InferTargetRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.request.RouteSynthesisRequest;
import com.nimbly.mcpjavadevtools.server.mcp.tools.action.McpActionRequest;
import com.nimbly.mcpjavadevtools.server.mcp.tools.action.McpActionRequestMapper;
import java.util.List;

/** Maps the consolidated MCP input into action-owned Core request models. */
class RouteSynthesisMcpRequestMapper
        implements McpActionRequestMapper<RouteSynthesisMcpActionInput, RouteSynthesisRequest> {

    @Override
    public RouteSynthesisRequest map(McpActionRequest<RouteSynthesisMcpActionInput> request) {
        if (request == null || request.input() == null) {
            throw new IllegalArgumentException("Route Synthesis request requires action and input");
        }
        RouteSynthesisAction action = RouteSynthesisAction.fromValue(request.action())
                .orElseThrow(() -> new IllegalArgumentException("Route Synthesis action is unsupported"));
        RouteSynthesisMcpActionInput input = request.input();
        List<String> roots = input.additionalSourceRoots() == null
                ? List.of() : List.copyOf(input.additionalSourceRoots());
        return switch (action) {
            case INFER_TARGET -> new InferTargetRequest(
                    input.projectRootAbs(), roots, input.classHint(), input.methodHint(), input.lineHint(),
                    input.maxCandidates(), input.probeId(), input.probeBaseUrl());
            case CLASS_METHODS -> new ClassMethodsRequest(
                    input.projectRootAbs(), roots, input.classHint(), input.probeId(), input.probeBaseUrl());
            case DISCOVER_HANDLERS -> new DiscoverHandlersRequest(
                    input.projectRootAbs(), roots, input.classHint(), input.probeId(), input.probeBaseUrl());
            case CREATE_RECIPE -> new CreateRecipeRequest(
                    input.projectRootAbs(), roots, input.classHint(), input.methodHint(), input.lineHint(),
                    input.mappingsBaseUrl(), input.discoveryPreference(), input.apiBasePath(), input.intentMode(),
                    input.authToken(), input.authUsername(), input.authPassword(), input.actuationEnabled(),
                    input.actuationReturnBoolean(), input.actuationActuatorId(), input.outputTemplate(),
                    input.probeId(), input.probeBaseUrl());
        };
    }

}
