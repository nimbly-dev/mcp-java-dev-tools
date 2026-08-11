package com.nimbly.mcpjavadevtools.server.core.feature.probe.response;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.response.ProbeResponseCompactionPolicy;

/**
 * Applies the shared configured text bound before diagnostics leave Core behavior.
 */
public class ProbeResponseTextCompactor {

    private ProbeResponseTextCompactor() {
    }

    /**
     * Returns text within the Core-owned configured compaction policy.
     *
     * @param value bounded Sidecar diagnostic value
     * @param compactionPolicy configured Core response policy
     * @return compact value
     */
    public static String compact(String value, ProbeResponseCompactionPolicy compactionPolicy) {
        if (value.length() <= compactionPolicy.maximumStringLength()) {
            return value;
        }
        return value.substring(0, compactionPolicy.maximumStringLength());
    }
}
