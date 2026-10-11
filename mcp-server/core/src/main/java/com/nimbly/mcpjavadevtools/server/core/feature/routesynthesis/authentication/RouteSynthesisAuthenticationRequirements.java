package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.authentication;

import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.authentication.RouteSynthesisAuthenticationMetadata;
import com.nimbly.mcpjavadevtools.server.core.operation.execution.OperationExecutionContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads bounded source/OpenAPI authentication hints without discovering credentials. */
class RouteSynthesisAuthenticationRequirements {
    private static final int MAX_HINT_BYTES = 512 * 1024;
    private static final List<String> OPENAPI_FILES = List.of(
            "docs/openapi/openapi.yaml", "docs/openapi/openapi.yml", "openapi.yaml", "openapi.yml",
            "swagger.yaml", "swagger.yml");
    private static final Pattern SOURCE_SECURITY = Pattern.compile(
            "@(?:[\\w]+\\.)*(PreAuthorize|Secured|RolesAllowed|SecurityRequirement)\\b");
    private static final Pattern GLOBAL_SECURITY = Pattern.compile("(?m)^security\\s*:\\s*"
            + "(?:#.*)?$");
    private static final Pattern BEARER = Pattern.compile("(?i)scheme\\s*:\\s*['\"]?bearer\\b");
    private static final Pattern BASIC = Pattern.compile("(?i)scheme\\s*:\\s*['\"]?basic\\b");
    private static final Pattern COOKIE = Pattern.compile("(?is)type\\s*:\\s*apiKey.{0,220}in\\s*:\\s*cookie\\b");

    RouteSynthesisAuthenticationMetadata resolve(Path projectRoot, Path controllerFile, String endpointPath) {
        try {
            RouteSynthesisAuthenticationMetadata openapi = openApi(projectRoot, endpointPath);
            if (openapi != null) {
                return openapi;
            }
            if (controllerFile != null && Files.isRegularFile(controllerFile)
                    && SOURCE_SECURITY.matcher(readText(controllerFile)).find()) {
                return required("bearer", "source.security_annotations");
            }
            return new RouteSynthesisAuthenticationMetadata(
                    "not_required", "none", List.of(), List.of(), "none");
        } catch (IOException exception) {
            // Existing but unreadable/oversized hints must not yield an anonymous ready recipe.
            return required("bearer", "auth.requirements_unavailable");
        }
    }

    private RouteSynthesisAuthenticationMetadata openApi(Path projectRoot, String endpointPath) throws IOException {
        if (projectRoot == null) {
            return null;
        }
        Path root = projectRoot.toAbsolutePath().normalize();
        // Match the retained first-existing-file precedence.
        Path candidate = OPENAPI_FILES.stream().map(root::resolve)
                .filter(Files::isRegularFile).findFirst().orElse(null);
        if (candidate == null) {
            return null;
        }
        if (!candidate.toRealPath().startsWith(root)) {
            throw new IOException("OpenAPI hint is outside the project");
        }
        String text = readText(candidate);
        if (GLOBAL_SECURITY.matcher(text).find() || endpointSecurity(text, endpointPath)) {
            return required(strategy(text), "openapi.security");
        }
        return null;
    }

    private boolean endpointSecurity(String text, String endpointPath) {
        if (endpointPath == null) {
            return false;
        }
        Pattern path = Pattern.compile("(?m)^ {2}" + Pattern.quote(endpointPath) + ":\\s*$");
        Matcher start = path.matcher(text);
        if (!start.find()) {
            return false;
        }
        String block = text.substring(start.end());
        Matcher next = Pattern.compile("(?m)^ {2}/[^:]+:\\s*$").matcher(block);
        if (next.find()) {
            block = block.substring(0, next.start());
        }
        return Pattern.compile("(?m)^ {4,8}security\\s*:\\s*$").matcher(block).find();
    }

    private String strategy(String text) {
        if (BEARER.matcher(text).find()) {
            return "bearer";
        }
        if (BASIC.matcher(text).find()) {
            return "basic";
        }
        return COOKIE.matcher(text).find() ? "cookie" : "bearer";
    }

    private RouteSynthesisAuthenticationMetadata required(String strategy, String source) {
        List<String> missing = "basic".equals(strategy)
                ? List.of("authUsername", "authPassword") : List.of("authToken");
        return new RouteSynthesisAuthenticationMetadata("needs_user_input", strategy, missing, List.of(), source);
    }

    private String readText(Path file) throws IOException {
        checkpoint();
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(MAX_HINT_BYTES + 1);
            if (bytes.length > MAX_HINT_BYTES) {
                throw new IOException("Authentication hint exceeds the read ceiling");
            }
            checkpoint();
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private void checkpoint() {
        if (Thread.currentThread().isInterrupted() || OperationExecutionContext.current().cancellationRequested()) {
            throw new java.util.concurrent.CancellationException("Authentication hint resolution was cancelled");
        }
    }
}
