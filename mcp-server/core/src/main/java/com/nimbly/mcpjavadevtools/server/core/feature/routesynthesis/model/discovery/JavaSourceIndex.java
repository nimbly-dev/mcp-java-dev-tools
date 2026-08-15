package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.discovery;

import java.util.List;

/**
 * Bounded deterministic Java source index.
 *
 * @param scannedJavaFiles number of readable Java files considered
 * @param files parsed source entries
 */
public record JavaSourceIndex(int scannedJavaFiles, List<JavaSourceFile> files) {

    /**
     * Defensively copies parsed source entries.
     */
    public JavaSourceIndex {
        files = files == null ? List.of() : List.copyOf(files);
    }
}
