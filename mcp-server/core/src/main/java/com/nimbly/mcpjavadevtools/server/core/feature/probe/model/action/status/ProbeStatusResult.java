package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.status;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.ProbeActionResult;
import java.util.List;

/**
 * Typed ordered output from single or batch Probe status operations.
 *
 * @param entries result entries in requested-key order
 */
public record ProbeStatusResult(List<ProbeStatusEntry> entries) implements ProbeActionResult {

    /**
     * Defensively copies the input-associated result order.
     */
    public ProbeStatusResult {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }
}
