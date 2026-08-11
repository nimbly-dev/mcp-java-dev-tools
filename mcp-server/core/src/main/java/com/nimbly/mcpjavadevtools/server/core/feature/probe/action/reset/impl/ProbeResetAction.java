package com.nimbly.mcpjavadevtools.server.core.feature.probe.action.reset.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.ProbeActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.endpoint.ProbeEndpointClient;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.endpoint.ProbeEndpointClientException;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.endpoint.ProbeEndpointFailureKind;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.ProbeAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.reset.ProbeBatchResetRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.reset.ProbeClassResetRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.reset.ProbeResetEntry;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.reset.ProbeResetRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.reset.ProbeResetResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.reset.ProbeSingleResetRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointConfiguration;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointResponse;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.key.StrictLineKey;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.request.ProbeRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.response.ProbeResponseCompactionPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeReasonCode;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeReasonMetadata;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.ProbeTarget;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.ProbeTargetResolution;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.ResolvedProbeTarget;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.UnresolvedProbeTarget;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.routing.ProbeTargetResolver;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Coordinates bounded reset requests without changing Sidecar reset semantics.
 */
@RequiredArgsConstructor
public final class ProbeResetAction implements ProbeActionHandler {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Pattern FULLY_QUALIFIED_CLASS_NAME = Pattern.compile(
            "^(?:[A-Za-z_$][A-Za-z0-9_$]*\\.)*[A-Za-z_$][A-Za-z0-9_$]*$");

    @NonNull private final ProbeTargetResolver targetResolver;
    @NonNull private final ProbeEndpointConfiguration endpointConfiguration;
    @NonNull private final ProbeEndpointClient endpointClient;
    @NonNull private final ProbeResponseCompactionPolicy compactionPolicy;

    /**
     * Dispatches only the reset request family owned by this action.
     *
     * @param request typed consolidated Probe request
     * @return deterministic reset outcome
     */
    @Override
    public ProbeResult execute(ProbeRequest request) {
        if (!(request instanceof ProbeResetRequest resetRequest)) {
            return ProbeResult.failure(ProbeReasonCode.INVALID_REQUEST, ProbeReasonMetadata.inputValidation());
        }
        try {
            return executeReset(resetRequest);
        } catch (IllegalArgumentException exception) {
            return ProbeResult.failure(ProbeReasonCode.INVALID_REQUEST, ProbeReasonMetadata.inputValidation());
        } catch (ProbeEndpointClientException exception) {
            if (exception.failureKind() == ProbeEndpointFailureKind.INTERRUPTED) {
                throw exception;
            }
            return ProbeResult.failure(ProbeReasonCode.PROBE_UNREACHABLE, ProbeReasonMetadata.status());
        }
    }

    /**
     * Returns the public Probe action implemented here.
     *
     * @return reset action discriminator
     */
    @Override
    public ProbeAction action() {
        return ProbeAction.RESET;
    }

    private ProbeResult executeReset(ProbeResetRequest request) {
        ProbeTargetResolution resolution = targetResolver.resolve(request.targetSelector());
        if (resolution instanceof UnresolvedProbeTarget unresolved) {
            return unresolved.result();
        }
        ProbeTarget target = ((ResolvedProbeTarget) resolution).target();
        if (request instanceof ProbeSingleResetRequest single) {
            return resetSingle(target, single);
        }
        if (request instanceof ProbeBatchResetRequest batch) {
            return resetBatch(target, batch);
        }
        return resetClass(target, (ProbeClassResetRequest) request);
    }

    private ProbeResult resetSingle(ProbeTarget target, ProbeSingleResetRequest request) {
        if (request.keySelector() == null) {
            return ProbeResult.failure(ProbeReasonCode.LINE_KEY_REQUIRED, ProbeReasonMetadata.inputValidation());
        }
        Optional<StrictLineKey> strictKey = request.keySelector().resolve();
        if (strictKey.isEmpty()) {
            return ProbeResult.failure(ProbeReasonCode.LINE_KEY_REQUIRED, ProbeReasonMetadata.inputValidation());
        }
        ProbeEndpointResponse response = endpointClient.exchange(
                endpointRequest(target, "{\"key\":\"" + strictKey.get().value() + "\"}", request.timeout()));
        ProbeResetEntry entry = singleEntry(strictKey.get().value(), response);
        return resultFor(new ProbeResetResult("key", null, null, List.of(entry)));
    }

    private ProbeResult resetBatch(ProbeTarget target, ProbeBatchResetRequest request) {
        List<String> keys = normalizedKeys(request);
        if (keys.isEmpty()) {
            return ProbeResult.failure(ProbeReasonCode.INVALID_REQUEST, ProbeReasonMetadata.inputValidation());
        }
        Map<String, ProbeResetEntry> entries = new LinkedHashMap<>();
        List<StrictLineKey> strictKeys = validKeys(keys, entries);
        if (strictKeys.isEmpty()) {
            return ProbeResult.withActionResult(
                    ProbeReasonCode.LINE_KEY_REQUIRED,
                    ProbeReasonMetadata.inputValidation(),
                    new ProbeResetResult("keys", null, null, orderedEntries(keys, entries)));
        }
        ProbeEndpointResponse response = endpointClient.exchange(
                endpointRequest(target, batchPayload(strictKeys), request.timeout()));
        addBatchEntries(entries, strictKeys, response);
        return resultFor(new ProbeResetResult("keys", null, null, orderedEntries(keys, entries)));
    }

    private ProbeResult resetClass(ProbeTarget target, ProbeClassResetRequest request) {
        String className = normalizedClassName(request.className());
        if (className == null) {
            return ProbeResult.failure(ProbeReasonCode.INVALID_REQUEST, ProbeReasonMetadata.inputValidation());
        }
        ProbeEndpointResponse response = endpointClient.exchange(
                endpointRequest(target, classPayload(className), request.timeout()));
        return resultFor(classResult(className, response));
    }

    private List<String> normalizedKeys(ProbeBatchResetRequest request) {
        if (request.keySelector() == null) {
            return List.of();
        }
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (String raw : request.keySelector().keys()) {
            if (raw != null && !raw.trim().isEmpty()) {
                keys.add(raw.trim());
            }
        }
        return List.copyOf(keys);
    }

    private static List<StrictLineKey> validKeys(
            List<String> keys,
            Map<String, ProbeResetEntry> entries) {
        List<StrictLineKey> strictKeys = new ArrayList<>();
        for (String key : keys) {
            Optional<StrictLineKey> strictKey = StrictLineKey.parse(key);
            if (strictKey.isPresent()) {
                strictKeys.add(strictKey.get());
            } else {
                entries.put(key, new ProbeResetEntry(key, false, 400, null, "line_key_required"));
            }
        }
        return List.copyOf(strictKeys);
    }

    private ProbeEndpointRequest endpointRequest(ProbeTarget target, String payload, Duration timeout) {
        URI endpoint = target.baseUrl().resolve(endpointConfiguration.paths().resetPath());
        return new ProbeEndpointRequest(
                endpoint,
                "POST",
                Map.of("content-type", "application/json"),
                payload,
                endpointConfiguration.requestPolicy().timeoutOrDefault(timeout),
                endpointConfiguration);
    }

    private ProbeResetEntry singleEntry(String expectedKey, ProbeEndpointResponse response) {
        if (response == null || response.statusCode() < 200 || response.statusCode() >= 300) {
            return new ProbeResetEntry(expectedKey, false, response == null ? null : response.statusCode(), null, null);
        }
        Optional<JsonNode> root = parseObject(response.payload());
        JsonNode responseKey = root.map(value -> value.path("key")).orElse(null);
        if (responseKey == null || !responseKey.isTextual() || !expectedKey.equals(responseKey.asText())) {
            return new ProbeResetEntry(expectedKey, false, response.statusCode(), null, null);
        }
        return resetEntry(root.get(), response.statusCode());
    }

    private void addBatchEntries(
            Map<String, ProbeResetEntry> entries,
            List<StrictLineKey> strictKeys,
            ProbeEndpointResponse response) {
        Map<String, ProbeResetEntry> remoteEntries = response == null
                        || response.statusCode() < 200
                        || response.statusCode() >= 300
                ? Map.of()
                : parsedBatchEntries(response);
        for (StrictLineKey strictKey : strictKeys) {
            entries.put(strictKey.value(), remoteEntries.getOrDefault(
                    strictKey.value(),
                    new ProbeResetEntry(
                            strictKey.value(),
                            false,
                            response == null ? null : response.statusCode(),
                            null,
                            null)));
        }
    }

    private Map<String, ProbeResetEntry> parsedBatchEntries(ProbeEndpointResponse response) {
        Optional<JsonNode> root = parseObject(response.payload());
        if (root.isEmpty() || !root.get().path("results").isArray()) {
            return Map.of();
        }
        Map<String, ProbeResetEntry> entries = new LinkedHashMap<>();
        for (JsonNode row : root.get().path("results")) {
            JsonNode key = row.path("key");
            if (key.isTextual()) {
                entries.putIfAbsent(key.asText(), resetEntry(row, response.statusCode()));
            }
        }
        return Map.copyOf(entries);
    }

    private ProbeResetResult classResult(String requestedClassName, ProbeEndpointResponse response) {
        if (response == null || response.statusCode() < 200 || response.statusCode() >= 300) {
            return new ProbeResetResult("className", requestedClassName, "endpoint_unavailable", List.of());
        }
        Optional<JsonNode> root = parseObject(response.payload());
        if (root.isEmpty() || !validClassResponse(root.get(), requestedClassName)) {
            return new ProbeResetResult("className", requestedClassName, "malformed_response", List.of());
        }
        List<ProbeResetEntry> entries = parsedClassEntries(root.get(), response.statusCode());
        return new ProbeResetResult(
                root.get().path("selector").isTextual() ? bound(root.get().path("selector").asText()) : null,
                root.get().path("className").isTextual() ? bound(root.get().path("className").asText()) : null,
                root.get().path("reason").isTextual() ? bound(root.get().path("reason").asText()) : null,
                entries);
    }

    private List<ProbeResetEntry> parsedClassEntries(JsonNode root, int statusCode) {
        if (!root.path("results").isArray()) {
            return List.of();
        }
        List<ProbeResetEntry> entries = new ArrayList<>();
        for (JsonNode row : root.path("results")) {
            if (row.path("key").isTextual()) {
                entries.add(resetEntry(row, statusCode));
            }
        }
        return List.copyOf(entries);
    }

    private ProbeResult resultFor(ProbeResetResult result) {
        if (result.reason() != null) {
            return ProbeResult.withActionResult(ProbeReasonCode.RESET_FAILED, ProbeReasonMetadata.status(), result);
        }
        if (result.entries().stream().anyMatch(entry -> Boolean.FALSE.equals(entry.reset()))) {
            return ProbeResult.withActionResult(ProbeReasonCode.RESET_FAILED, ProbeReasonMetadata.status(), result);
        }
        if (result.entries().stream().anyMatch(entry -> Boolean.FALSE.equals(entry.lineResolvable())
                || "invalid_line_target".equals(entry.lineValidation()))) {
            return ProbeResult.withActionResult(
                    ProbeReasonCode.INVALID_LINE_TARGET,
                    ProbeReasonMetadata.status(),
                    result);
        }
        return ProbeResult.success(result);
    }

    private ProbeResetEntry resetEntry(JsonNode node, int statusCode) {
        return new ProbeResetEntry(
                node.path("key").isTextual() ? bound(node.path("key").asText()) : null,
                node.path("ok").isBoolean() ? node.path("ok").booleanValue() : null,
                statusCode,
                node.path("lineResolvable").isBoolean() ? node.path("lineResolvable").booleanValue() : null,
                node.path("lineValidation").isTextual() ? bound(node.path("lineValidation").asText()) : null);
    }

    private static List<ProbeResetEntry> orderedEntries(
            List<String> keys,
            Map<String, ProbeResetEntry> entries) {
        List<ProbeResetEntry> ordered = new ArrayList<>();
        for (String key : keys) {
            ProbeResetEntry entry = entries.get(key);
            if (entry != null) {
                ordered.add(entry);
            }
        }
        return List.copyOf(ordered);
    }

    private static String batchPayload(List<StrictLineKey> keys) {
        StringBuilder payload = new StringBuilder("{\"keys\":[");
        for (StrictLineKey key : keys) {
            if (payload.charAt(payload.length() - 1) != '[') {
                payload.append(',');
            }
            payload.append('"').append(key.value()).append('"');
        }
        return payload.append("]}").toString();
    }

    private static String classPayload(String className) {
        try {
            return OBJECT_MAPPER.writeValueAsString(Map.of("className", className));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Probe reset class payload could not be serialized", exception);
        }
    }

    private static Optional<JsonNode> parseObject(String payload) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(payload);
            return root != null && root.isObject() ? Optional.of(root) : Optional.empty();
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    private String bound(String value) {
        return value.length() <= compactionPolicy.maximumStringLength()
                ? value
                : value.substring(0, compactionPolicy.maximumStringLength());
    }

    private static String normalizedClassName(String className) {
        if (className == null || className.trim().isEmpty()) {
            return null;
        }
        String normalized = className.trim();
        return FULLY_QUALIFIED_CLASS_NAME.matcher(normalized).matches() ? normalized : null;
    }

    private static boolean validClassResponse(JsonNode root, String requestedClassName) {
        JsonNode selector = root.path("selector");
        if (!selector.isTextual() || !"className".equals(selector.asText())) {
            return false;
        }
        JsonNode className = root.path("className");
        if (!className.isTextual() || !requestedClassName.equals(className.asText())) {
            return false;
        }
        return root.path("results").isArray() || root.path("reason").isTextual();
    }
}
