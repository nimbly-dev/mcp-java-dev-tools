package com.nimbly.mcpjavadevtools.server.configuration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Application-owned Failure Analysis defaults mapped into a bounded Core policy. */
@ConfigurationProperties(prefix = "mcpjvm.failure-analysis")
@Getter
@Setter
public class FailureAnalysisConfigurationProperties {

    private Endpoint endpoint = new Endpoint();

    /** Configurable values that can only narrow the Core hard ceilings. */
    @Getter
    @Setter
    public static class Endpoint {

        private int timeoutMs = 15000;
        private int maximumTraceCharacters = 200000;
        private int maximumResponsePayloadBytes = 1048576;
        private int maximumStringLength = 256;
        private int maximumFrames = 32;
        private int maximumSections = 16;
    }
}
