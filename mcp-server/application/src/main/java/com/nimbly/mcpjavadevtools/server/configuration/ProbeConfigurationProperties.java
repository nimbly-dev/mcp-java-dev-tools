package com.nimbly.mcpjavadevtools.server.configuration;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Capability-first operational defaults bound outside the Spring-independent Core Feature.
 */
@ConfigurationProperties(prefix = "mcpjvm.probe")
@Getter
@Setter
public class ProbeConfigurationProperties {

    private Endpoint endpoint = new Endpoint();
    private Capture capture = new Capture();
    private Registry registry = new Registry();

    /**
     * Endpoint timing and resource defaults, bounded again by Core hard ceilings.
     */
    @Getter
    @Setter
    public static class Endpoint {

        private String defaultBaseUrl;
        private int timeoutMs = 15000;
        private int pollIntervalMs = 500;
        private int maxRetries = 1;
        private boolean unreachableRetryEnabled;
        private int unreachableMaxRetries = 3;
        private int maximumResponsePayloadBytes = 1048576;

    }

    /**
     * Capture response compaction defaults that can only restrict Core safety policy.
     */
    @Getter
    @Setter
    public static class Capture {

        private Response response = new Response();

    }

    /**
     * Bounded capture response options.
     */
    @Getter
    @Setter
    public static class Response {

        private boolean includeExecutionPaths;
        private int maxStringLength = 256;
        private int maxExecutionPaths = 32;
        private Set<String> safeDiagnosticValueHeaders = new LinkedHashSet<>(Set.of(
                "content-length",
                "content-type",
                "etag",
                "traceparent",
                "x-correlation-id",
                "x-request-id"));

    }

    /**
     * Application-owned Probe registry binding used only to compose Core routing.
     */
    @Getter
    @Setter
    public static class Registry {

        private List<Registration> registrations = new ArrayList<>();
    }

    /**
     * One configured Probe identifier and its Sidecar base URL.
     */
    @Getter
    @Setter
    public static class Registration {

        private String id;
        private String baseUrl;
    }
}
