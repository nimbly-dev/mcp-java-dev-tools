package com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.operation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.action.JvmLifecycleActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.action.JvmLifecycleAction;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.request.JvmLifecycleRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.result.JvmLifecycleResult;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class JvmLifecycleOperationCatalogTest {

    @Test
    void registersEveryPublicLifecycleActionInClosedEnumOrder() {
        JvmLifecycleOperationCatalog catalog = new JvmLifecycleOperationCatalog(handlers());

        assertThat(catalog.catalog())
                .extracting(descriptor -> descriptor.action())
                .containsExactly("list_jvms", "attach", "deactivate");
        assertThat(catalog.traceInventory())
                .extracting(entry -> entry.operationCatalog())
                .containsOnly(JvmLifecycleOperationCatalog.class.getName());
        assertThat(catalog.traceInventory())
                .extracting(entry -> entry.sideEffect())
                .containsExactly("jvm_process_read", "sidecar_agent_attach", "sidecar_agent_deactivate");
    }

    @Test
    void executesThroughTheOwnerForTheSelectedAction() {
        JvmLifecycleOperationCatalog catalog = new JvmLifecycleOperationCatalog(handlers());

        JvmLifecycleResult result = catalog.execute(
                JvmLifecycleAction.ATTACH,
                () -> JvmLifecycleAction.ATTACH);

        assertThat(result).isEqualTo(JvmLifecycleResult.blocked("attach_test"));
    }

    @Test
    void rejectsAnIncompleteActionSetBeforeTheFeatureCanBeCreated() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new JvmLifecycleOperationCatalog(
                        handlers().stream()
                                .filter(handler -> handler.action() != JvmLifecycleAction.DEACTIVATE)
                                .toList()))
                .withMessage("missing operation: DEACTIVATE");
    }

    private static List<JvmLifecycleActionHandler> handlers() {
        return Arrays.stream(JvmLifecycleAction.values())
                .map(JvmLifecycleOperationCatalogTest::handler)
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
