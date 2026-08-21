package com.nimbly.mcpjavadevtools.server.configuration;

import java.util.LinkedHashSet;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Typed Application configuration for the bounded HTTP transport provider. */
@ConfigurationProperties(prefix = "mcpjvm.transport-execution")
@Getter
@Setter
public class TransportExecutionConfigurationProperties {

    private Http http = new Http();

    /** Additional exact hosts allowed beyond the Core loopback defaults. */
    @Getter
    @Setter
    public static class Http {

        private Set<String> allowedHosts = new LinkedHashSet<>();
    }
}
