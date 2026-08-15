package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.result;

/**
 * Named inputs for a candidate disambiguation result.
 *
 * @param resultType result presentation type
 * @param status public status
 * @param reasonCode stable reason code
 * @param failedStep failed processing step
 * @param nextActionCode next-action code
 * @param nextAction operator guidance
 * @param actionResult candidate payload
 */
public record RouteSynthesisDisambiguationDetails(
        String resultType,
        String status,
        String reasonCode,
        String failedStep,
        String nextActionCode,
        String nextAction,
        RouteSynthesisActionResult actionResult) {
}
