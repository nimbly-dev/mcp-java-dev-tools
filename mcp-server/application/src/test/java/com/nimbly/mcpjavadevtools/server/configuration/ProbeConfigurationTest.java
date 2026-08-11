package com.nimbly.mcpjavadevtools.server.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.ProbeFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.DefaultProbeFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.ProbeActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.actuate.impl.ProbeActuateAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.capture.impl.ProbeCaptureAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.check.impl.ProbeCheckAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.profiler.impl.ProbeProfilerAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.reset.impl.ProbeResetAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.status.impl.ProbeStatusAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.waitforhit.impl.ProbeWaitForHitAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.waitforhit.ProbeWaitSleeper;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.check.ProbeCheckRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointConfiguration;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeReasonCode;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.registry.ProbeRegistration;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.response.ProbeResponseCompactionPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.registry.ProbeRegistry;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.registry.ProbeRegistryProvider;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.ProbeTargetSelector;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.endpoint.ProbeEndpointClient;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.routing.ProbeTargetResolver;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class ProbeConfigurationTest {

    @Test
    void composesEachProbeActionAsAnIndependentSpringBean() {
        ProbeRegistry registry = new ProbeRegistry(List.of());
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ProbeRegistryProvider.class, () -> () -> registry);
            context.register(ProbeConfiguration.class);
            context.refresh();

            assertThat(context.getBeansOfType(ProbeActionHandler.class)).hasSize(7);
            assertThat(context.getBean(ProbeFeature.class)).isInstanceOf(DefaultProbeFeature.class);
            assertThat(context.getBean(Clock.class)).isNotNull();
            assertThat(context.getBean(ProbeWaitSleeper.class)).isNotNull();
        }
    }

    @Test
    void routesAnExplicitProbeIdThroughTheConfiguredRegistry() throws IOException {
        AtomicReference<String> statusQuery = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/__probe/reset", exchange -> respond(exchange, "{\"ok\":true}"));
        server.createContext("/__probe/status", exchange -> {
            statusQuery.set(exchange.getRequestURI().getRawQuery());
            respond(exchange, """
                    {"probe":{"key":"mcp.jvm.diagnose#key","hitCount":0,"lastHitEpoch":0}}
                    """);
        });
        server.start();
        try {
            ProbeConfigurationProperties properties = properties(server.getAddress().getPort());
            ProbeRegistry registry = new ProbeRegistry(List.of(
                    new ProbeRegistration("orders", "http://127.0.0.1:" + server.getAddress().getPort())));
            ProbeConfiguration configuration = new ProbeConfiguration();
            ProbeFeature feature = feature(configuration, properties, registry);

            ProbeResult result = feature.execute(new ProbeCheckRequest(
                    new ProbeTargetSelector("orders", null),
                    Map.of(),
                    null));

            assertThat(result.reasonCode()).isEqualTo(ProbeReasonCode.SUCCESS);
            assertThat(statusQuery.get()).isEqualTo("key=mcp.jvm.diagnose%23key");
        } finally {
            server.stop(0);
        }
    }

    private static ProbeFeature feature(
            ProbeConfiguration configuration,
            ProbeConfigurationProperties properties,
            ProbeRegistry registry) {
        ProbeEndpointConfiguration endpoint = configuration.probeEndpointConfiguration(properties);
        ProbeResponseCompactionPolicy compaction = configuration.probeResponseCompactionPolicy(properties);
        ProbeEndpointClient client = configuration.probeEndpointClient();
        ProbeTargetResolver resolver = configuration.probeTargetResolver(endpoint, () -> registry);
        ProbeStatusAction status = configuration.probeStatusAction(resolver, endpoint, client, compaction);
        ProbeCheckAction check = configuration.probeCheckAction(resolver, endpoint, client, compaction);
        ProbeResetAction reset = configuration.probeResetAction(resolver, endpoint, client, compaction);
        ProbeWaitForHitAction wait = configuration.probeWaitForHitAction(
                status,
                endpoint,
                configuration.probeClock(),
                configuration.probeWaitSleeper());
        ProbeCaptureAction capture = configuration.probeCaptureAction(resolver, endpoint, client, compaction);
        ProbeActuateAction actuate = configuration.probeActuateAction(resolver, endpoint, client, compaction);
        ProbeProfilerAction profiler = configuration.probeProfilerAction(
                resolver,
                endpoint,
                client,
                compaction,
                configuration.probeProfilerOutputStore());
        return configuration.probeFeature(List.of(check, status, reset, wait, capture, actuate, profiler));
    }

    private static ProbeConfigurationProperties properties(int port) {
        ProbeConfigurationProperties.Registration registration = new ProbeConfigurationProperties.Registration();
        registration.setId("orders");
        registration.setBaseUrl("http://127.0.0.1:" + port);
        ProbeConfigurationProperties.Registry registry = new ProbeConfigurationProperties.Registry();
        registry.setRegistrations(List.of(registration));
        ProbeConfigurationProperties properties = new ProbeConfigurationProperties();
        properties.setRegistry(registry);
        return properties;
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String payload) throws IOException {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("content-type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
