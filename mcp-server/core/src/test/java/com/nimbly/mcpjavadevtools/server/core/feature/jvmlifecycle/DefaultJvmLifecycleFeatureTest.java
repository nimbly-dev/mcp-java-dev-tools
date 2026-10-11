package com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.action.JvmLifecycleActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.action.JvmLifecycleAction;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.request.JvmLifecycleRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.result.JvmLifecycleResult;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class DefaultJvmLifecycleFeatureTest {

    @Test
    void executesThroughTheOwnerForTheSelectedAction() {
        JvmLifecycleFeature feature = new DefaultJvmLifecycleFeature(handlers());

        JvmLifecycleResult result = feature.execute(() -> JvmLifecycleAction.ATTACH);

        assertThat(result).isEqualTo(JvmLifecycleResult.blocked("attach_test"));
    }

    @Test
    void rejectsAnIncompleteActionSetBeforeTheFeatureCanBeCreated() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DefaultJvmLifecycleFeature(
                        handlers().stream()
                                .filter(handler -> handler.action() != JvmLifecycleAction.DEACTIVATE)
                                .toList()))
                .withMessage("JVM lifecycle handlers must cover every JVM lifecycle action");
    }

    @Test
    void rejectsDuplicateActionOwners() {
        List<JvmLifecycleActionHandler> handlers = new java.util.ArrayList<>(handlers());
        handlers.add(handler(JvmLifecycleAction.ATTACH));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DefaultJvmLifecycleFeature(handlers))
                .withMessage("Duplicate JVM lifecycle action owner: attach");
    }

    private static List<JvmLifecycleActionHandler> handlers() {
        return Arrays.stream(JvmLifecycleAction.values())
                .map(DefaultJvmLifecycleFeatureTest::handler)
                .toList();
    }

    private static JvmLifecycleActionHandler handler(JvmLifecycleAction action) {
        return new JvmLifecycleActionHandler() {
            @Override
            public JvmLifecycleAction action() {
                return action;
            }

            @Override
            public JvmLifecycleResult execute(JvmLifecycleRequest request) {
                return JvmLifecycleResult.blocked(action.value() + "_test");
            }
        };
    }
}
