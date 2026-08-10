package com.nimbly.mcpjavadevtools.server.mcp.tools.debugcheck;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbly.mcpjavadevtools.server.lifecycle.ServerRuntimeMetadata;
import com.nimbly.mcpjavadevtools.server.lifecycle.WorkspaceContext;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

class DebugCheckMcpToolTest {

    @Test
    void mapsRuntimeMetadataIntoTheStableDebugCheckResponse() {
        ServerRuntimeMetadata metadata = new ServerRuntimeMetadata("0.1.9-test");
        WorkspaceContext workspace = new WorkspaceContext(new DefaultApplicationArguments());
        DebugCheckResponse response = new DebugCheckMcpTool(metadata, workspace).response();

        assertThat(response.ok()).isTrue();
        assertThat(response.version()).isEqualTo("0.1.9-test");
        assertThat(response.buildFingerprint()).isEqualTo("implementation-version:0.1.9-test");
        assertThat(response.pid()).isPositive();
    }
}
