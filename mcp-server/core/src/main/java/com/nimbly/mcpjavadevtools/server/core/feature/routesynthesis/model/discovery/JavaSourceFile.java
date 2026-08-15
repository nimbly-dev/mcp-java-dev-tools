package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.discovery;

import java.nio.file.Path;
import java.util.List;

/**
 * Parsed Java source file index entry.
 *
 * @param file absolute source path
 * @param packageName declared package when available
 * @param className declared class-like type when available
 * @param methods parsed methods
 */
public record JavaSourceFile(
        Path file,
        String packageName,
        String className,
        List<JavaSourceMethod> methods) {

    /**
     * Defensively copies parsed methods.
     */
    public JavaSourceFile {
        methods = methods == null ? List.of() : List.copyOf(methods);
    }

    /**
     * Returns the fully qualified class name when package and class are known.
     *
     * @return FQCN or null
     */
    public String fqcn() {
        if (className == null || className.isBlank()) {
            return null;
        }
        if (packageName == null || packageName.isBlank()) {
            return className;
        }
        return packageName + "." + className;
    }
}
