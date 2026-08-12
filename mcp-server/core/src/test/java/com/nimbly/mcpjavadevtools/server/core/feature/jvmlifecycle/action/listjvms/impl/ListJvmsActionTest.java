package com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.action.listjvms.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.helper.JvmLifecycleHelper;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.helper.JvmLifecycleHelperRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.helper.JvmLifecycleHelperResult;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.action.listjvms.ListJvmsRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.action.listjvms.JvmListResult;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.candidate.JvmCandidate;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.result.JvmLifecycleResult;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class ListJvmsActionTest {

    @Test
    void returnsAnEmptyListWhenDiscoveryFindsNoJvm() {
        AtomicReference<JvmLifecycleHelperRequest> request = new AtomicReference<>();
        ListJvmsAction action = action(List.of(), request::set);

        JvmLifecycleResult result = action.execute(new ListJvmsRequest());

        assertThat(list(result).jvms()).isEmpty();
        assertThat(request.get().operation()).isEqualTo("discover");
        assertThat(request.get().arguments()).containsExactly("discover");
    }

    @Test
    void returnsOneCandidateWithoutChangingItsIdentityEvidence() {
        JvmCandidate candidate = candidate(pid(1));
        JvmLifecycleResult result = action(List.of(candidate)).execute(new ListJvmsRequest());

        assertThat(list(result).jvms()).containsExactly(candidate);
    }

    @Test
    void preservesMultipleCandidatesInHelperOrder() {
        List<JvmCandidate> candidates = List.of(candidate(pid(1)), candidate(pid(2)), candidate(pid(3)));
        JvmLifecycleResult result = action(candidates).execute(new ListJvmsRequest());

        assertThat(list(result).jvms())
                .extracting(JvmCandidate::pid)
                .containsExactly(pid(1), pid(2), pid(3));
    }

    @Test
    void filtersTheMcpServerProcessFromDiscovery() {
        String self = Long.toString(ProcessHandle.current().pid());
        String other = pid(4);
        JvmLifecycleResult result = action(List.of(candidate(self), candidate(other)))
                .execute(new ListJvmsRequest());

        assertThat(list(result).jvms())
                .extracting(JvmCandidate::pid)
                .containsExactly(other);
    }

    @Test
    void capsDiscoveryAt128Candidates() {
        List<JvmCandidate> candidates = new ArrayList<>();
        for (int index = 1; index <= 129; index++) {
            candidates.add(candidate(pid(index + 10)));
        }
        JvmLifecycleResult result = action(candidates).execute(new ListJvmsRequest());

        assertThat(list(result).jvms()).hasSize(128);
        assertThat(list(result).jvms().get(127).pid()).isEqualTo(pid(138));
    }

    private static JvmListResult list(JvmLifecycleResult result) {
        return (JvmListResult) result.actionResult().orElseThrow();
    }

    private static ListJvmsAction action(List<JvmCandidate> candidates) {
        return action(candidates, request -> { });
    }

    private static ListJvmsAction action(
            List<JvmCandidate> candidates,
            Consumer<JvmLifecycleHelperRequest> observer) {
        JvmLifecycleHelper helper = request -> {
            observer.accept(request);
            return new JvmLifecycleHelperResult(
                    "discover",
                    "unverified",
                    "jvm_discovery_unverified",
                    candidates.stream().map(JvmCandidate::pid).toList(),
                    candidates,
                    List.of());
        };
        return new ListJvmsAction(helper);
    }

    private static JvmCandidate candidate(String pid) {
        return new JvmCandidate(
                pid, "service-" + pid + ".jar", "sanitized_attach_descriptor",
                "unknown", List.of(), 1720000000000L);
    }

    private static String pid(int offset) {
        return Long.toString(ProcessHandle.current().pid() + 1000L + offset);
    }
}
