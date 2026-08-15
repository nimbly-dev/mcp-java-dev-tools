package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.workspace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Workspace containment and existing-directory policy for source discovery.
 */
public class RouteSynthesisPathPolicy {

    private RouteSynthesisPathPolicy() {
    }

    /**
     * Resolves an absolute or workspace-relative directory and enforces
     * containment before checking that it exists.
     *
     * @param workspaceRoot bound workspace root
     * @param requested requested absolute or relative path
     * @return contained existing directory when valid
     */
    public static Optional<Path> resolveExistingDirectory(Path workspaceRoot, String requested) {
        if (workspaceRoot == null || requested == null || requested.isBlank()) {
            return Optional.empty();
        }
        Path root = workspaceRoot.toAbsolutePath().normalize();
        Optional<Path> resolved = resolveCandidate(root, requested.trim());
        if (resolved.isEmpty() || !Files.isDirectory(resolved.get())) {
            return Optional.empty();
        }
        return resolved;
    }

    private static Optional<Path> resolveCandidate(Path root, String requested) {
        try {
            Path candidate = Path.of(requested);
            Path resolved = candidate.isAbsolute()
                    ? candidate.toAbsolutePath().normalize()
                    : root.resolve(candidate).toAbsolutePath().normalize();
            return resolved.startsWith(root) ? Optional.of(resolved) : Optional.empty();
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    /**
     * Returns a project-relative path for deterministic output.
     *
     * @param projectRoot project root
     * @param path contained source path
     * @return normalized slash-separated relative path
     */
    public static String relativePath(Path projectRoot, Path path) {
        return projectRoot.relativize(path).toString().replace('\\', '/');
    }
}
