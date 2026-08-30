package com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.operation;

import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.action.JvmLifecycleAction;

/** Classifies the externally visible effect of a JVM lifecycle action. */
final class JvmLifecycleOperationEffects {

    private JvmLifecycleOperationEffects() {
    }

    static String sideEffect(JvmLifecycleAction action) {
        return switch (action) {
            case LIST_JVMS -> "jvm_process_read";
            case ATTACH -> "sidecar_agent_attach";
            case DEACTIVATE -> "sidecar_agent_deactivate";
        };
    }
}
