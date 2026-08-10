package com.nimbly.mcpjavadevtools.server.lifecycle;

import java.nio.file.Path;

public record WorkspaceSnapshot(Path root, WorkspaceSource source, String reasonCode, String rootDiscoveryStatus) {

    public String rootText() {
        if (root == null) {
            return null;
        }
        return root.toString();
    }

    public String sourceText() {
        return source.wireValue();
    }
}
