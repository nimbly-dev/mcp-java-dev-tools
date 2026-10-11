package com.nimbly.mcpjavadevtools.server.core.operation.trace;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRegistration;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationDescriptor;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationManifest;

/** Generates stable Tool-to-Operation inventory rows from a live catalog. */
public class OperationTraceInventory {

    private OperationTraceInventory() {
    }

    /** Generates aggregate rows from an assembled immutable manifest. */
    public static List<OperationTraceEntry> generate(OperationManifest manifest) {
        Objects.requireNonNull(manifest, "manifest must not be null");
        List<OperationTraceEntry> entries = new ArrayList<>();
        for (OperationRegistration<?, ?> registration
                : manifest.registrations()) {
            OperationDescriptor descriptor = registration.descriptor();
            OperationTraceMetadata trace = descriptor.trace();
            entries.add(new OperationTraceEntry(
                    descriptor.toolName(),
                    descriptor.action(),
                    descriptor.requestType(),
                    descriptor.resultType(),
                    trace.mcpAdapter(),
                    trace.requestMapper(),
                    trace.coreFeature(),
                    OperationDescriptor.class.getName(),
                    descriptor.executableOwner(),
                    trace.responseMapper(),
                    trace.focusedEvidence(),
                    trace.sideEffect(),
                    trace.collaboratorRoles(),
                    descriptor.operationId().value()));
        }
        return List.copyOf(entries);
    }
}
