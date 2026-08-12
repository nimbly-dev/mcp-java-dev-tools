package com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.action.attach.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.artifact.JvmLifecycleArtifactResolution;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.artifact.JvmLifecycleArtifactResolver;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.helper.JvmLifecycleHelper;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.helper.JvmLifecycleHelperResult;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.action.attach.AttachRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.result.JvmLifecycleResult;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.policy.ProbeHostPolicy;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AttachActionTest {

    @Test
    void forwardsTheFrozenHelperArgumentsAndProbeSelection() {
        AtomicReference<String> operation = new AtomicReference<>();
        AtomicReference<List<String>> arguments = new AtomicReference<>();
        JvmLifecycleHelper helper = request -> {
            operation.set(request.operation());
            arguments.set(request.arguments());
            return new JvmLifecycleHelperResult(
                    "attach", "active", "active", List.of(), List.of(), List.of());
        };
        JvmLifecycleArtifactResolver artifacts =
                kind -> JvmLifecycleArtifactResolution.resolved(Path.of("agent.jar"));
        AttachAction action = new AttachAction(helper, artifacts, new ProbeHostPolicy(Set.of()));

        JvmLifecycleResult result = action.execute(new AttachRequest(
                "1234", 1720000000000L, true, "127.0.0.1", 9191, "com.example.*", null));

        assertThat(result.status().value()).isEqualTo("ok");
        assertThat(operation).hasValue("attach");
        assertThat(arguments.get()).containsSubsequence(
                "--agent-jar", "agent.jar", "--confirm", "true",
                "--agent-args", "host=127.0.0.1;port=9191;include=com.example.*");
    }

    @Test
    void blocksAConfiguredProbeHostOutsideTheAllowlist() {
        JvmLifecycleHelper helper = request -> {
            throw new AssertionError("helper must not be invoked");
        };
        JvmLifecycleArtifactResolver artifacts =
                kind -> JvmLifecycleArtifactResolution.resolved(Path.of("agent.jar"));
        AttachAction action = new AttachAction(helper, artifacts, new ProbeHostPolicy(Set.of()));

        JvmLifecycleResult result = action.execute(new AttachRequest(
                "1234", 1L, true, "192.0.2.1", 9191, null, null));

        assertThat(result.reasonCode()).isEqualTo("probe_host_not_allowed");
    }
}
