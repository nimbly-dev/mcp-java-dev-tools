package com.nimbly.mcpjavadevtools.server.configuration;

import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.discovery.FileSystemJavaSourceDiscovery;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.discovery.JavaSourceDiscovery;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.discovery.JavaSourceIndex;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.workspace.RouteSynthesisWorkspaceProvider;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.workspace.RouteSynthesisWorkspaceSnapshot;
import java.nio.file.Path;
import java.util.List;

/**
 * Application composition adapter that binds source discovery to the current workspace snapshot.
 */
class RouteSynthesisWorkspaceJavaSourceDiscovery implements JavaSourceDiscovery {

    private final RouteSynthesisWorkspaceProvider workspaceProvider;

    RouteSynthesisWorkspaceJavaSourceDiscovery(RouteSynthesisWorkspaceProvider workspaceProvider) {
        this.workspaceProvider = workspaceProvider;
    }

    @Override
    public JavaSourceIndex discover(Path projectRoot, List<Path> additionalSourceRoots, String classHint) {
        RouteSynthesisWorkspaceSnapshot snapshot = workspaceProvider.current().orElse(null);
        if (snapshot == null) {
            return new JavaSourceIndex(0, List.of());
        }
        return new FileSystemJavaSourceDiscovery(snapshot).discover(
                projectRoot, additionalSourceRoots, classHint);
    }
}
