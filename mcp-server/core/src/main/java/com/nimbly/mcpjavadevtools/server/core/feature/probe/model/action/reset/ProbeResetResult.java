package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.reset;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.ProbeActionResult;
import java.util.List;

/**
 * Typed aggregate output from a bounded Probe reset operation.
 */
public record ProbeResetResult(
        String selector,
        String className,
        String reason,
        List<ProbeResetEntry> entries) implements ProbeActionResult {

    /**
     * Defensively copies input-associated or discovered reset entries.
     */
    public ProbeResetResult {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }
}
