package com.nimbly.mcpjavadevtools.server.configuration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Application-owned binding for JVM lifecycle environment and defaults.
 */
@ConfigurationProperties(prefix = "mcpjvm.jvm-lifecycle")
@Getter
@Setter
public class JvmLifecycleConfigurationProperties {

    private Artifacts artifacts = new Artifacts();
    private String javaBin = "java";
    private String allowedProbeHosts = "";

    /** Explicit artifact overrides. */
    @Getter
    @Setter
    public static class Artifacts {

        private String helperJar = "";
        private String agentJar = "";
    }
}
