package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.project;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactOperationException;
import java.util.LinkedHashSet;
import java.util.Set;

/** Released Project Artifact runtime-context validation contract. */
final class ProjectRuntimeContextContract {

    Set<String> validate(JsonNode workspace) {
        JsonNode contexts = workspace.get("runtimeContexts");
        if (contexts == null || !contexts.isArray()) {
            return Set.of();
        }
        Set<String> names = new LinkedHashSet<>();
        for (int index = 0; index < contexts.size(); index++) {
            validateContext(contexts.get(index), index, names);
        }
        return Set.copyOf(names);
    }

    private void validateContext(JsonNode context, int index, Set<String> names) {
        requireObject(context, "runtimeContexts[" + index + "] must be object");
        String name = text(context, "name");
        if (name == null) {
            invalid("runtimeContexts[" + index + "].name is required");
        }
        String mode = text(context, "mode");
        if (mode == null || !Set.of("terminal", "docker").contains(mode)) {
            invalid("runtimeContexts[" + index + "].mode must be terminal|docker");
        }
        if ("docker".equals(mode) && text(context, "composeFile") == null) {
            invalid("runtimeContexts[" + index + "].composeFile is required for docker mode");
        }
        if (context.has("startup")) {
            invalid("runtimeContexts[" + index + "].startup is unsupported; use startups[]");
        }
        JsonNode startups = context.path("startups");
        int startupCount = validateStartups(startups, index);
        boolean autoStart = !context.path("autoStart").isBoolean()
                || context.path("autoStart").asBoolean();
        if ("terminal".equals(mode) && autoStart && startupCount == 0) {
            invalid("runtimeContexts[" + index + "].startups[] is required for terminal autoStart");
        }
        validateSidecar(context.get("sidecarLifecycle"), mode, autoStart, startups, startupCount, index);
        names.add(name);
    }

    private int validateStartups(JsonNode startups, int contextIndex) {
        if (!startups.isArray()) {
            return 0;
        }
        int valid = 0;
        for (int index = 0; index < startups.size(); index++) {
            validateCommand(startups.get(index),
                    "runtimeContexts[" + contextIndex + "].startups[" + index + "]");
            valid++;
        }
        return valid;
    }

    private void validateCommand(JsonNode command, String field) {
        requireObject(command, field + " must be object");
        if (text(command, "name") == null) {
            invalid(field + ".name is required");
        }
        if (text(command, "command") == null) {
            invalid(field + ".command is required");
        }
        rejectAbsolute(text(command, "appdir"), field + ".appdir");
        JsonNode arguments = command.path("args");
        if (arguments.isArray()) {
            for (int index = 0; index < arguments.size(); index++) {
                if (arguments.get(index).isTextual()) {
                    rejectAbsolute(arguments.get(index).asText().trim(),
                            field + ".args[" + index + "]");
                }
            }
        }
    }

    private void validateSidecar(
            JsonNode sidecar, String mode, boolean autoStart, JsonNode startups,
            int startupCount, int contextIndex) {
        if (sidecar == null) {
            return;
        }
        String field = "runtimeContexts[" + contextIndex + "].sidecarLifecycle";
        if (!sidecar.isObject()) {
            sidecarInvalid(field + " must be object");
        }
        String target = text(sidecar, "targetStartupName");
        if (!"dynamic_attach_local".equals(text(sidecar, "activation"))
                || target == null || text(sidecar, "probeId") == null
                || !"terminal".equals(mode) || !autoStart || startupCount != 1) {
            sidecarInvalid(field + " is not a valid dynamic_attach_local policy");
        }
        JsonNode startup = startups.get(0);
        if (!target.equals(text(startup, "name"))) {
            sidecarInvalid(field + ".targetStartupName must match the single startups[].name");
        }
        if (sidecar.has("verifyProbeAfterAttach")
                && !sidecar.path("verifyProbeAfterAttach").asBoolean(false)) {
            sidecarInvalid(field + ".verifyProbeAfterAttach must be true");
        }
        if (sidecar.has("deactivateOnFinish")
                && !sidecar.path("deactivateOnFinish").asBoolean(false)) {
            sidecarInvalid(field + ".deactivateOnFinish is implied and cannot be false");
        }
        validateDynamicAttach(startup, field + ".targetStartup");
    }

    private void validateDynamicAttach(JsonNode startup, String field) {
        String command = text(startup, "command");
        String normalized = command == null ? "" : command.replace('\\', '/');
        String launcher = normalized.substring(normalized.lastIndexOf('/') + 1);
        if (!Set.of("java", "java.exe").contains(launcher.toLowerCase(java.util.Locale.ROOT))) {
            sidecarInvalid(field + ".command must invoke java or java.exe directly");
        }
        JsonNode arguments = startup.path("args");
        int jarIndex = -1;
        int jarCount = 0;
        if (arguments.isArray()) {
            for (int index = 0; index < arguments.size(); index++) {
                String argument = arguments.path(index).asText("");
                if ("-jar".equals(argument)) {
                    jarIndex = index;
                    jarCount++;
                }
                if (argument.startsWith("-javaagent:")) {
                    sidecarInvalid(field + ".args must not contain -javaagent for dynamic_attach_local");
                }
            }
        }
        if (jarCount != 1) {
            sidecarInvalid(field + ".args must contain exactly one -jar option");
        }
        String jar = arguments.path(jarIndex + 1).asText("");
        if (jar.isBlank() || jar.startsWith("-") || absoluteOrTraversal(jar)) {
            sidecarInvalid(field + ".args must provide a relative JAR path after -jar");
        }
    }

    private void rejectAbsolute(String value, String field) {
        if (value != null && absolute(value)) {
            invalid(field + " must be relative/replayable (absolute paths are not allowed)");
        }
    }

    private boolean absoluteOrTraversal(String value) {
        return absolute(value) || java.util.Arrays.stream(value.split("[\\\\/]"))
                .anyMatch(".."::equals);
    }

    private boolean absolute(String value) {
        String text = value.trim();
        return text.matches("^[A-Za-z]:[\\\\/].*") || text.startsWith("\\\\")
                || text.startsWith("/") || text.startsWith("~/") || text.startsWith("~\\");
    }

    private String text(JsonNode object, String field) {
        JsonNode value = object == null ? null : object.get(field);
        if (value == null || !value.isTextual() || value.asText().trim().isEmpty()) {
            return null;
        }
        return value.asText().trim();
    }

    private void requireObject(JsonNode value, String message) {
        if (value == null || !value.isObject()) {
            invalid(message);
        }
    }

    private void invalid(String message) {
        throw new ArtifactOperationException("runtime_context_unknown", message);
    }

    private void sidecarInvalid(String message) {
        throw new ArtifactOperationException("sidecar_lifecycle_invalid", message);
    }
}
