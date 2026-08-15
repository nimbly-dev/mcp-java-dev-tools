package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.RouteSynthesisActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.impl.CreateRecipeAction;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.impl.DiscoverHandlersAction;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.RouteSynthesisAction;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.classmethods.ClassMethodsRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.request.RouteSynthesisRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.result.RouteSynthesisResult;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.createrecipe.SynthesizerSelection;
import java.util.List;
import org.junit.jupiter.api.Test;

class DefaultRouteSynthesisFeatureTest {

    @Test
    void requiresAndDispatchesTheCompleteActionSet() {
        RouteSynthesisActionHandler classMethods = handler(RouteSynthesisAction.CLASS_METHODS);
        RouteSynthesisFeature feature = new DefaultRouteSynthesisFeature(List.of(
                handler(RouteSynthesisAction.INFER_TARGET),
                classMethods,
                new DiscoverHandlersAction(),
                new CreateRecipeAction(request -> new SynthesizerSelection(true, false, 0, null))));

        RouteSynthesisResult result = feature.execute(new ClassMethodsRequest(
                "project", List.of(), "example.Work", null, null));

        assertThat(result.reasonCode()).isEqualTo("delegated_class_methods");
    }

    private RouteSynthesisActionHandler handler(RouteSynthesisAction action) {
        return new RouteSynthesisActionHandler() {
            @Override
            public RouteSynthesisAction action() {
                return action;
            }

            @Override
            public RouteSynthesisResult execute(RouteSynthesisRequest request) {
                return RouteSynthesisResult.report(
                        "report",
                        "delegated_" + action.value(),
                        "test",
                        "test",
                        "test");
            }
        };
    }
}
