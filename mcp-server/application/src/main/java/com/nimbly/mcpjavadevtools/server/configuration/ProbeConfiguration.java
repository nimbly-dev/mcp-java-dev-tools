package com.nimbly.mcpjavadevtools.server.configuration;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.ProbeFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.DefaultProbeFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.ProbeActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.profiler.LocalProbeProfilerOutputStore;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.profiler.ProbeProfilerOutputStore;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.actuate.impl.ProbeActuateAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.capture.impl.ProbeCaptureAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.check.impl.ProbeCheckAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.profiler.impl.ProbeProfilerAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.reset.impl.ProbeResetAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.status.impl.ProbeStatusAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.waitforhit.impl.ProbeWaitForHitAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.waitforhit.ProbeWaitSleeper;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.endpoint.HttpProbeEndpointClient;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.endpoint.ProbeEndpointClient;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointConfiguration;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointLimits;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointPaths;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeRequestBounds;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeRequestPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.response.ProbeResponseCompactionPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.registry.ProbeRegistryProvider;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.routing.ProbeTargetResolver;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring composition for the complete, Spring-independent Probe Core Feature.
 */
@Configuration
@EnableConfigurationProperties(ProbeConfigurationProperties.class)
public class ProbeConfiguration {

    @Bean
    ProbeEndpointConfiguration probeEndpointConfiguration(ProbeConfigurationProperties properties) {
        return endpointConfiguration(properties);
    }

    @Bean
    ProbeResponseCompactionPolicy probeResponseCompactionPolicy(ProbeConfigurationProperties properties) {
        return compactionPolicy(properties);
    }

    @Bean
    ProbeEndpointClient probeEndpointClient() {
        return new HttpProbeEndpointClient();
    }

    @Bean
    Clock probeClock() {
        return Clock.systemUTC();
    }

    @Bean
    ProbeWaitSleeper probeWaitSleeper() {
        return Thread::sleep;
    }

    @Bean
    ProbeTargetResolver probeTargetResolver(
            ProbeEndpointConfiguration endpoint,
            ProbeRegistryProvider registryProvider) {
        return ProbeTargetResolver.dynamic(endpoint, registryProvider);
    }

    @Bean
    ProbeCheckAction probeCheckAction(
            ProbeTargetResolver resolver,
            ProbeEndpointConfiguration endpoint,
            ProbeEndpointClient client,
            ProbeResponseCompactionPolicy compaction) {
        return new ProbeCheckAction(resolver, endpoint, client, compaction);
    }

    @Bean
    ProbeStatusAction probeStatusAction(
            ProbeTargetResolver resolver,
            ProbeEndpointConfiguration endpoint,
            ProbeEndpointClient client,
            ProbeResponseCompactionPolicy compaction) {
        return new ProbeStatusAction(resolver, endpoint, client, compaction);
    }

    @Bean
    ProbeResetAction probeResetAction(
            ProbeTargetResolver resolver,
            ProbeEndpointConfiguration endpoint,
            ProbeEndpointClient client,
            ProbeResponseCompactionPolicy compaction) {
        return new ProbeResetAction(resolver, endpoint, client, compaction);
    }

    @Bean
    ProbeWaitForHitAction probeWaitForHitAction(
            ProbeStatusAction status,
            ProbeEndpointConfiguration endpoint,
            Clock probeClock,
            ProbeWaitSleeper probeWaitSleeper) {
        return new ProbeWaitForHitAction(
                status,
                endpoint.requestPolicy(),
                probeClock,
                probeWaitSleeper);
    }

    @Bean
    ProbeCaptureAction probeCaptureAction(
            ProbeTargetResolver resolver,
            ProbeEndpointConfiguration endpoint,
            ProbeEndpointClient client,
            ProbeResponseCompactionPolicy compaction) {
        return new ProbeCaptureAction(resolver, endpoint, client, compaction);
    }

    @Bean
    ProbeActuateAction probeActuateAction(
            ProbeTargetResolver resolver,
            ProbeEndpointConfiguration endpoint,
            ProbeEndpointClient client,
            ProbeResponseCompactionPolicy compaction) {
        return new ProbeActuateAction(resolver, endpoint, client, compaction);
    }

    @Bean
    ProbeProfilerOutputStore probeProfilerOutputStore() {
        return new LocalProbeProfilerOutputStore();
    }

    @Bean
    ProbeProfilerAction probeProfilerAction(
            ProbeTargetResolver resolver,
            ProbeEndpointConfiguration endpoint,
            ProbeEndpointClient client,
            ProbeResponseCompactionPolicy compaction,
            ProbeProfilerOutputStore outputStore) {
        return new ProbeProfilerAction(resolver, endpoint, client, compaction, outputStore);
    }

    /**
     * Assembles the public Core Feature from action-owned Spring beans.
     *
     * @param handlers all registered Probe action implementations
     * @return complete consolidated Probe Feature
     */
    @Bean
    ProbeFeature probeFeature(List<ProbeActionHandler> handlers) {
        return new DefaultProbeFeature(handlers);
    }

    private static ProbeEndpointConfiguration endpointConfiguration(ProbeConfigurationProperties properties) {
        ProbeConfigurationProperties.Endpoint endpoint = properties.getEndpoint();
        return new ProbeEndpointConfiguration(
                endpoint.getDefaultBaseUrl(),
                new ProbeEndpointPaths(
                        "/__probe/status",
                        "/__probe/reset",
                        "/__probe/actuate",
                        "/__probe/capture",
                        "/__probe/profiler"),
                new ProbeRequestPolicy(
                        Duration.ofMillis(endpoint.getTimeoutMs()),
                        Duration.ofMillis(endpoint.getPollIntervalMs()),
                        endpoint.getMaxRetries(),
                        endpoint.isUnreachableRetryEnabled(),
                        endpoint.getUnreachableMaxRetries(),
                        new ProbeRequestBounds(
                                Duration.ofSeconds(1),
                                Duration.ofSeconds(60),
                                Duration.ofMillis(100),
                                Duration.ofSeconds(5),
                                1,
                                10)),
                new ProbeEndpointLimits(64, 128, 4096, 65536, endpoint.getMaximumResponsePayloadBytes()));
    }

    private static ProbeResponseCompactionPolicy compactionPolicy(ProbeConfigurationProperties properties) {
        ProbeConfigurationProperties.Response response = properties.getCapture().getResponse();
        return new ProbeResponseCompactionPolicy(
                response.isIncludeExecutionPaths(),
                response.getMaxStringLength(),
                response.getMaxExecutionPaths(),
                64,
                256,
                response.getSafeDiagnosticValueHeaders());
    }

}
