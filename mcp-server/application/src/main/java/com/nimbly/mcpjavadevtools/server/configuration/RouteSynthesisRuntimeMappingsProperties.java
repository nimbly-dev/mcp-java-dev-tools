package com.nimbly.mcpjavadevtools.server.configuration;

import java.util.ArrayList;
import java.util.List;
import java.net.InetAddress;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Typed allowlist and bounded response policy for runtime mapping reads. */
@ConfigurationProperties(prefix = "mcpjvm.route-synthesis")
@Getter
@Setter
public class RouteSynthesisRuntimeMappingsProperties {

    private List<String> runtimeMappingsAllowedHosts = defaultAllowedHosts();
    private int runtimeMappingsMaxResponseBytes = 1_048_576;

    private static List<String> defaultAllowedHosts() {
        return new ArrayList<>(List.of("localhost", InetAddress.getLoopbackAddress().getHostAddress()));
    }
}
