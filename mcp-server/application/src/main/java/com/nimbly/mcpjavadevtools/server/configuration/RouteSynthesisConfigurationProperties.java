package com.nimbly.mcpjavadevtools.server.configuration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed Application binding for external Route Synthesis module presence.
 *
 * <p>The configured values are intentionally not exposed to Core or public
 * output. Core receives only the sanitized module count through composition.</p>
 */
@ConfigurationProperties(prefix = "mcpjvm.route-synthesis.synthesizer")
@Getter
@Setter
public class RouteSynthesisConfigurationProperties {

    private String externalModules = "";

    /**
     * Counts configured module specifications without returning their values.
     *
     * @return bounded configured module count
     */
    public int configuredExternalModuleCount() {
        if (externalModules == null || externalModules.isBlank()) {
            return 0;
        }
        int count = 0;
        for (String value : externalModules.split("[;,\\r\\n]+")) {
            if (!value.isBlank()) {
                count++;
            }
        }
        return count;
    }
}
