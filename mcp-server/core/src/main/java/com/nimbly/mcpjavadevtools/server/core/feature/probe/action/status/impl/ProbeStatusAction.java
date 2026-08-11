package com.nimbly.mcpjavadevtools.server.core.feature.probe.action.status.impl;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.ProbeActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.endpoint.ProbeEndpointClient;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.endpoint.ProbeEndpointClientException;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.endpoint.ProbeEndpointFailureKind;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.ProbeAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.status.ProbeBatchStatusRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.status.ProbeSingleStatusRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.status.ProbeStatusEntry;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.status.ProbeStatusRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.status.ProbeStatusResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointConfiguration;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointResponse;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.key.ProbeKeyBatchSelector;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.key.StrictLineKey;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.response.ProbeResponseCompactionPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.response.ProbeStatusPayload;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.request.ProbeRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeReasonCode;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeReasonMetadata;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.ProbeTarget;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.ProbeTargetResolution;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.ResolvedProbeTarget;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.UnresolvedProbeTarget;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.response.ProbeResponseJsonParser;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.routing.ProbeTargetResolver;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Executes bounded single and batch status reads against an existing Sidecar Agent.
 */
@RequiredArgsConstructor
public final class ProbeStatusAction implements ProbeActionHandler {

    @NonNull private final ProbeTargetResolver targetResolver;
    @NonNull private final ProbeEndpointConfiguration endpointConfiguration;
    @NonNull private final ProbeEndpointClient endpointClient;
    @NonNull private final ProbeResponseCompactionPolicy compactionPolicy;

    /**
     * Executes one typed single or batch status request.
     *
     * @param request typed status request
     * @return deterministic status outcome
     */
    public ProbeResult execute(ProbeStatusRequest request) {
        if (request == null) {
            return ProbeResult.failure(ProbeReasonCode.INVALID_REQUEST, ProbeReasonMetadata.inputValidation());
        }
        try {
            return executeResolved(request);
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
     * Dispatches only the status request family owned by this action.
     *
     * @param request typed consolidated Probe request
     * @return deterministic status outcome
     */
    @Override
    public ProbeResult execute(ProbeRequest request) {
        if (!(request instanceof ProbeStatusRequest statusRequest)) {
            return ProbeResult.failure(ProbeReasonCode.INVALID_REQUEST, ProbeReasonMetadata.inputValidation());
        }
        return execute(statusRequest);
    }

    /**
     * Returns the public Probe action implemented here.
     *
     * @return status action discriminator
     */
    @Override
    public ProbeAction action() {
        return ProbeAction.STATUS;
    }

    private ProbeResult executeResolved(ProbeStatusRequest request) {
        ProbeTargetResolution resolution = targetResolver.resolve(request.targetSelector());
        if (resolution instanceof UnresolvedProbeTarget unresolved) {
            return unresolved.result();
        }
        ProbeTarget target = ((ResolvedProbeTarget) resolution).target();
        if (request instanceof ProbeSingleStatusRequest single) {
            return singleStatus(target, single);
        }
        return batchStatus(target, (ProbeBatchStatusRequest) request);
    }

    private ProbeResult singleStatus(ProbeTarget target, ProbeSingleStatusRequest request) {
        if (request.keySelector() == null) {
            return ProbeResult.failure(ProbeReasonCode.LINE_KEY_REQUIRED, ProbeReasonMetadata.inputValidation());
        }
        Optional<StrictLineKey> strictKey = request.keySelector().resolve();
        if (strictKey.isEmpty()) {
            return ProbeResult.failure(ProbeReasonCode.LINE_KEY_REQUIRED, ProbeReasonMetadata.inputValidation());
        }
        ProbeEndpointResponse response = endpointClient.exchange(singleRequest(target, strictKey.get(), request));
        return singleResponse(strictKey.get(), response);
    }

    private ProbeResult singleResponse(StrictLineKey strictKey, ProbeEndpointResponse response) {
        if (response == null || response.statusCode() < 200 || response.statusCode() >= 300) {
            return statusFailure(List.of(emptyEntry(
                    strictKey.value(),
                    strictKey.value(),
                    response == null ? null : response.statusCode())));
        }
        Optional<ProbeStatusPayload> payload = ProbeResponseJsonParser.parseStatus(
                response.payload(),
                compactionPolicy);
        if (payload.isEmpty() || !strictKey.value().equals(payload.get().key())) {
            return malformedFailure(List.of(emptyEntry(
                    strictKey.value(),
                    strictKey.value(),
                    response == null ? null : response.statusCode())));
        }
        ProbeStatusEntry entry = entry(strictKey.value(), payload.get(), response.statusCode());
        return resultForEntries(List.of(entry));
    }

    private ProbeResult batchStatus(ProbeTarget target, ProbeBatchStatusRequest request) {
        List<String> keys = normalizedKeys(request.keySelector());
        if (keys.isEmpty()) {
            return ProbeResult.failure(ProbeReasonCode.INVALID_REQUEST, ProbeReasonMetadata.inputValidation());
        }
        Map<String, ProbeStatusEntry> entriesByKey = new LinkedHashMap<>();
        List<StrictLineKey> strictKeys = collectStrictKeys(keys, entriesByKey);
        if (strictKeys.isEmpty()) {
            return ProbeResult.withActionResult(
                    ProbeReasonCode.LINE_KEY_REQUIRED,
                    ProbeReasonMetadata.inputValidation(),
                    new ProbeStatusResult(entriesInOrder(keys, entriesByKey)));
        }
        ProbeEndpointResponse response = endpointClient.exchange(batchRequest(target, strictKeys, request));
        addBatchEntries(entriesByKey, strictKeys, response);
        return resultForEntries(entriesInOrder(keys, entriesByKey));
    }

    private List<String> normalizedKeys(ProbeKeyBatchSelector selector) {
        if (selector == null) {
            return List.of();
        }
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (String raw : selector.keys()) {
            if (raw != null && !raw.trim().isEmpty()) {
                keys.add(raw.trim());
            }
        }
        return List.copyOf(keys);
    }

    private List<StrictLineKey> collectStrictKeys(
            List<String> keys,
            Map<String, ProbeStatusEntry> entriesByKey) {
        List<StrictLineKey> strictKeys = new ArrayList<>();
        for (String key : keys) {
            Optional<StrictLineKey> strictKey = StrictLineKey.parse(key);
            if (strictKey.isPresent()) {
                strictKeys.add(strictKey.get());
            } else {
                entriesByKey.put(key, invalidEntry(key));
            }
        }
        return List.copyOf(strictKeys);
    }

    private void addBatchEntries(
            Map<String, ProbeStatusEntry> entriesByKey,
            List<StrictLineKey> strictKeys,
            ProbeEndpointResponse response) {
        if (response == null || response.statusCode() < 200 || response.statusCode() >= 300) {
            addUnavailableEntries(entriesByKey, strictKeys, response);
            return;
        }
        Optional<List<ProbeStatusPayload>> payloads = ProbeResponseJsonParser.parseStatusBatch(
                response.payload(),
                compactionPolicy);
        if (payloads.isEmpty()) {
            addUnavailableEntries(entriesByKey, strictKeys, response);
            return;
        }
        Map<String, ProbeStatusPayload> byKey = payloadsByKey(payloads.get());
        for (StrictLineKey strictKey : strictKeys) {
            addAssociatedEntry(entriesByKey, strictKey, byKey.get(strictKey.value()), response.statusCode());
        }
    }

    private static Map<String, ProbeStatusPayload> payloadsByKey(List<ProbeStatusPayload> payloads) {
        Map<String, ProbeStatusPayload> byKey = new LinkedHashMap<>();
        for (ProbeStatusPayload payload : payloads) {
            if (payload.key() != null) {
                byKey.putIfAbsent(payload.key(), payload);
            }
        }
        return Map.copyOf(byKey);
    }

    private void addAssociatedEntry(
            Map<String, ProbeStatusEntry> entriesByKey,
            StrictLineKey strictKey,
            ProbeStatusPayload payload,
            int statusCode) {
        if (payload == null) {
            entriesByKey.put(strictKey.value(), emptyEntry(strictKey.value(), strictKey.value(), statusCode));
            return;
        }
        entriesByKey.put(strictKey.value(), entry(strictKey.value(), payload, statusCode));
    }

    private void addUnavailableEntries(
            Map<String, ProbeStatusEntry> entriesByKey,
            List<StrictLineKey> strictKeys,
            ProbeEndpointResponse response) {
        for (StrictLineKey strictKey : strictKeys) {
            entriesByKey.put(strictKey.value(), emptyEntry(
                    strictKey.value(),
                    strictKey.value(),
                    response == null ? null : response.statusCode()));
        }
    }

    private static List<ProbeStatusEntry> entriesInOrder(
            List<String> keys,
            Map<String, ProbeStatusEntry> entriesByKey) {
        List<ProbeStatusEntry> entries = new ArrayList<>();
        for (String key : keys) {
            ProbeStatusEntry entry = entriesByKey.get(key);
            if (entry != null) {
                entries.add(entry);
            }
        }
        return List.copyOf(entries);
    }

    private ProbeEndpointRequest singleRequest(
            ProbeTarget target,
            StrictLineKey key,
            ProbeSingleStatusRequest request) {
        String encodedKey = URLEncoder.encode(key.value(), StandardCharsets.UTF_8);
        URI endpoint = URI.create(target.baseUrl().resolve(endpointConfiguration.paths().statusPath()) + "?key=" + encodedKey);
        return endpointRequest(endpoint, "GET", "", request.timeout());
    }

    private ProbeEndpointRequest batchRequest(
            ProbeTarget target,
            List<StrictLineKey> keys,
            ProbeBatchStatusRequest request) {
        URI endpoint = target.baseUrl().resolve(endpointConfiguration.paths().statusPath());
        return endpointRequest(endpoint, "POST", batchPayload(keys), request.timeout());
    }

    private ProbeEndpointRequest endpointRequest(
            URI endpoint,
            String method,
            String payload,
            java.time.Duration timeout) {
        Map<String, String> headers = "POST".equals(method) ? Map.of("content-type", "application/json") : Map.of();
        return new ProbeEndpointRequest(
                endpoint,
                method,
                headers,
                payload,
                endpointConfiguration.requestPolicy().timeoutOrDefault(timeout),
                endpointConfiguration);
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

    private ProbeStatusEntry entry(String requestedKey, ProbeStatusPayload payload, int statusCode) {
        return new ProbeStatusEntry(
                requestedKey,
                payload.key(),
                statusCode,
                payload.endpointOk() == null || payload.endpointOk(),
                payload.hitCount(),
                payload.lastHitEpoch(),
                payload.lineResolvable(),
                payload.lineValidation(),
                payload.capturePreview(),
                payload.runtime());
    }

    private ProbeStatusEntry invalidEntry(String key) {
        return new ProbeStatusEntry(
                bounded(key),
                null,
                400,
                false,
                null,
                null,
                null,
                "line_key_required",
                null,
                null);
    }

    private static ProbeStatusEntry emptyEntry(String requestedKey, String key, Integer statusCode) {
        return new ProbeStatusEntry(
                requestedKey,
                key,
                statusCode,
                false,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private ProbeResult resultForEntries(List<ProbeStatusEntry> entries) {
        ProbeStatusResult result = new ProbeStatusResult(entries);
        if (entries.stream().anyMatch(entry -> !Boolean.TRUE.equals(entry.endpointOk()))) {
            return ProbeResult.withActionResult(ProbeReasonCode.STATUS_FAILED, ProbeReasonMetadata.status(), result);
        }
        if (entries.stream().anyMatch(entry -> Boolean.FALSE.equals(entry.lineResolvable())
                || "invalid_line_target".equals(entry.lineValidation()))) {
            return ProbeResult.withActionResult(
                    ProbeReasonCode.INVALID_LINE_TARGET,
                    ProbeReasonMetadata.status(),
                    result);
        }
        return ProbeResult.success(result);
    }

    private ProbeResult statusFailure(List<ProbeStatusEntry> entries) {
        return ProbeResult.withActionResult(
                ProbeReasonCode.STATUS_FAILED,
                ProbeReasonMetadata.status(),
                new ProbeStatusResult(entries));
    }

    private ProbeResult malformedFailure(List<ProbeStatusEntry> entries) {
        return ProbeResult.withActionResult(
                ProbeReasonCode.STATUS_FAILED,
                ProbeReasonMetadata.endpointResponse(),
                new ProbeStatusResult(entries));
    }

    private String bounded(String value) {
        if (value.length() <= compactionPolicy.maximumStringLength()) {
            return value;
        }
        return value.substring(0, compactionPolicy.maximumStringLength());
    }

}
