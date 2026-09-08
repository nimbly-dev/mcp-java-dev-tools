package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.project;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactOperationException;
import java.util.ArrayList;
import java.util.List;

/** Released validation for Project Artifact scripts, prerequisites, and external systems. */
final class ProjectWorkspaceSectionsContract {

    void validate(JsonNode workspace) {
        validateScripts(workspace.get("scripts"));
        validatePrerequisites(workspace.get("runPrerequisites"));
        validateExternalSystems(workspace.get("externalSystems"));
    }

    private void validateScripts(JsonNode scripts) {
        if (scripts == null || !scripts.isArray()) {
            return;
        }
        for (int index = 0; index < scripts.size(); index++) {
            JsonNode script = scripts.get(index);
            String field = "workspaces[].scripts[" + index + "]";
            requireObject(script, "project_artifact_invalid", field + " must be object");
            requiredText(script, "name", "project_artifact_invalid", field + ".name is required");
            requiredText(script, "command", "project_artifact_invalid", field + ".command is required");
            String phase = text(script, "phase");
            if (phase != null && !List.of(
                    "preRuntime", "postRuntime", "postHealthcheck", "prePlan").contains(phase)) {
                invalid("project_artifact_invalid", field + ".phase is invalid");
            }
            validateCommandPaths(script, field);
        }
    }

    private void validatePrerequisites(JsonNode prerequisites) {
        if (prerequisites == null || !prerequisites.isArray()) {
            return;
        }
        for (int index = 0; index < prerequisites.size(); index++) {
            validatePrerequisite(prerequisites.get(index), index);
        }
        List<Integer> orders = new ArrayList<>();
        prerequisites.forEach(entry -> orders.add(entry.path("order").asInt(0)));
        orders.sort(Integer::compareTo);
        for (int index = 0; index < orders.size(); index++) {
            if (orders.get(index) != index + 1) {
                invalid("project_artifact_invalid",
                        "workspaces[].runPrerequisites[].order must be sequential from 1..N");
            }
        }
    }

    private void validatePrerequisite(JsonNode prerequisite, int index) {
        String field = "workspaces[].runPrerequisites[" + index + "]";
        requireObject(prerequisite, "project_artifact_invalid", field + " must be object");
        if (!positiveInteger(prerequisite.get("order"))) {
            invalid("project_artifact_invalid", field + ".order must be a positive integer");
        }
        requiredText(prerequisite, "id", "project_artifact_invalid", field + ".id is required");
        String type = requiredText(
                prerequisite, "type", "project_artifact_invalid", field + ".type must be assert|script");
        if (type == null || !List.of("assert", "script").contains(type)) {
            invalid("project_artifact_invalid", field + ".type must be assert|script");
        }
        String onFail = requiredText(prerequisite, "onFail", "project_artifact_invalid",
                field + ".onFail must be block|skip_remaining");
        if (onFail == null || !List.of("block", "skip_remaining").contains(onFail)) {
            invalid("project_artifact_invalid", field + ".onFail must be block|skip_remaining");
        }
        if ("assert".equals(type)) {
            validateAssertion(prerequisite.get("assert"), field + ".assert");
        } else {
            validatePrerequisiteScript(prerequisite.get("script"), field + ".script");
        }
    }

    private void validateAssertion(JsonNode assertion, String field) {
        requireObject(assertion, "project_artifact_invalid", field + " is required for type=assert");
        String kind = text(assertion, "kind");
        if (kind == null || !List.of("env_exists", "context_exists", "file_exists", "port_reachable",
                "url_reachable", "command_available").contains(kind)) {
            invalid("project_artifact_invalid", field + ".kind is invalid");
        }
        if (List.of("env_exists", "context_exists").contains(kind)) {
            requiredText(assertion, "key", "project_artifact_invalid", field + ".key is required");
        } else if ("file_exists".equals(kind)) {
            requiredText(assertion, "path", "project_artifact_invalid", field + ".path is required");
        } else if ("port_reachable".equals(kind)) {
            requiredText(assertion, "host", "project_artifact_invalid", field + ".host is required");
            if (!positivePort(assertion.get("port"))) {
                invalid("project_artifact_invalid", field + ".port is invalid");
            }
        } else if ("url_reachable".equals(kind)) {
            requiredText(assertion, "url", "project_artifact_invalid", field + ".url is required");
        } else if ("command_available".equals(kind)) {
            requiredText(assertion, "name", "project_artifact_invalid", field + ".name is required");
        }
    }

    private void validatePrerequisiteScript(JsonNode script, String field) {
        requireObject(script, "project_artifact_invalid", field + " is required for type=script");
        String command = text(script, "command");
        if (command == null || !List.of("python", "node", "sh", "ps").contains(command)) {
            invalid("project_artifact_invalid", field + ".command must be python|node|sh|ps");
        }
        String scriptPath = requiredText(
                script, "scriptPath", "project_artifact_invalid", field + ".scriptPath is required");
        rejectAbsolute(scriptPath, field + ".scriptPath");
        rejectAbsolute(text(script, "cwd"), field + ".cwd");
        validateArguments(script.path("args"), field);
    }

    private void validateExternalSystems(JsonNode systems) {
        if (systems == null || !systems.isArray()) {
            return;
        }
        for (int index = 0; index < systems.size(); index++) {
            JsonNode system = systems.get(index);
            String field = "workspaces[].externalSystems[" + index + "]";
            requireObject(system, "external_system_invalid", field + " must be object");
            requiredText(system, "name", "external_system_invalid", field + ".name is required");
            requiredText(system, "kind", "external_system_invalid", field + ".kind is required");
            requiredText(system, "host", "external_system_invalid", field + ".host is required");
            if (!positivePort(system.get("port"))) {
                invalid("external_system_invalid", field + ".port is invalid");
            }
            validateHealthChecks(system.get("healthChecks"));
        }
    }

    private void validateHealthChecks(JsonNode checks) {
        if (checks == null || !checks.isArray()) {
            return;
        }
        for (int index = 0; index < checks.size(); index++) {
            JsonNode check = checks.get(index);
            String field = "externalSystems[].healthChecks[" + index + "]";
            requireObject(check, "external_system_invalid", field + " must be object");
            requiredText(check, "id", "external_system_invalid", field + ".id is required");
            String type = text(check, "type");
            if (type == null || !List.of("tcp", "http").contains(type)) {
                invalid("external_system_invalid", field + ".type must be tcp|http");
            }
            if ("tcp".equals(type)) {
                requiredText(check, "target", "external_system_invalid", field + ".target is required");
            } else {
                requiredText(check, "url", "external_system_invalid", field + ".url is required");
            }
        }
    }

    private void validateCommandPaths(JsonNode command, String field) {
        rejectAbsolute(text(command, "appdir"), field + ".appdir");
        validateArguments(command.path("args"), field);
    }

    private void validateArguments(JsonNode arguments, String field) {
        if (!arguments.isArray()) {
            return;
        }
        for (int index = 0; index < arguments.size(); index++) {
            if (arguments.get(index).isTextual()) {
                rejectAbsolute(arguments.get(index).asText().trim(), field + ".args[" + index + "]");
            }
        }
    }

    private String requiredText(JsonNode value, String name, String reasonCode, String message) {
        String result = text(value, name);
        if (result == null) {
            invalid(reasonCode, message);
        }
        return result;
    }

    private String text(JsonNode value, String name) {
        JsonNode field = value == null ? null : value.get(name);
        return field != null && field.isTextual() && !field.asText().trim().isEmpty()
                ? field.asText().trim() : null;
    }

    private boolean positiveInteger(JsonNode value) {
        return value != null && value.isIntegralNumber() && value.canConvertToInt() && value.asInt() > 0;
    }

    private boolean positivePort(JsonNode value) {
        return positiveInteger(value) && value.asInt() <= 65_535;
    }

    private void rejectAbsolute(String value, String field) {
        if (value != null && (value.matches("^[A-Za-z]:[\\\\/].*") || value.startsWith("\\\\")
                || value.startsWith("/") || value.startsWith("~/") || value.startsWith("~\\"))) {
            invalid("project_artifact_invalid", field + " must be relative/replayable");
        }
    }

    private void requireObject(JsonNode value, String reasonCode, String message) {
        if (value == null || !value.isObject()) {
            invalid(reasonCode, message);
        }
    }

    private void invalid(String reasonCode, String message) {
        throw new ArtifactOperationException(reasonCode, message);
    }
}
