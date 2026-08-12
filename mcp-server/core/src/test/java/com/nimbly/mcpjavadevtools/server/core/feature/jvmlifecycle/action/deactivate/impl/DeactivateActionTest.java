package com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.action.deactivate.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.artifact.JvmLifecycleArtifactResolution;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.artifact.JvmLifecycleArtifactResolver;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.helper.JvmLifecycleHelper;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.helper.JvmLifecycleHelperResult;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.action.deactivate.DeactivateRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.result.JvmLifecycleResult;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DeactivateActionTest {

    @Test
    void requiresTheAgentArtifactButDoesNotExposeItAsInput() {
        AtomicReference<List<String>> arguments = new AtomicReference<>();
        JvmLifecycleHelper helper = request -> {
            arguments.set(request.arguments());
            return new JvmLifecycleHelperResult(
                    "deactivate", "deactivated", "deactivated", List.of(), List.of(), List.of());
        };
        JvmLifecycleArtifactResolver artifacts =
                kind -> JvmLifecycleArtifactResolution.resolved(Path.of("agent.jar"));
        DeactivateAction action = new DeactivateAction(helper, artifacts);

        JvmLifecycleResult result = action.execute(new DeactivateRequest("1234", 1L, true));

        assertThat(result.status().value()).isEqualTo("ok");
        assertThat(arguments.get()).containsSubsequence("--agent-jar", "agent.jar");
    }
}
