package com.nimbly.mcpjavadevtools.server.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.registry.ProbeRegistry;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.registry.ProbeRegistryProvider;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.DefaultTransportExecutionFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.TransportExecutionFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.policy.TransportExecutionPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.TransportProviderRegistry;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.http.HttpRedirectResponseExecutor;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.http.HttpRequestValidator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class TransportExecutionConfigurationTest {

    @Test
    void composesTransportFeatureAndRegistryPolicyThroughSpring() {
        ProbeRegistry registry = new ProbeRegistry(List.of(), true);
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ProbeRegistryProvider.class, () -> () -> registry);
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.register(TransportExecutionConfiguration.class);
            context.refresh();

            assertThat(context.getBean(TransportExecutionFeature.class))
                    .isInstanceOf(DefaultTransportExecutionFeature.class);
            assertThat(context.getBean(TransportProviderRegistry.class)).isNotNull();
            assertThat(context.getBean(HttpRequestValidator.class)).isNotNull();
            assertThat(context.getBean(HttpRedirectResponseExecutor.class)).isNotNull();
            assertThat(context.getBean(TransportExecutionPolicy.class).allowNonWrappedExecutable()).isTrue();
        }
    }
}
