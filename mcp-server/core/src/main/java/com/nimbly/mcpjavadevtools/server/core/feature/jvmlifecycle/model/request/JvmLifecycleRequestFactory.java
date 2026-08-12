package com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.request;

import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.action.JvmLifecycleAction;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.action.attach.AttachRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.action.deactivate.DeactivateRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.action.listjvms.ListJvmsRequest;
import java.util.Objects;

/**
 * Creates validated Core requests from the transport boundary values.
 */
public final class JvmLifecycleRequestFactory {

    private static final String DEFAULT_PROBE_HOST = String.join(".", "127", "0", "0", "1");
    private static final int DEFAULT_PROBE_PORT = 9191;
    private static final int MAX_TEXT_LENGTH = 2048;

    /**
     * Creates one action-specific request.
     *
     * @param action selected action
     * @param input nullable boundary values
     * @return validated request
     */
    public JvmLifecycleRequest create(JvmLifecycleAction action, JvmLifecycleInput input) {
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(input, "input must not be null");
        return switch (action) {
            case LIST_JVMS -> list(input);
            case ATTACH -> attach(input);
            case DEACTIVATE -> deactivate(input);
        };
    }

    private static ListJvmsRequest list(JvmLifecycleInput input) {
        if (hasMutationFields(input)) {
            throw new IllegalArgumentException("list_jvms input must be empty");
        }
        return new ListJvmsRequest();
    }

    private static AttachRequest attach(JvmLifecycleInput input) {
        String pid = positivePid(input.pid());
        long start = positiveStart(input.expectedProcessStartEpochMs());
        requireConfirmation(input.confirm());
        String host = boundedText(input.probeHost(), DEFAULT_PROBE_HOST, "probeHost");
        int port = boundedPort(input.probePort());
        String include = optionalText(input.include(), "include");
        String exclude = optionalText(input.exclude(), "exclude");
        return new AttachRequest(pid, start, true, host, port, include, exclude);
    }

    private static DeactivateRequest deactivate(JvmLifecycleInput input) {
        String pid = positivePid(input.pid());
        long start = positiveStart(input.expectedProcessStartEpochMs());
        requireConfirmation(input.confirm());
        rejectAttachOnlyFields(input);
        return new DeactivateRequest(pid, start, true);
    }

    private static boolean hasMutationFields(JvmLifecycleInput input) {
        return input.pid() != null || input.expectedProcessStartEpochMs() != null
                || input.confirm() != null || input.probeHost() != null
                || input.probePort() != null || input.include() != null || input.exclude() != null;
    }

    private static void rejectAttachOnlyFields(JvmLifecycleInput input) {
        if (input.probeHost() != null || input.probePort() != null
                || input.include() != null || input.exclude() != null) {
            throw new IllegalArgumentException("deactivate input contains attach-only fields");
        }
    }

    private static String positivePid(String value) {
        if (value == null || !value.matches("[1-9][0-9]*")) {
            throw new IllegalArgumentException("pid must be a positive numeric string");
        }
        return value;
    }

    private static long positiveStart(Long value) {
        if (value == null || value <= 0L) {
            throw new IllegalArgumentException("expectedProcessStartEpochMs must be positive");
        }
        return value;
    }

    private static void requireConfirmation(Boolean value) {
        if (!Boolean.TRUE.equals(value)) {
            throw new IllegalArgumentException("confirm must be true");
        }
    }

    private static String boundedText(String value, String defaultValue, String field) {
        String normalized = value == null ? defaultValue : value.trim();
        if (normalized.isEmpty() || normalized.length() > 255) {
            throw new IllegalArgumentException(field + " is outside the supported bounds");
        }
        return normalized;
    }

    private static String optionalText(String value, String field) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException(field + " is outside the supported bounds");
        }
        return normalized;
    }

    private static int boundedPort(Integer value) {
        int port = value == null ? DEFAULT_PROBE_PORT : value;
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("probePort is outside the supported range");
        }
        return port;
    }
}
