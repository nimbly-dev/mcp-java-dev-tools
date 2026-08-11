package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.ProbeAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.status.ProbeBatchStatusRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProbeRequestFactoryTest {

    private final ProbeRequestFactory factory = new ProbeRequestFactory();

    @Test
    void keepsResetSelectorCombinationPolicyInCore() {
        ProbeRequestInput input = input();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> factory.create(ProbeAction.RESET, input));
    }

    @Test
    void keepsTimeoutValidationInCore() {
        ProbeRequestInput input = new ProbeRequestInput(
                null, null, null, 0, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> factory.create(ProbeAction.CHECK, input))
                .withMessage("timeoutMs must be positive");
    }

    @Test
    void createsBatchStatusRequestsFromHostNeutralInput() {
        ProbeRequestInput input = new ProbeRequestInput(
                "http://probe.example",
                "orders",
                null,
                2000,
                null,
                List.of("example.Work#doIt:17"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);

        assertThat(factory.create(ProbeAction.STATUS, input)).isInstanceOf(ProbeBatchStatusRequest.class);
    }

    private static ProbeRequestInput input() {
        return new ProbeRequestInput(
                null,
                null,
                null,
                null,
                "example.Work#doIt:17",
                List.of("example.Work#save:21"),
                null,
                "example.Work",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }
}
