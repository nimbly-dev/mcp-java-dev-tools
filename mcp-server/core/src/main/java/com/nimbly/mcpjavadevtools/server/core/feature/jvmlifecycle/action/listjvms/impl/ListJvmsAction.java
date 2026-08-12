package com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.action.listjvms.impl;

import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.action.JvmLifecycleActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.helper.JvmLifecycleHelper;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.helper.JvmLifecycleHelperRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.helper.JvmLifecycleHelperResult;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.action.JvmLifecycleAction;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.action.listjvms.JvmListResult;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.action.listjvms.ListJvmsRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.candidate.JvmCandidate;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.request.JvmLifecycleRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.result.JvmLifecycleResult;
import java.util.ArrayList;
import java.util.List;

/**
 * Discovers bounded, sanitized local JVM candidates.
 */
public final class ListJvmsAction implements JvmLifecycleActionHandler {

    private final JvmLifecycleHelper helper;

    /** Creates the discovery action. */
    public ListJvmsAction(JvmLifecycleHelper helper) {
        this.helper = helper;
    }

    @Override
    public JvmLifecycleAction action() {
        return JvmLifecycleAction.LIST_JVMS;
    }

    @Override
    public JvmLifecycleResult execute(JvmLifecycleRequest request) {
        if (!(request instanceof ListJvmsRequest)) {
            return JvmLifecycleResult.blocked("jvm_lifecycle_request_invalid");
        }
        JvmLifecycleHelperResult result = helper.execute(
                new JvmLifecycleHelperRequest("discover", List.of("discover")));
        if (!"discover".equals(result.operation())) {
            return JvmLifecycleResult.blocked(result.reasonCode());
        }
        return JvmLifecycleResult.discovery(
                result.reasonCode(), new JvmListResult(filterSelf(result)));
    }

    private static List<JvmCandidate> filterSelf(JvmLifecycleHelperResult result) {
        String selfPid = Long.toString(ProcessHandle.current().pid());
        List<JvmCandidate> filtered = new ArrayList<>();
        for (String pid : result.pids()) {
            if (!selfPid.equals(pid)) {
                result.candidates().stream()
                        .filter(candidate -> candidate.pid().equals(pid))
                        .findFirst()
                        .ifPresent(filtered::add);
            }
            if (filtered.size() == 128) {
                break;
            }
        }
        return filtered;
    }
}
