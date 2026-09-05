package com.nimbly.mcpjavadevtools.server.core.feature.probe.operation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.ProbeFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.ProbeAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.request.ProbeRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.request.ProbeRequestFactory;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.request.ProbeRequestInput;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResult;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.operation.ProbeOperationArguments;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRegistration;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRegistrationContract;
import com.nimbly.mcpjavadevtools.server.core.operation.catalog.Operation;
import com.nimbly.mcpjavadevtools.server.core.operation.composition.CoreOperationDirectory;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationDescriptor;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.CoreOperationSafetyPolicy;
import com.nimbly.mcpjavadevtools.server.core.operation.schema.CoreOperationResultSchemas;
import com.nimbly.mcpjavadevtools.server.core.operation.schema.OperationSchema;
import com.nimbly.mcpjavadevtools.server.core.operation.trace.OperationLegacyIdentity;
import com.nimbly.mcpjavadevtools.server.core.operation.trace.OperationTraceMetadata;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationId;

/** Binds all seven Probe owners to the typed request factory. */
public class ProbeOperationRegistrations {

    private ProbeOperationRegistrations() {
    }

    public static List<OperationRegistration<?, ?>> create(
            ProbeOperationCatalog catalog, ObjectMapper mapper) {
        Objects.requireNonNull(catalog, "probe catalog must not be null");
        Objects.requireNonNull(mapper, "mapper must not be null");
        EnumMap<ProbeAction, Operation<ProbeAction, ProbeRequest, ProbeResult>> owners =
                new EnumMap<>(ProbeAction.class);
        for (Operation<ProbeAction, ProbeRequest, ProbeResult> owner : catalog.operations()) {
            if (owners.put(owner.operationId(), owner) != null) {
                throw new IllegalArgumentException("duplicate Probe owner: " + owner.operationId());
            }
        }
        ProbeRequestFactory factory = new ProbeRequestFactory();
        return java.util.Arrays.stream(ProbeAction.values())
                .<OperationRegistration<?, ?>>map(action ->
                        register(action, owners.get(action), factory, mapper))
                .toList();
    }

    static OperationRegistration<ProbeOperationArguments, ProbeResult> register(
            ProbeAction action,
            Operation<ProbeAction, ProbeRequest, ProbeResult> owner,
            ProbeRequestFactory factory,
            ObjectMapper mapper) {
        Objects.requireNonNull(owner, "probe operation owner must not be null");
        OperationDescriptor descriptor = descriptor(action, owner);
        return new OperationRegistration<>(
                descriptor,
                ProbeOperationArguments.class,
                ProbeResult.class,
                new OperationRegistrationContract(
                        schema(action), CoreOperationResultSchemas.probe(),
                        CoreOperationSafetyPolicy.forOperation(
                                descriptor.operationId().value(), descriptor.trace().sideEffect())),
                input -> mapper.convertValue(input, ProbeOperationArguments.class),
                input -> owner.execute(factory.create(action, decode((ProbeOperationArguments) input))),
                result -> mapper.valueToTree(ProbeResult.class.cast(result)),
                CoreOperationDirectory.class.getName(),
                identity(action));
    }

    static ProbeRequestInput decode(ProbeOperationArguments arguments) {
        return new ProbeRequestInput(
                arguments.baseUrl(),
                arguments.probeId(),
                arguments.http().headers(),
                arguments.timeoutMs(),
                arguments.key(),
                arguments.keys(),
                arguments.lineHint(),
                arguments.className(),
                arguments.pollIntervalMs(),
                arguments.maxRetries(),
                arguments.captureId(),
                arguments.action(),
                arguments.sessionId(),
                arguments.actuatorId(),
                arguments.targetKey(),
                arguments.returnBoolean(),
                arguments.ttlMs(),
                arguments.provider(),
                arguments.event(),
                arguments.intervalNanos(),
                arguments.outputPath(),
                arguments.outputFormat());
    }

    static OperationSchema schema(ProbeAction action) {
        return ProbeOperationSchemas.schema(action);
    }

    static OperationDescriptor descriptor(
            ProbeAction action, Operation<ProbeAction, ProbeRequest, ProbeResult> owner) {
        String id = OperationId.fromLegacy(ProbeOperationCatalog.TOOL_NAME, action.value()).value();
        String sideEffect = CoreOperationSafetyPolicy.sideEffect(id);
        return new OperationDescriptor(
                ProbeOperationCatalog.TOOL_NAME,
                action.value(),
                ProbeOperationArguments.class.getName(),
                ProbeResult.class.getName(),
                owner.executableOwner(),
                new OperationTraceMetadata(
                        "java_mcp_operation_directory_adapter",
                        ProbeOperationRegistrations.class.getName(),
                        ProbeFeature.class.getName(),
                        ProbeOperationRegistrations.class.getName(),
                        "mcpjvm-610:" + id + ":typed-binding",
                        sideEffect,
                        Map.of("capabilityCatalog", ProbeOperationCatalog.class.getName(),
                                "executableOwner", owner.executableOwner(),
                                "operationId", id)));
    }

    static OperationLegacyIdentity identity(ProbeAction action) {
        return new OperationLegacyIdentity(
                ProbeOperationCatalog.TOOL_NAME,
                action.value(),
                false,
                Map.of(),
                "probe_action_input_to_typed_request_factory",
                "probe_result_status_reason_and_action_result_preserved",
                "probe_" + action.value());
    }
}
