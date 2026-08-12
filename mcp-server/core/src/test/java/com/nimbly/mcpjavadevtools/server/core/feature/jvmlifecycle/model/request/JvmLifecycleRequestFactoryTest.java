package com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.action.JvmLifecycleAction;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.action.attach.AttachRequest;
import org.junit.jupiter.api.Test;

class JvmLifecycleRequestFactoryTest {

    private final JvmLifecycleRequestFactory factory = new JvmLifecycleRequestFactory();

    @Test
    void appliesSafeAttachDefaults() {
        JvmLifecycleRequest request = factory.create(
                JvmLifecycleAction.ATTACH,
                new JvmLifecycleInput("1234", 1720000000000L, true, null, null, null, null));

        assertThat(request).isInstanceOf(AttachRequest.class);
        AttachRequest attach = (AttachRequest) request;
        assertThat(attach.probeHost()).isEqualTo("127.0.0.1");
        assertThat(attach.probePort()).isEqualTo(9191);
    }

    @Test
    void rejectsUnsafeMutationInputs() {
        assertThatThrownBy(() -> factory.create(
                JvmLifecycleAction.DEACTIVATE,
                new JvmLifecycleInput("1234", 0L, true, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.create(
                JvmLifecycleAction.LIST_JVMS,
                new JvmLifecycleInput("1234", null, null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
