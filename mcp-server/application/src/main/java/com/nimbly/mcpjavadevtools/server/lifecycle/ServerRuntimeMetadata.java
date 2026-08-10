package com.nimbly.mcpjavadevtools.server.lifecycle;

import java.time.Instant;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ServerRuntimeMetadata {

    private final String version;
    private final String buildFingerprint;

    public ServerRuntimeMetadata(@Value("${spring.ai.mcp.server.version:0.1.9}") String version) {
        this.version = version;
        this.buildFingerprint = "implementation-version:" + version;
    }

    public String version() {
        return version;
    }

    public String buildFingerprint() {
        return buildFingerprint;
    }

    public String timestamp() {
        return Instant.now().toString();
    }

    public long processId() {
        return ProcessHandle.current().pid();
    }

    public Long parentProcessId() {
        Optional<ProcessHandle> parent = ProcessHandle.current().parent();
        return parent.map(ProcessHandle::pid).orElse(null);
    }
}
