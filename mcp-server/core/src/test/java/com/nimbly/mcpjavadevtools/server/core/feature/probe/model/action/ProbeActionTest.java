package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ProbeActionTest {

    @Test
    void exposesTheCompleteStableActionAllowlist() {
        List<String> actions = List.of(
                ProbeAction.CHECK.value(),
                ProbeAction.STATUS.value(),
                ProbeAction.RESET.value(),
                ProbeAction.WAIT_FOR_HIT.value(),
                ProbeAction.CAPTURE.value(),
                ProbeAction.ACTUATE.value(),
                ProbeAction.PROFILER.value());

        assertThat(actions).containsExactly(
                "check",
                "status",
                "reset",
                "wait_for_hit",
                "capture",
                "actuate",
                "profiler");
        assertThat(ProbeAction.fromValue("wait_for_hit")).contains(ProbeAction.WAIT_FOR_HIT);
        assertThat(ProbeAction.fromValue("unknown")).isEmpty();
    }
}
