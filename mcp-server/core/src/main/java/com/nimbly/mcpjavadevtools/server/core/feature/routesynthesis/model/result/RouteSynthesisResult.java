package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.result;

import java.util.List;
import java.util.Objects;

/**
 * Deterministic Core result shared by all Route Synthesis actions.
 *
 * @param resultType stable result presentation type
 * @param status stable public status
 * @param reasonCode stable reason code when applicable
 * @param failedStep owned processing step when applicable
 * @param nextActionCode deterministic next-action code when applicable
 * @param nextAction deterministic operator guidance when applicable
 * @param actionResult typed action payload when available
 * @param evidence bounded safe evidence
 * @param attemptedStrategies bounded strategy names
 */
public record RouteSynthesisResult(
        String resultType,
        String status,
        String reasonCode,
        String failedStep,
        String nextActionCode,
        String nextAction,
        RouteSynthesisActionResult actionResult,
        List<String> evidence,
        List<String> attemptedStrategies) {

    /**
     * Validates and defensively copies deterministic output collections.
     */
    public RouteSynthesisResult {
        requireText(resultType, "resultType");
        requireText(status, "status");
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        attemptedStrategies = attemptedStrategies == null
                ? List.of()
                : List.copyOf(attemptedStrategies);
    }

    /**
     * Creates a successful result with a typed payload.
     *
     * @param resultType result presentation type
     * @param actionResult typed action payload
     * @return successful result
     */
    public static RouteSynthesisResult success(
            String resultType,
            RouteSynthesisActionResult actionResult) {
        return new RouteSynthesisResult(
                resultType,
                "ok",
                null,
                null,
                null,
                null,
                Objects.requireNonNull(actionResult, "actionResult must not be null"),
                List.of(),
                List.of());
    }

    /**
     * Creates a deterministic report without a typed action payload.
     *
     * @param status public status
     * @param reasonCode stable reason code
     * @param failedStep failed processing step
     * @param nextActionCode next-action code
     * @param nextAction operator guidance
     * @return report result
     */
    public static RouteSynthesisResult report(
            String status,
            String reasonCode,
            String failedStep,
            String nextActionCode,
            String nextAction) {
        return new RouteSynthesisResult(
                "report",
                status,
                reasonCode,
                failedStep,
                nextActionCode,
                nextAction,
                null,
                List.of(),
                List.of());
    }

    /**
     * Creates a report from a named details model.
     *
     * @param details report details
     * @return report result
     */
    public static RouteSynthesisResult report(RouteSynthesisReportDetails details) {
        Objects.requireNonNull(details, "details must not be null");
        return new RouteSynthesisResult(
                "report",
                details.status(),
                details.reasonCode(),
                details.failedStep(),
                details.nextActionCode(),
                details.nextAction(),
                null,
                details.evidence(),
                details.attemptedStrategies());
    }

    /**
     * Creates a typed action-shaped report with bounded failure metadata.
     *
     * @param resultType action-shaped result type
     * @param details report details
     * @param actionResult typed action payload
     * @return report result
     */
    public static RouteSynthesisResult report(
            String resultType,
            RouteSynthesisReportDetails details,
            RouteSynthesisActionResult actionResult) {
        requireText(resultType, "resultType");
        Objects.requireNonNull(details, "details must not be null");
        return new RouteSynthesisResult(
                resultType,
                details.status(),
                details.reasonCode(),
                details.failedStep(),
                details.nextActionCode(),
                details.nextAction(),
                actionResult,
                details.evidence(),
                details.attemptedStrategies());
    }

    /**
     * Creates a disambiguation result with candidate payload.
     *
     * @param details disambiguation details
     * @return disambiguation result
     */
    public static RouteSynthesisResult disambiguation(
            RouteSynthesisDisambiguationDetails details) {
        Objects.requireNonNull(details, "details must not be null");
        return new RouteSynthesisResult(
                details.resultType(),
                details.status(),
                details.reasonCode(),
                details.failedStep(),
                details.nextActionCode(),
                details.nextAction(),
                details.actionResult(),
                List.of(),
                List.of());
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
