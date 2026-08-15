package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.createrecipe;

import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.discoverhandlers.RouteSynthesisHandler;

/** Internal deterministic outcome of Synthesizer selection and recipe shaping. */
public record RouteSynthesisSynthesisResult(
        boolean compatible,
        String reasonCode,
        String failedStep,
        String nextActionCode,
        String synthesizerUsed,
        RouteSynthesisHandler selectedHandler) {

    /** Creates a successful synthesis result. */
    public static RouteSynthesisSynthesisResult success(
            String synthesizerUsed,
            RouteSynthesisHandler selectedHandler) {
        return new RouteSynthesisSynthesisResult(
                true, null, null, null, synthesizerUsed, selectedHandler);
    }

    /** Creates a failed synthesis result. */
    public static RouteSynthesisSynthesisResult failure(
            String reasonCode,
            String failedStep,
            String nextActionCode) {
        return new RouteSynthesisSynthesisResult(
                false, reasonCode, failedStep, nextActionCode, null, null);
    }
}
