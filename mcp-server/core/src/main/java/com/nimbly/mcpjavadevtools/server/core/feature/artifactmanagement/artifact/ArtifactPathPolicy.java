package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.io.IOException;
import java.util.regex.Pattern;

/** Workspace-contained Artifact path policy with traversal and segment checks. */
public final class ArtifactPathPolicy {

    private static final Pattern SEPARATOR = Pattern.compile("[\\\\/]");
    private final Path workspaceRoot;
    private final Path realWorkspaceRoot;

    /** Creates a policy rooted at one normalized workspace path. */
    public ArtifactPathPolicy(Path workspaceRoot) {
        if (workspaceRoot == null) {
            throw new IllegalArgumentException("workspaceRoot must not be null");
        }
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        this.realWorkspaceRoot = realPathOrNormalized(this.workspaceRoot);
    }

    /** Resolves safe Artifact path segments under the workspace. */
    public Path resolve(String... segments) {
        Path candidate = workspaceRoot;
        for (String segment : segments) {
            validateSegment(segment);
            candidate = candidate.resolve(segment);
        }
        return check(candidate);
    }

    /** Checks an already assembled path so later child resolution cannot bypass containment. */
    public Path check(Path candidate) {
        if (candidate == null) {
            throw new ArtifactOperationException(
                    "artifact_path_invalid", "Artifact path must not be null");
        }
        Path normalized = candidate.toAbsolutePath().normalize();
        if (!normalized.startsWith(workspaceRoot)) {
            throw new ArtifactOperationException(
                    "artifact_path_escape",
                    "Artifact path resolves outside the workspace");
        }
        ensureRealContained(normalized);
        return normalized;
    }

    /** Converts a path to a stable workspace-relative representation. */
    public String relative(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(workspaceRoot)) {
            throw new ArtifactOperationException(
                    "artifact_path_escape",
                    "Artifact path resolves outside the workspace");
        }
        ensureRealContained(normalized);
        return workspaceRoot.relativize(normalized).toString().replace('\\', '/');
    }

    private void ensureRealContained(Path candidate) {
        Path existing = candidate;
        while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS) && existing.getParent() != null) {
            existing = existing.getParent();
        }
        Path real = realPathOrNormalized(existing);
        if (!real.startsWith(realWorkspaceRoot)) {
            throw new ArtifactOperationException(
                    "artifact_path_symlink_escape",
                    "Artifact path resolves through a symlink outside the workspace");
        }
    }

    private static Path realPathOrNormalized(Path path) {
        try {
            return Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                    ? path.toRealPath()
                    : path.toAbsolutePath().normalize();
        } catch (IOException exception) {
            throw new ArtifactOperationException(
                    "artifact_path_unresolvable",
                    "Artifact path could not be resolved safely");
        }
    }

    /** Validates a single user-controlled Artifact path segment. */
    public static void validateSegment(String value) {
        if (value == null || value.isBlank() || ".".equals(value) || "..".equals(value)
                || value.indexOf('\0') >= 0 || SEPARATOR.matcher(value).find()) {
            throw new ArtifactOperationException(
                    "artifact_path_segment_invalid",
                    "Artifact path values must be single safe path segments");
        }
    }
}
