package com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.action.attach.impl;

import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.action.JvmLifecycleActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.artifact.JvmLifecycleArtifactKind;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.artifact.JvmLifecycleArtifactResolution;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.artifact.JvmLifecycleArtifactResolver;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.helper.JvmLifecycleHelper;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.helper.JvmLifecycleHelperRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.helper.JvmLifecycleHelperResult;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.action.JvmLifecycleAction;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.action.attach.AttachRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.request.JvmLifecycleRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.result.JvmLifecycleResult;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.result.JvmLifecycleResultStatus;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.result.JvmMutationResult;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.policy.ProbeHostPolicy;
import java.util.ArrayList;
import java.util.List;

/**
 * Coordinates safe attach through the existing lifecycle helper.
 */
public final class AttachAction implements JvmLifecycleActionHandler {

    private final JvmLifecycleHelper helper;
    private final JvmLifecycleArtifactResolver artifacts;
    private final ProbeHostPolicy probeHostPolicy;

    /** Creates the attach action. */
    public AttachAction(
            JvmLifecycleHelper helper,
            JvmLifecycleArtifactResolver artifacts,
            ProbeHostPolicy probeHostPolicy) {
        this.helper = helper;
        this.artifacts = artifacts;
        this.probeHostPolicy = probeHostPolicy;
    }

    @Override
    public JvmLifecycleAction action() {
        return JvmLifecycleAction.ATTACH;
    }

    @Override
    public JvmLifecycleResult execute(JvmLifecycleRequest request) {
        if (!(request instanceof AttachRequest input)) {
            return JvmLifecycleResult.blocked("jvm_lifecycle_request_invalid");
        }
        if (isSelf(input.pid())) {
            return JvmLifecycleResult.blocked("mcp_server_attach_forbidden");
        }
        if (!probeHostPolicy.isAllowed(input.probeHost())) {
            return JvmLifecycleResult.blocked("probe_host_not_allowed");
        }
        JvmLifecycleArtifactResolution agent = artifacts.resolve(JvmLifecycleArtifactKind.AGENT);
        if (!agent.isResolved()) {
            return JvmLifecycleResult.blocked(agent.reasonCode());
        }
        JvmLifecycleHelperResult result = helper.execute(
                new JvmLifecycleHelperRequest("attach", arguments(input, agent)));
        if (!"attach".equals(result.operation())) {
            return JvmLifecycleResult.blocked(result.reasonCode());
        }
        JvmMutationResult mutation = new JvmMutationResult(
                result.operation(), result.outcome(), input.pid(),
                input.expectedProcessStartEpochMs(), input.probeHost(), input.probePort(), List.of());
        JvmLifecycleResultStatus status = "active".equals(result.outcome())
                ? JvmLifecycleResultStatus.OK : JvmLifecycleResultStatus.BLOCKED;
        return JvmLifecycleResult.mutation(status, result.reasonCode(), mutation);
    }

    private static boolean isSelf(String pid) {
        return Long.toString(ProcessHandle.current().pid()).equals(pid);
    }

    private static List<String> arguments(AttachRequest input, JvmLifecycleArtifactResolution agent) {
        List<String> arguments = new ArrayList<>(List.of(
                "attach",
                "--pid", input.pid(),
                "--expected-process-start-epoch-ms", Long.toString(input.expectedProcessStartEpochMs()),
                "--agent-jar", agent.path().toString(),
                "--confirm", "true"));
        List<String> agentArguments = new ArrayList<>(List.of(
                "host=" + input.probeHost(), "port=" + input.probePort()));
        if (input.include() != null) {
            agentArguments.add("include=" + input.include());
        }
        if (input.exclude() != null) {
            agentArguments.add("exclude=" + input.exclude());
        }
        arguments.addAll(List.of("--agent-args", String.join(";", agentArguments)));
        return arguments;
    }
}
