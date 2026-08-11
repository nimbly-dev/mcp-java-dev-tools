package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint;

import java.util.Objects;

/**
 * Relative Sidecar endpoint paths shared by later Probe actions.
 *
 * @param statusPath status endpoint path
 * @param resetPath reset endpoint path
 * @param actuatePath actuation endpoint path
 * @param capturePath capture endpoint path
 * @param profilerPath profiler endpoint path
 */
public record ProbeEndpointPaths(
        String statusPath,
        String resetPath,
        String actuatePath,
        String capturePath,
        String profilerPath) {

    /**
     * Validates that endpoint paths remain relative to a selected base URL.
     */
    public ProbeEndpointPaths {
        statusPath = requiredPath(statusPath, "statusPath");
        resetPath = requiredPath(resetPath, "resetPath");
        actuatePath = requiredPath(actuatePath, "actuatePath");
        capturePath = requiredPath(capturePath, "capturePath");
        profilerPath = requiredPath(profilerPath, "profilerPath");
    }

    private static String requiredPath(String path, String fieldName) {
        Objects.requireNonNull(path, fieldName + " must not be null");
        if (containsControlCharacter(path)) {
            throw new IllegalArgumentException(fieldName + " must not contain control characters");
        }
        String normalized = path.trim();
        if (!isSafeRelativePath(normalized)) {
            throw new IllegalArgumentException(fieldName + " must be a safe relative path");
        }
        return normalized;
    }

    private static boolean isSafeRelativePath(String path) {
        return path.startsWith("/")
                && !path.startsWith("//")
                && path.indexOf('?') < 0
                && path.indexOf('#') < 0
                && path.indexOf('\\') < 0
                && path.indexOf('%') < 0
                && !containsTraversal(path);
    }

    private static boolean containsControlCharacter(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsTraversal(String path) {
        for (String segment : path.split("/")) {
            if (".".equals(segment) || "..".equals(segment)) {
                return true;
            }
        }
        return false;
    }
}
