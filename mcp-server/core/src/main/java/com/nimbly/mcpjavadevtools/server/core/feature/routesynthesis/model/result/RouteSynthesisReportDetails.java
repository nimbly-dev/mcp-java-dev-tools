package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.result;

import java.util.List;

/**
 * Named deterministic report fields used when evidence and strategies are
 * both present.
 *
 * @param status public status
 * @param reasonCode stable reason code
 * @param failedStep failed processing step
 * @param nextActionCode next-action code
 * @param nextAction operator guidance
 * @param evidence bounded evidence
 * @param attemptedStrategies bounded strategy names
 */
public record RouteSynthesisReportDetails(
        String status,
        String reasonCode,
        String failedStep,
        String nextActionCode,
        String nextAction,
        List<String> evidence,
        List<String> attemptedStrategies) {
}
