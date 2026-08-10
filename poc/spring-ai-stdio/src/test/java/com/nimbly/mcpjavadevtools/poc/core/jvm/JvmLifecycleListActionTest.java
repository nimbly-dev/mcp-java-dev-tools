package com.nimbly.mcpjavadevtools.poc.core.jvm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JvmLifecycleListActionTest {

    @Test
    void returnsTheExistingDeterministicJvmListShape() {
        JvmLifecycleResult result = new JvmLifecycleListAction().execute();

        assertThat(result.structuredContent())
                .containsEntry("resultType", "jvm_list")
                .containsEntry("status", "ok")
                .containsEntry("reasonCode", "ok")
                .containsKey("jvms");
    }
}
