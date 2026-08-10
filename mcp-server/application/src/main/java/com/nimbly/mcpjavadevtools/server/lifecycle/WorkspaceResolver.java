package com.nimbly.mcpjavadevtools.server.lifecycle;

import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.ApplicationArguments;

class WorkspaceResolver {

    private static final String WORKSPACE_ROOT_ENV = "MCP_WORKSPACE_ROOT";
    private static final String ROOTS_AVAILABLE = "available";
    private static final String ROOTS_UNAVAILABLE = "unavailable";
    private static final String ROOTS_NOT_REQUESTED = "not_requested";

    private WorkspaceResolver() {
    }

    static WorkspaceSnapshot initial(
            ApplicationArguments arguments,
            Map<String, String> environment,
            Path workingDirectory) {
        String argumentRoot = firstOption(arguments, "workspace-root");
        if (hasText(argumentRoot)) {
            return bound(argumentRoot, WorkspaceSource.ARG, ROOTS_NOT_REQUESTED);
        }

        String environmentRoot = environment.get(WORKSPACE_ROOT_ENV);
        if (hasText(environmentRoot)) {
            return bound(environmentRoot, WorkspaceSource.ENV, ROOTS_NOT_REQUESTED);
        }

        String sessionRoot = sessionRoot(environment);
        if (hasText(sessionRoot)) {
            return bound(sessionRoot, WorkspaceSource.SESSION, ROOTS_NOT_REQUESTED);
        }

        Path normalizedWorkingDirectory = normalize(workingDirectory);
        if (hasProbeConfig(normalizedWorkingDirectory)) {
            return new WorkspaceSnapshot(normalizedWorkingDirectory, WorkspaceSource.CWD, null, ROOTS_NOT_REQUESTED);
        }
        return missing(ROOTS_NOT_REQUESTED);
    }

    static WorkspaceSnapshot fromRoots(List<McpSchema.Root> roots) {
        List<Path> candidates = rootCandidates(roots);
        List<Path> canonicalCandidates = canonicalCandidates(candidates);
        if (canonicalCandidates.size() > 1) {
            return new WorkspaceSnapshot(null, WorkspaceSource.AMBIGUOUS, "workspace_context_ambiguous", ROOTS_AVAILABLE);
        }
        if (!canonicalCandidates.isEmpty()) {
            return new WorkspaceSnapshot(canonicalCandidates.getFirst(), WorkspaceSource.ROOTS, null, ROOTS_AVAILABLE);
        }
        if (!candidates.isEmpty()) {
            return new WorkspaceSnapshot(candidates.getFirst(), WorkspaceSource.ROOTS, null, ROOTS_AVAILABLE);
        }
        return missing(ROOTS_AVAILABLE);
    }

    static WorkspaceSnapshot rootsUnavailable(WorkspaceSnapshot current) {
        return new WorkspaceSnapshot(
                current.root(),
                current.source(),
                current.reasonCode(),
                ROOTS_UNAVAILABLE);
    }

    private static String firstOption(ApplicationArguments arguments, String optionName) {
        List<String> values = arguments.getOptionValues(optionName);
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.getFirst();
    }

    private static String sessionRoot(Map<String, String> environment) {
        String initCwd = environment.get("INIT_CWD");
        if (hasText(initCwd)) {
            return initCwd;
        }
        return environment.get("PWD");
    }

    private static WorkspaceSnapshot bound(String root, WorkspaceSource source, String discoveryStatus) {
        try {
            return new WorkspaceSnapshot(normalize(Path.of(root)), source, null, discoveryStatus);
        } catch (InvalidPathException exception) {
            return new WorkspaceSnapshot(null, source, "workspace_context_invalid", discoveryStatus);
        }
    }

    private static List<Path> rootCandidates(List<McpSchema.Root> roots) {
        Set<Path> candidates = new LinkedHashSet<>();
        for (McpSchema.Root root : roots) {
            pathFromUri(root.uri()).ifPresent(candidates::add);
        }
        return new ArrayList<>(candidates);
    }

    private static List<Path> canonicalCandidates(List<Path> candidates) {
        return candidates.stream().filter(WorkspaceResolver::hasProbeConfig).toList();
    }

    private static java.util.Optional<Path> pathFromUri(String value) {
        try {
            URI uri = URI.create(value);
            if (!"file".equalsIgnoreCase(uri.getScheme())) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(normalize(Path.of(uri)));
        } catch (IllegalArgumentException exception) {
            return java.util.Optional.empty();
        }
    }

    private static Path normalize(Path value) {
        Path resolved = value.toAbsolutePath().normalize();
        Path fileName = resolved.getFileName();
        if (fileName != null && ".mcpjvm".equalsIgnoreCase(fileName.toString()) && resolved.getParent() != null) {
            return resolved.getParent();
        }
        return resolved;
    }

    private static boolean hasProbeConfig(Path root) {
        return Files.isRegularFile(root.resolve(".mcpjvm").resolve("probe-config.json"));
    }

    private static WorkspaceSnapshot missing(String discoveryStatus) {
        return new WorkspaceSnapshot(null, WorkspaceSource.MISSING, "workspace_context_missing", discoveryStatus);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
