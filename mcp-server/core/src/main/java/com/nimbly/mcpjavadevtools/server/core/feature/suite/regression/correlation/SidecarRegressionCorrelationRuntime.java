package com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.correlation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.endpoint.ProbeEndpointClient;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointConfiguration;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointResponse;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.ProbeTarget;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.ProbeTargetSelector;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.ResolvedProbeTarget;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.routing.ProbeTargetResolver;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Sidecar protocol adapter for Regression correlation leases and event cursors. */
public class SidecarRegressionCorrelationRuntime implements RegressionCorrelationRuntime {

    private static final String CONFIGURE_PATH = "/__probe/correlation/configure";
    private static final String EVENTS_PATH = "/__probe/correlation/events";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final ProbeTargetResolver targets;
    private final ProbeEndpointConfiguration configuration;
    private final ProbeEndpointClient client;
    private final ObjectMapper mapper;

    /** Creates the adapter from the shared Probe routing and transport boundary. */
    public SidecarRegressionCorrelationRuntime(
            ProbeTargetResolver targets,
            ProbeEndpointConfiguration configuration,
            ProbeEndpointClient client,
            ObjectMapper mapper) {
        this.targets = Objects.requireNonNull(targets, "targets must not be null");
        this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    @Override
    public Session open(JsonNode correlation, String executionId) {
        JsonNode evidence = correlation.path("runtimeEvidence");
        if (!evidence.path("required").asBoolean(false)) {
            return new EmptySession();
        }
        String sessionId = requiredText(correlation, "correlationSessionId");
        String eventKeyPath = requiredText(evidence, "eventKeyPath");
        int pageLimit = bounded(evidence.path("pageLimit").asInt(256), 1, 1_000);
        int maxEvents = bounded(evidence.path("maxEvents").asInt(10_000), 1, 10_000);
        long leaseTtlMs = boundedLong(evidence.path("maxDurationMs").asLong(30_000L) + 60_000L,
                5_000L, 300_000L);
        List<TargetCursor> cursors = resolveTargets(evidence.path("probeIds"));
        try {
            for (TargetCursor cursor : cursors) {
                configure(cursor.target(), correlation, sessionId, executionId, eventKeyPath, leaseTtlMs);
                cursor.afterSequence(highWater(cursor.target(), sessionId));
            }
            return new SidecarSession(sessionId, executionId, pageLimit, maxEvents, cursors);
        } catch (RuntimeException failure) {
            release(cursors, executionId);
            throw failure;
        }
    }

    private List<TargetCursor> resolveTargets(JsonNode probeIds) {
        if (!probeIds.isArray() || probeIds.isEmpty()) {
            throw new RegressionCorrelationRuntimeException("correlation_probe_scope_mismatch");
        }
        List<TargetCursor> resolved = new ArrayList<>();
        for (JsonNode probeIdNode : probeIds) {
            String probeId = probeIdNode.asText().trim();
            var resolution = targets.resolve(new ProbeTargetSelector(probeId, null));
            if (!(resolution instanceof ResolvedProbeTarget selected)) {
                throw new RegressionCorrelationRuntimeException("correlation_probe_scope_mismatch");
            }
            resolved.add(new TargetCursor(probeId, selected.target()));
        }
        return resolved;
    }

    private void configure(
            ProbeTarget target,
            JsonNode correlation,
            String sessionId,
            String executionId,
            String eventKeyPath,
            long leaseTtlMs) {
        ObjectNode payload = mapper.createObjectNode()
                .put("sessionId", sessionId)
                .put("executionId", executionId)
                .put("eventKeyPath", eventKeyPath)
                .put("leaseTtlMs", leaseTtlMs);
        JsonNode boundaries = correlation.path("consumerBoundaries");
        if (boundaries.isArray() && !boundaries.isEmpty()) {
            ArrayNode normalized = payload.putArray("consumerBoundaries");
            for (JsonNode boundary : boundaries) {
                ObjectNode value = normalized.addObject()
                        .put("id", boundary.path("id").asText())
                        .put("fqcn", boundary.path("selector").path("fqcn").asText())
                        .put("method", boundary.path("selector").path("method").asText())
                        .put("eventArgumentIndex", boundary.path("eventArgumentIndex").asInt());
                value.set("parameterTypes", boundary.path("selector").path("parameterTypes"));
            }
        }
        ProbeEndpointResponse response = exchange(target, "POST", CONFIGURE_PATH, json(payload));
        JsonNode body = parse(response);
        if (response.statusCode() / 100 != 2 || !body.path("configured").asBoolean(false)) {
            throw new RegressionCorrelationRuntimeException("correlation_runtime_configure_failed");
        }
    }

    private long highWater(ProbeTarget target, String sessionId) {
        ProbeEndpointResponse response = exchange(target, "GET", eventsPath(sessionId, 0L, 1), "");
        JsonNode body = parse(response);
        if (response.statusCode() / 100 != 2 || !body.path("events").isArray()) {
            throw new RegressionCorrelationRuntimeException("correlation_probe_response_invalid");
        }
        return body.path("highWaterSequence").asLong(0L);
    }

    private ProbeEndpointResponse exchange(ProbeTarget target, String method, String path, String payload) {
        URI endpoint = target.baseUrl().resolve(path);
        Map<String, String> headers = payload.isEmpty()
                ? Map.of()
                : Map.of("content-type", "application/json");
        return client.exchange(new ProbeEndpointRequest(
                endpoint, method, headers, payload, REQUEST_TIMEOUT, configuration));
    }

    private JsonNode parse(ProbeEndpointResponse response) {
        try {
            return mapper.readTree(response.payload());
        } catch (JsonProcessingException failure) {
            throw new RegressionCorrelationRuntimeException("correlation_probe_response_invalid");
        }
    }

    private String json(JsonNode value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("correlation payload could not be serialized", failure);
        }
    }

    private void release(List<TargetCursor> cursors, String executionId) {
        for (TargetCursor cursor : cursors) {
            try {
                ObjectNode payload = mapper.createObjectNode()
                        .put("executionId", executionId)
                        .put("release", true);
                exchange(cursor.target(), "POST", CONFIGURE_PATH, json(payload));
            } catch (RuntimeException ignored) {
                // Lease expiry is the Sidecar fail-safe when release cannot be delivered.
            }
        }
    }

    private static String eventsPath(String sessionId, long afterSequence, int limit) {
        return EVENTS_PATH + "?sessionId=" + encode(sessionId)
                + "&afterSequence=" + afterSequence + "&limit=" + limit;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText().trim();
        if (value.isEmpty()) {
            throw new RegressionCorrelationRuntimeException("correlation_runtime_configuration_invalid");
        }
        return value;
    }

    private static int bounded(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static long boundedLong(long value, long minimum, long maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private final class SidecarSession implements Session {

        private final String sessionId;
        private final String executionId;
        private final int pageLimit;
        private final int maxEvents;
        private final List<TargetCursor> cursors;
        private boolean closed;

        private SidecarSession(
                String sessionId,
                String executionId,
                int pageLimit,
                int maxEvents,
                List<TargetCursor> cursors) {
            this.sessionId = sessionId;
            this.executionId = executionId;
            this.pageLimit = pageLimit;
            this.maxEvents = maxEvents;
            this.cursors = cursors;
        }

        @Override
        public List<Map<String, Object>> readEvents() {
            List<Map<String, Object>> events = new ArrayList<>();
            for (TargetCursor cursor : cursors) {
                read(cursor, events);
            }
            return List.copyOf(events);
        }

        private void read(TargetCursor cursor, List<Map<String, Object>> events) {
            boolean more;
            do {
                int remaining = maxEvents - events.size();
                if (remaining <= 0) {
                    throw new RegressionCorrelationRuntimeException("correlation_evidence_budget_exceeded");
                }
                int limit = Math.min(pageLimit, remaining);
                ProbeEndpointResponse response = exchange(
                        cursor.target(), "GET", eventsPath(sessionId, cursor.afterSequence(), limit), "");
                JsonNode body = parse(response);
                if (response.statusCode() / 100 != 2 || !body.path("events").isArray()) {
                    throw new RegressionCorrelationRuntimeException("correlation_probe_response_invalid");
                }
                for (JsonNode event : body.path("events")) {
                    if (!validEvent(event, cursor.probeId())) {
                        throw new RegressionCorrelationRuntimeException("correlation_probe_response_invalid");
                    }
                    events.add(mapper.convertValue(event, new com.fasterxml.jackson.core.type.TypeReference<>() { }));
                }
                long delivered = body.path("lastDeliveredSequence").asLong(cursor.afterSequence());
                more = body.path("hasMore").asBoolean(false) && delivered > cursor.afterSequence();
                cursor.afterSequence(delivered);
            } while (more);
        }

        private boolean validEvent(JsonNode event, String probeId) {
            return event.isObject()
                    && sessionId.equals(event.path("correlationSessionId").asText())
                    && executionId.equals(event.path("correlationExecutionId").asText())
                    && probeId.equals(event.path("probeId").asText())
                    && !event.path("runtimeInstanceId").asText().isBlank()
                    && !event.path("lineKey").asText().isBlank()
                    && !event.path("keyFingerprint").asText().isBlank()
                    && "runtime_line_hit".equals(event.path("eventType").asText());
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                release(cursors, executionId);
            }
        }
    }

    private static final class EmptySession implements Session {

        @Override
        public List<Map<String, Object>> readEvents() {
            return List.of();
        }

        @Override
        public void close() {
        }
    }

    private static final class TargetCursor {

        private final String probeId;
        private final ProbeTarget target;
        private long afterSequence;

        private TargetCursor(String probeId, ProbeTarget target) {
            this.probeId = probeId;
            this.target = target;
        }

        private String probeId() {
            return probeId;
        }

        private ProbeTarget target() {
            return target;
        }

        private long afterSequence() {
            return afterSequence;
        }

        private void afterSequence(long value) {
            afterSequence = value;
        }
    }
}
