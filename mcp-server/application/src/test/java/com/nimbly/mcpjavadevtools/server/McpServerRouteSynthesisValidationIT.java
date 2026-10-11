package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Tag("route-synthesis")
@Timeout(120)
class McpServerRouteSynthesisValidationIT extends McpServerStdioSupport {
    private int nextId = 100;

    @Test
    void catalogDescribeAndMissingWorkspacePreserveCdeContract() throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (McpServerProcess server = McpServerProcess.start(jarPath(), workspace)) {
            server.roots(List.of());
            initialize(server);
            server.send(request(20, "tools/call", Map.of("name", "operation_catalog",
                    "arguments", Map.of("api", "route_synthesis"))));
            JsonNode entries = toolPayload(server.responseFor(20)).path("entries");
            assertThat(entries).extracting(entry -> entry.path("operationId").asText()).containsExactlyInAnyOrder(
                    "route_synthesis.infer_target", "route_synthesis.class_methods",
                    "route_synthesis.discover_handlers", "route_synthesis.create_recipe");
            for (String action : List.of("infer_target", "class_methods", "discover_handlers", "create_recipe")) {
                int id = nextId++;
                server.send(request(id, "tools/call", Map.of("name", "operation_describe",
                        "arguments", Map.of("operationId", "route_synthesis." + action))));
                JsonNode description = toolPayload(server.responseFor(id));
                assertThat(description.path("status").asText()).isEqualTo("succeeded");
                JsonNode operation = description.path("operation");
                assertThat(operation.path("safety").path("confirmationRequired").asBoolean())
                        .isEqualTo("create_recipe".equals(action));
                assertThat(operation.path("inputSchema").path("required"))
                        .contains(JSON.valueToTree("projectRootAbs"));
                assertThat(operation.path("resultSchema").path("required"))
                        .contains(JSON.valueToTree("resultType"), JSON.valueToTree("status"));
                JsonNode result = call(server, action, recipe(workspace, "example.Controller")).path("result");
                assertThat(result.path("reasonCode").asText()).isEqualTo("workspace_context_missing");
            }
        }
    }

    @Test
    void invalidInputsAndMissingTargetsFailDeterministically() throws Exception {
        Path workspace = JavaItScenario.workspace();
        Path source = copyRouteFixtureSource(workspace);
        try (McpServerProcess server = JvmProbeScenario.startServer(workspace)) {
            initialize(server);
            assertInvalidSelectors(server, source);
            Map<String, Object> input = recipe(source, "absent.Controller");
            for (Map.Entry<String, String> expected : Map.of("infer_target", "target_not_found",
                    "class_methods", "class_not_found", "discover_handlers", "target_type_not_found",
                    "create_recipe", "target_candidate_missing").entrySet()) {
                assertThat(call(server, expected.getKey(), input).path("result").path("reasonCode").asText())
                        .isEqualTo(expected.getValue());
            }
            for (Map<String, Object> invalid : List.of(Map.<String, Object>of("lineHint", 0),
                    Map.<String, Object>of("discoveryPreference", "guess"), Map.<String, Object>of("intentMode", "guess"))) {
                Map<String, Object> variant = new LinkedHashMap<>(input);
                variant.putAll(invalid);
                assertThat(call(server, "create_recipe", variant).path("status").asText()).isEqualTo("invalid_input");
            }
            input.putAll(Map.of("intentMode", "line_probe"));
            assertThat(call(server, "create_recipe", input).path("result").path("reasonCode").asText())
                    .isEqualTo("line_target_required_for_probe_mode");
            input.putAll(Map.of("lineHint", 1, "actuationEnabled", true));
            assertThat(call(server, "create_recipe", input).path("result").path("reasonCode").asText())
                    .isEqualTo("actuation_input_required");
        }
    }

    private void assertInvalidSelectors(McpServerProcess server, Path source) throws Exception {
        for (String action : List.of("infer_target", "class_methods", "discover_handlers", "create_recipe")) {
            Map<String, Object> input = recipe(source, "absent.Controller");
            Map<String, Object> missing = new LinkedHashMap<>(input);
            missing.remove("projectRootAbs");
            assertThat(call(server, action, missing).path("status").asText()).isEqualTo("invalid_input");
            input.put("unknownArgument", "validation-secret-sentinel");
            JsonNode invalid = call(server, action, input);
            assertThat(invalid.path("status").asText()).isEqualTo("invalid_input");
            assertThat(invalid.toString()).doesNotContain("validation-secret-sentinel", source.toString());
            input.remove("unknownArgument");
            for (String root : List.of("../outside", source.resolve("missing").toString())) {
                input.put("projectRootAbs", root);
                assertThat(call(server, action, input).path("result").path("reasonCode").asText())
                        .isEqualTo("create_recipe".equals(action) ? "target_candidate_missing" : "project_selector_invalid");
            }
            input.put("projectRootAbs", source.toString());
            input.put("additionalSourceRoots", List.of("../outside"));
            assertThat(call(server, action, input).path("result").path("reasonCode").asText())
                    .isEqualTo("class_methods".equals(action) ? "project_selector_invalid" : "additional_source_roots_invalid");
            input.put("additionalSourceRoots", java.util.Collections.nCopies(11, source.toString()));
            assertThat(call(server, action, input).path("status").asText()).isEqualTo("invalid_input");
        }
    }

    @Test
    void authenticationTemplatesAndUnsupportedProvidersRemainSanitized() throws Exception {
        try (RouteSynthesisScenario s = RouteSynthesisScenario.start()) {
            Map<String, Object> input = s.recipeArguments(RouteSynthesisScenario.TAGS, "lockTag");
            input.putAll(Map.of("apiBasePath", "/context/", "outputTemplate", "{{auth.strategy}} {{http.path}} {{unknown}}"));
            JsonNode anonymous = s.recipe(input);
            assertThat(anonymous.path("auth").path("status").asText()).isEqualTo("not_required");
            assertThat(anonymous.path("requestCandidates").get(0).path("path").asText())
                    .isEqualTo("/context/api/v2/tenant/{tenantId}/tags/{tagName}/lock");
            assertThat(anonymous.path("rendered").asText())
                    .isEqualTo("none /context/api/v2/tenant/{tenantId}/tags/{tagName}/lock {{unknown}}");
            input.put("authUsername", "route-user-sentinel");
            JsonNode missingResult = s.result("create_recipe", input);
            assertThat(missingResult.path("resultType").asText()).isEqualTo("report");
            assertThat(missingResult.path("status").asText()).isEqualTo("execution_input_required");
            assertThat(missingResult.path("reasonCode").asText()).isEqualTo("auth_input_required");
            JsonNode missing = missingResult.path("actionResult");
            assertThat(missing.path("auth").path("status").asText()).isEqualTo("needs_user_input");
            assertThat(missing.path("executionReadiness").asText()).isEqualTo("needs_user_input");
            assertThat(missing.path("auth").path("missing")).containsExactly(JSON.valueToTree("authPassword"));
            input.put("authPassword", "route-password-sentinel");
            JsonNode basic = s.recipe(input);
            assertThat(basic.path("auth").path("strategy").asText()).isEqualTo("basic");
            assertThat(basic.toString()).doesNotContain("route-user-sentinel", "route-password-sentinel");
            input.remove("authUsername");
            assertThat(s.result("create_recipe", input).path("actionResult").path("auth").path("missing"))
                    .containsExactly(JSON.valueToTree("authUsername"));
            input.put("authToken", "bearer-precedence-sentinel");
            assertThat(s.recipe(input).path("auth").path("strategy").asText()).isEqualTo("bearer");
            assertUnsupportedProviders(s);
            input.put("methodHint", "missing");
            assertThat(s.result("create_recipe", input).path("reasonCode").asText()).isEqualTo("request_candidate_missing");
        }
    }

    private void assertUnsupportedProviders(RouteSynthesisScenario s) throws Exception {
        for (String framework : List.of("jaxrs", "grpc")) {
            String fqcn = "placeholder." + framework + ".Endpoint";
            Path file = s.sources.resolve(framework + "/Endpoint.java");
            Files.createDirectories(file.getParent());
            String annotation = "jaxrs".equals(framework) ? "@jakarta.ws.rs.Path(\"/placeholder\")\n" : "";
            String superclass = "grpc".equals(framework) ? " extends ExampleGrpc.ExampleImplBase" : "";
            Files.writeString(file, "package placeholder." + framework + ";\n" + annotation
                    + "public class Endpoint" + superclass + " {\n public String handle() {\n return \"placeholder\";\n }\n}\n");
            JsonNode blocked = s.result("create_recipe", s.recipeArguments(fqcn, "handle"));
            assertThat(blocked.path("reasonCode").asText()).isEqualTo("mapper_plugin_unavailable");
            assertThat(blocked.path("resultType").asText()).isEqualTo("report");
            assertThat(blocked.path("actionResult").isNull()).isTrue();
        }
    }

    @Test
    void sourceAndOpenApiAuthenticationRequirementsBlockAnonymousRecipes() throws Exception {
        try (RouteSynthesisScenario s = RouteSynthesisScenario.start()) {
            Path controller = s.sources.resolve("ProtectedController.java");
            for (String annotation : List.of("PreAuthorize(\"isAuthenticated()\")", "Secured(\"ROLE_USER\")",
                    "RolesAllowed(\"USER\")", "SecurityRequirement(name=\"bearerAuth\")")) {
                Files.writeString(controller, "package example;\n@RestController\npublic class ProtectedController {\n"
                        + "@" + annotation + "\n@GetMapping(\"/protected\")\npublic String handle() {\n"
                        + "return \"protected\";\n}\n}\n");
                Map<String, Object> input = s.recipeArguments("example.ProtectedController", "handle");
                assertRequiredAuthentication(s, input, "bearer", "source.security_annotations");
            }
            Files.delete(controller);
            assertRuntimeSourceAuthentication(s);
            Path openapi = s.sources.resolve("openapi.yaml");
            Files.writeString(openapi, "openapi: 3.0.3\nsecurity:\n  - bearerAuth: []\ncomponents:\n"
                    + "  securitySchemes:\n    bearerAuth:\n      type: http\n      scheme: bearer\n");
            Map<String, Object> input = s.recipeArguments(RouteSynthesisScenario.TAGS, "lockTag");
            assertRequiredAuthentication(s, input, "bearer", "openapi.security");
            input.put("discoveryPreference", "runtime_only");
            input.put("mappingsBaseUrl", "http://127.0.0.1:" + s.runtime.target.port());
            assertRequiredAuthentication(s, input, "bearer", "openapi.security");
            JavaItScenario.observe("route_synthesis.create_recipe", "ASSERTED", "source_openapi_auth_requirements");
        }
    }

    private void assertRuntimeSourceAuthentication(RouteSynthesisScenario s) throws Exception {
        Path source = s.sources.resolve(RouteSynthesisScenario.TAGS.replace('.', '/') + ".java");
        String original = Files.readString(source);
        Files.writeString(source, original.replace("public class TagLifecycleController",
                "@PreAuthorize(\"isAuthenticated()\")\npublic class TagLifecycleController"));
        Map<String, Object> input = s.recipeArguments(RouteSynthesisScenario.TAGS, "lockTag");
        input.putAll(Map.of("discoveryPreference", "runtime_only", "mappingsBaseUrl",
                "http://127.0.0.1:" + s.runtime.target.port()));
        assertRequiredAuthentication(s, input, "bearer", "source.security_annotations");
        Files.writeString(source, original);
    }

    private void assertRequiredAuthentication(RouteSynthesisScenario s, Map<String, Object> input,
            String strategy, String source) throws Exception {
        JsonNode result = s.result("create_recipe", input);
        assertThat(result.path("resultType").asText()).isEqualTo("report");
        assertThat(result.path("status").asText()).isEqualTo("execution_input_required");
        assertThat(result.path("reasonCode").asText()).isEqualTo("auth_input_required");
        JsonNode recipe = result.path("actionResult");
        assertThat(recipe.path("executionReadiness").asText()).isEqualTo("needs_user_input");
        assertThat(recipe.path("auth").path("status").asText()).isEqualTo("needs_user_input");
        assertThat(recipe.path("auth").path("strategy").asText()).isEqualTo(strategy);
        assertThat(recipe.path("auth").path("source").asText()).isEqualTo(source);
        assertThat(recipe.path("auth").path("missing")).contains(JSON.valueToTree("authToken"));
        input.put("authToken", "alice-token");
        JsonNode ready = s.recipe(input);
        assertThat(ready.path("auth").path("status").asText()).isEqualTo("auto_resolved");
        assertThat(ready.path("executionReadiness").asText()).isEqualTo("ready");
        assertThat(ready.toString()).doesNotContain("alice-token");
        input.remove("authToken");
    }

    @Test
    void interfaceMappedRuntimeRecipeRetainsControllerAuthenticationRequirements() throws Exception {
        try (RouteSynthesisScenario s = RouteSynthesisScenario.start()) {
            Path source = s.sources.resolve(JvmProbeScenario.CONTROLLER.replace('.', '/') + ".java");
            String original = Files.readString(source);
            Files.writeString(source, original.replace("public class PostController",
                    "@PreAuthorize(\"isAuthenticated()\")\npublic class PostController"));
            JsonNode staticDiscovery = s.result("discover_handlers", s.arguments(JvmProbeScenario.CONTROLLER));
            assertThat(staticDiscovery.path("reasonCode").asText()).isEqualTo("mapper_plugin_unavailable");
            for (String mode : List.of("runtime_only", "runtime_first")) {
                Map<String, Object> input = s.recipeArguments(JvmProbeScenario.CONTROLLER, "getPost");
                input.putAll(Map.of("discoveryPreference", mode, "mappingsBaseUrl",
                        "http://127.0.0.1:" + s.runtime.target.port()));
                assertRequiredAuthentication(s, input, "bearer", "source.security_annotations");
                input.put("authToken", "alice-token");
                JsonNode ready = s.recipe(input);
                assertThat(ready.path("requestCandidates").get(0).path("path").asText())
                        .isEqualTo("/api/v1/posts/{postId}");
                assertThat(ready.path("evidence").toString()).contains("mapping_source=runtime_actuator");
                assertThat(ready.toString()).doesNotContain("alice-token");
            }
            JavaItScenario.observe("route_synthesis.create_recipe", "ASSERTED", "interface_mapped_controller_auth_required");
        }
    }

    @Test
    void staticHttpVerbsAndAdditionalSourceRootsPreserveRequestTemplates() throws Exception {
        try (RouteSynthesisScenario s = RouteSynthesisScenario.start()) {
            Path extra = Files.createDirectories(s.runtime.workspace.resolve("verb-source"));
            StringBuilder source = new StringBuilder("package example;\n@RestController\n@RequestMapping(\"/verbs\")\n"
                    + "public class Verbs {\n");
            for (String verb : List.of("Get", "Post", "Put", "Patch", "Delete")) {
                source.append("@" + verb + "Mapping(\"/" + verb + "\")\npublic String " + verb + "() {\n")
                        .append("return \"" + verb + "\";\n}\n");
            }
            source.append("@RequestMapping(path=\"/generic\", method=RequestMethod.POST)\n")
                    .append("public String generic() {\nreturn \"generic\";\n}\n}\n");
            Files.writeString(extra.resolve("Verbs.java"), source);
            for (String verb : List.of("Get", "Post", "Put", "Patch", "Delete", "generic")) {
                Map<String, Object> input = s.recipeArguments("example.Verbs", verb);
                input.put("additionalSourceRoots", List.of(extra.toString(), extra.toString()));
                JsonNode recipe = s.recipe(input);
                JsonNode candidate = recipe.path("requestCandidates").get(0);
                String method = "generic".equals(verb) ? "POST" : verb.toUpperCase(java.util.Locale.ROOT);
                assertThat(candidate.path("method").asText()).isEqualTo(method);
                assertThat(candidate.path("path").asText()).isEqualTo("/verbs/" + verb);
                if (List.of("POST", "PUT", "PATCH").contains(method)) {
                    assertThat(candidate.path("bodyTemplate").asText()).isEqualTo("{}");
                } else {
                    assertThat(candidate.path("bodyTemplate").isNull()).isTrue();
                }
                assertThat(recipe.path("runtimeCapture").path("status").asText()).isEqualTo("unavailable");
                assertThat(recipe.path("executionPlan").path("probeCallPlan").path("total").asInt()).isZero();
            }
            JavaItScenario.observe("route_synthesis.create_recipe", "ASSERTED", "six_static_http_mapping_variants_body_defaults");
        }
    }

    @Test
    void configuredExternalSynthesizerReturnsBoundedBlocker() throws Exception {
        Path workspace = JavaItScenario.workspace();
        Path source = copyRouteFixtureSource(workspace);
        String secretPath = workspace.resolve("private-plugin-sentinel.jar").toString();
        try (McpServerProcess server = JvmProbeScenario.startServer(workspace,
                "--mcpjvm.route-synthesis.synthesizer.external-modules=" + secretPath)) {
            initialize(server);
            JsonNode result = call(server, "create_recipe", recipe(source, "example.Controller")).path("result");
            assertThat(result.path("reasonCode").asText()).isEqualTo("synthesizer_not_installed");
            assertThat(result.path("failedStep").asText()).isEqualTo("plugin_bootstrap");
            assertThat(result.toString()).doesNotContain(secretPath, "private-plugin-sentinel");
            assertThat(result.path("evidence").toString()).contains("configuredModuleCount=1");
        }
    }

    private Map<String, Object> recipe(Path source, String classHint) {
        return new LinkedHashMap<>(Map.of("projectRootAbs", source.toString(), "classHint", classHint,
                "methodHint", "handle", "intentMode", "regression"));
    }

    private JsonNode call(McpServerProcess server, String action, Map<String, Object> input) throws Exception {
        Map<String, Object> arguments = new LinkedHashMap<>(input);
        if (!"create_recipe".equals(action)) {
            arguments.remove("intentMode");
        }
        if (List.of("class_methods", "discover_handlers").contains(action)) {
            arguments.remove("methodHint");
        }
        int id = nextId++;
        server.send(executeRequest(id, "route_synthesis." + action, arguments, "create_recipe".equals(action)));
        JsonNode payload = toolPayload(server.responseFor(id, Duration.ofSeconds(30)));
        JavaItScenario.observe("route_synthesis." + action, payload.path("status").asText(),
                payload.path("result").path("reasonCode").asText());
        return payload;
    }
}
