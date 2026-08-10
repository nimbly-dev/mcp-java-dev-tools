package com.nimbly.mcpjavadevtools.poc.mcp;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.stereotype.Component;

@Component
public final class StatusResource {

    @McpResource(
            uri = "mcp-java-dev-tools://status",
            name = "status",
            description = "Server status and defaults",
            mimeType = "application/json")
    public String status() {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("ok", true);
        output.put("name", "mcp-java-dev-tools");
        output.put("version", "0.1.0-poc");
        output.put("buildFingerprint", "spring-ai-stdio-poc");
        output.put("pid", ProcessHandle.current().pid());
        output.put("ppid", ProcessHandle.current().parent().map(ProcessHandle::pid).orElse(null));
        output.put("workspaceRoot", null);
        output.put("workspaceRootSource", "missing");
        output.put("probe", probeDefaults());
        output.put("recipe", Map.of("hasCustomTemplate", false));
        output.put("auth", Map.of("credentialDiscovery", "disabled"));
        output.put("time", Instant.now().toString());
        return McpToolResponse.json(output);
    }

    private Map<String, Object> probeDefaults() {
        Map<String, Object> probe = new LinkedHashMap<>();
        probe.put("baseUrl", "http://127.0.0.1:9191");
        probe.put("statusPath", "/__probe/status");
        probe.put("resetPath", "/__probe/reset");
        probe.put("actuatePath", "/__probe/actuate");
        probe.put("capturePath", "/__probe/capture");
        probe.put("profilerPath", "/__probe/profiler");
        probe.put("waitMaxRetriesDefault", 20);
        probe.put("waitUnreachableRetryEnabled", false);
        probe.put("waitUnreachableMaxRetries", 0);
        probe.put("activeProfile", null);
        probe.put("profileSource", null);
        probe.put("registryProbeCount", 0);
        probe.put("allowNonWrappedExecutable", false);
        return probe;
    }
}
