package com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.action.deactivate.impl;

import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.action.JvmLifecycleActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.artifact.JvmLifecycleArtifactKind;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.artifact.JvmLifecycleArtifactResolution;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.artifact.JvmLifecycleArtifactResolver;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.helper.JvmLifecycleHelper;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.helper.JvmLifecycleHelperRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.helper.JvmLifecycleHelperResult;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.action.JvmLifecycleAction;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.action.deactivate.DeactivateRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.request.JvmLifecycleRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.result.JvmLifecycleResult;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.result.JvmLifecycleResultStatus;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.result.JvmMutationResult;
import java.util.ArrayList;
import java.util.List;

/**
 * Coordinates safe Sidecar Agent deactivation through the existing helper.
 */
public final class DeactivateAction implements JvmLifecycleActionHandler {

    private final JvmLifecycleHelper helper;
    private final JvmLifecycleArtifactResolver artifacts;

    /** Creates the deactivation action. */
    public DeactivateAction(
            JvmLifecycleHelper helper,
            JvmLifecycleArtifactResolver artifacts) {
        this.helper = helper;
        this.artifacts = artifacts;
    }

    @Override
    public JvmLifecycleAction action() {
        return JvmLifecycleAction.DEACTIVATE;
    }

    @Override
    public JvmLifecycleResult execute(JvmLifecycleRequest request) {
        if (!(request instanceof DeactivateRequest input)) {
            return JvmLifecycleResult.blocked("jvm_lifecycle_request_invalid");
        }
        if (isSelf(input.pid())) {
            return JvmLifecycleResult.blocked("mcp_server_attach_forbidden");
        }
        JvmLifecycleArtifactResolution agent = artifacts.resolve(JvmLifecycleArtifactKind.AGENT);
        if (!agent.isResolved()) {
            return JvmLifecycleResult.blocked(agent.reasonCode());
        }
        JvmLifecycleHelperResult result = helper.execute(
                new JvmLifecycleHelperRequest("deactivate", arguments(input, agent)));
        if (!"deactivate".equals(result.operation())) {
            return JvmLifecycleResult.blocked(result.reasonCode());
        }
        JvmMutationResult mutation = new JvmMutationResult(
                result.operation(), result.outcome(), input.pid(),
                input.expectedProcessStartEpochMs(), null, null, result.nonRestorableClasses());
        boolean successful = "deactivated".equals(result.outcome())
                || "partial".equals(result.outcome());
        JvmLifecycleResultStatus status = successful
                ? JvmLifecycleResultStatus.OK : JvmLifecycleResultStatus.BLOCKED;
        return JvmLifecycleResult.mutation(status, result.reasonCode(), mutation);
    }

    private static boolean isSelf(String pid) {
        return Long.toString(ProcessHandle.current().pid()).equals(pid);
    }

    private static List<String> arguments(
            DeactivateRequest input,
            JvmLifecycleArtifactResolution agent) {
        return new ArrayList<>(List.of(
                "deactivate",
                "--pid", input.pid(),
                "--expected-process-start-epoch-ms", Long.toString(input.expectedProcessStartEpochMs()),
                "--agent-jar", agent.path().toString(),
                "--confirm", "true"));
    }
}
