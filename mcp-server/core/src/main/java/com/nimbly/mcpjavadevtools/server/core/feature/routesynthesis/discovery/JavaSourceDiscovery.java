package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.discovery;

import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.discovery.JavaSourceIndex;
import java.nio.file.Path;
import java.util.List;

/**
 * Contained Java source, class, and method discovery contract.
 */
public interface JavaSourceDiscovery {

    /**
     * Discovers bounded Java source under the supplied contained roots.
     *
     * @param projectRoot selected project root
     * @param additionalSourceRoots additional contained roots
     * @param classHint optional class hint used for traversal prioritization
     * @return deterministic source index
     */
    JavaSourceIndex discover(
            Path projectRoot,
            List<Path> additionalSourceRoots,
            String classHint);
}
