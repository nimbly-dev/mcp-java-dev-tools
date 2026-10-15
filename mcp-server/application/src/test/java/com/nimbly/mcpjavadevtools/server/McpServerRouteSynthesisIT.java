package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Tag("route-synthesis")
@Timeout(180)
class McpServerRouteSynthesisIT extends McpServerStdioSupport {

    @Test
    @Tag("route-smoke")
    @Tag("skill-workflows")
    void discoveryRecipeTransportEffectAndCapture() throws Exception {
        try (RouteSynthesisScenario s = RouteSynthesisScenario.start()) {
            s.runtime.attach();
            int line = RouteSynthesisScenario.line(RouteSynthesisScenario.TAGS, "createTag",
                    "return tagLifecycleService.createTag(tenantId, body);");
            assertDiscovery(s, line);
            Map<String, Object> arguments = s.recipeArguments(RouteSynthesisScenario.TAGS, "createTag");
            arguments.putAll(Map.of("intentMode", "line_probe", "lineHint", line,
                    "authToken", "alice-token", "outputTemplate", "{{http.method}} {{http.path}} {{probe.hit}}"));
            assertThat(s.runtime.execute("route_synthesis.create_recipe", arguments, false)
                    .path("status").asText()).isEqualTo("confirmation_required");
            JsonNode recipe = s.recipe(arguments);
            assertThat(recipe.path("auth").path("strategy").asText()).isEqualTo("bearer");
            assertThat(recipe.path("rendered").asText()).isEqualTo(
                    "POST /api/v2/tenant/{tenantId}/tags Not executed (recipe only)");
            String key = RouteSynthesisScenario.TAGS + "#createTag:" + line;
            executeGeneratedRequest(s, recipe, key);
            JsonNode enriched = s.recipe(arguments);
            assertThat(enriched.path("runtimeCapture").path("status").asText()).isEqualTo("available");
            assertThat(enriched.path("runtimeCapture").path("captureId").asText())
                    .isEqualTo(s.runtime.status(key).path("capturePreview").path("captureId").asText()).isNotEmpty();
            assertThat(enriched.toString()).doesNotContain("alice-token");
            s.runtime.deactivate();
        }
    }

    private void assertDiscovery(RouteSynthesisScenario s, int line) throws Exception {
        Map<String, Object> arguments = s.arguments(RouteSynthesisScenario.TAGS);
        JsonNode methods = s.result("class_methods", arguments).path("actionResult").path("target");
        assertThat(methods.path("fqcn").asText()).isEqualTo(RouteSynthesisScenario.TAGS);
        assertThat(methods.path("methods")).anySatisfy(method -> {
            assertThat(method.path("methodName").asText()).isEqualTo("createTag");
            assertThat(method.path("firstExecutableLine").asInt()).isEqualTo(line);
            assertThat(method.path("lineSelectionStatus").asText()).isEqualTo("validated");
        });
        arguments.putAll(Map.of("methodHint", "createTag", "lineHint", line));
        JsonNode inferred = s.result("infer_target", arguments);
        assertThat(inferred.path("status").asText()).isEqualTo("ok");
        JsonNode candidates = inferred.path("actionResult").path("candidates");
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).path("line").asInt()).isEqualTo(line);
        assertThat(candidates.get(0).path("key").asText()).isEqualTo(RouteSynthesisScenario.TAGS + "#createTag");
        JsonNode discovery = s.result("discover_handlers", s.arguments(RouteSynthesisScenario.TAGS));
        assertThat(discovery.path("status").asText()).isEqualTo("ready");
        JsonNode handlers = discovery.path("actionResult").path("handlers");
        assertThat(handlers).hasSize(3);
        assertThat(handlers).anySatisfy(handler -> {
            assertThat(handler.path("methodName").asText()).isEqualTo("createTag");
            assertThat(handler.path("path").asText()).isEqualTo("/api/v2/tenant/{tenantId}/tags");
            assertThat(handler.path("strictLineKey").asText()).isEqualTo(RouteSynthesisScenario.TAGS + "#createTag:" + line);
        });
    }

    private void executeGeneratedRequest(RouteSynthesisScenario s, JsonNode recipe, String key) throws Exception {
        JsonNode candidate = recipe.path("requestCandidates").get(0);
        assertThat(candidate.path("bodyTemplate").asText()).isEqualTo("{}");
        String path = candidate.path("path").asText().replace("{tenantId}", "route-it");
        s.runtime.success("reset", Map.of("key", key));
        Map<String, Object> request = Map.of("method", candidate.path("method").asText(),
                "url", "http://127.0.0.1:" + s.runtime.target.port() + path,
                "headers", Map.of("Authorization", "Bearer alice-token", "Content-Type", "application/json"),
                "body", Map.of("name", "route-generated"));
        assertThat(s.runtime.execute("transport_execute.execute", Map.of("protocol", "http", "request", request),
                false).path("status").asText()).isEqualTo("confirmation_required");
        assertThat(s.runtime.status(key).path("hitCount").asLong()).isZero();
        JsonNode transport = s.runtime.result("transport_execute.execute",
                Map.of("protocol", "http", "request", request), true);
        assertThat(transport.path("statusCode").asInt()).as(transport.toPrettyString()).isEqualTo(200);
        JsonNode body = JSON.readTree(transport.path("bodyPreview").asText());
        assertThat(body.path("tenantId").asText()).isEqualTo("route-it");
        assertThat(body.path("operation").asText()).isEqualTo("create");
        assertThat(body.path("request").path("name").asText()).isEqualTo("route-generated");
        assertThat(s.runtime.status(key).path("hitCount").asLong()).isPositive();
        assertThat(s.runtime.status(key).path("lineValidation").asText()).isEqualTo("resolvable");
        JavaItScenario.observe("route_synthesis.create_recipe", "ASSERTED", "generated_transport_effect_strict_hit_capture");
    }

    @Test
    void overloadLineSelectsMatchingRoute() throws Exception {
        String controller = "com.example.social.post.app.controller.RouteSelectionController";
        try (RouteSynthesisScenario s = RouteSynthesisScenario.start()) {
            s.runtime.attach();
            int line = RouteSynthesisScenario.line(controller, "select", "return \"value-route:\" + value;");
            Map<String, Object> arguments = s.arguments(controller);
            arguments.put("methodHint", "select");
            assertThat(s.result("infer_target", arguments).path("reasonCode").asText()).isEqualTo("target_ambiguous");
            arguments.put("lineHint", line);
            JsonNode inferred = s.result("infer_target", arguments);
            assertThat(inferred.path("status").asText()).isEqualTo("ok");
            assertThat(inferred.path("actionResult").path("candidates")).hasSize(1);
            arguments.put("intentMode", "line_probe");
            JsonNode recipe = s.recipe(arguments);
            assertThat(recipe.path("selectedHandler").path("firstExecutableLine").asInt()).isEqualTo(line);
            assertThat(recipe.path("requestCandidates").get(0).path("path").asText())
                    .isEqualTo("/route-selection/value/{value}");
            String path = recipe.path("requestCandidates").get(0).path("path").asText().replace("{value}", "selected");
            assertThat(s.runtime.http("GET", path, null).body()).isEqualTo("value-route:selected");
            assertThat(s.runtime.status(controller + "#select:" + line).path("hitCount").asLong()).isPositive();
            arguments.put("intentMode", "regression");
            arguments.remove("lineHint");
            assertThat(s.result("create_recipe", arguments).path("reasonCode").asText()).isEqualTo("target_ambiguous");
            s.runtime.deactivate();
        }
    }

    @Test
    void probeRegistrySelectionLineValidationAndActuationPlanOptions() throws Exception {
        try (RouteSynthesisScenario s = RouteSynthesisScenario.start()) {
            s.runtime.attach();
            JsonNode registry = JSON.valueToTree(Map.of("defaultProfile", "it", "profiles", Map.of("it",
                    Map.of("probes", Map.of("owned", Map.of("baseUrl", s.runtime.baseUrl))))));
            s.runtime.result("artifact_management.probe_config.upsert", Map.of("payload", registry), true);
            Map<String, Object> arguments = s.arguments(RouteSynthesisScenario.TAGS);
            arguments.remove("probeBaseUrl");
            arguments.putAll(Map.of("classHint", "TagLifecycleController", "methodHint", "createTag"));
            JsonNode implicit = s.result("infer_target", arguments);
            assertThat(implicit.path("status").asText()).isEqualTo("ok");
            int line = implicit.path("actionResult").path("candidates").get(0).path("line").asInt();
            assertThat(line).isEqualTo(RouteSynthesisScenario.line(RouteSynthesisScenario.TAGS, "createTag",
                    "return tagLifecycleService.createTag(tenantId, body);"));
            arguments.putAll(Map.of("probeId", "owned", "probeBaseUrl", "invalid"));
            assertThat(s.result("infer_target", arguments).path("status").asText()).isEqualTo("ok");
            arguments.put("probeId", "unknown");
            assertThat(s.result("infer_target", arguments).path("reasonCode").asText()).isEqualTo("runtime_line_unresolved");
            arguments = s.recipeArguments(RouteSynthesisScenario.TAGS, "createTag");
            arguments.putAll(Map.of("intentMode", "line_probe", "lineHint", line, "actuationEnabled", true,
                    "actuationReturnBoolean", false, "actuationActuatorId", "route-plan-only"));
            JsonNode recipe = s.recipe(arguments);
            assertThat(recipe.path("executionPlan").path("probeCallPlan").path("total").asInt()).isEqualTo(4);
            assertThat(recipe.path("executionPlan").path("steps").toString())
                    .contains("returnBoolean=false", "actuatorId=route-plan-only", "cleanup");
            assertThat(s.runtime.status(RouteSynthesisScenario.TAGS + "#createTag:" + line).path("hitCount").asLong()).isZero();
            arguments.put("lineHint", line - 1);
            assertThat(s.result("create_recipe", arguments).path("reasonCode").asText()).isEqualTo("runtime_line_unresolved");
            s.runtime.deactivate();
        }
    }

    @Test
    void runtimeMappingsModesAndRecovery() throws Exception {
        try (RouteSynthesisScenario s = RouteSynthesisScenario.start()) {
            s.runtime.attach();
            for (String mode : List.of("runtime_only", "runtime_first")) {
                Map<String, Object> arguments = s.recipeArguments(JvmProbeScenario.CONTROLLER, "getPost");
                arguments.putAll(Map.of("discoveryPreference", mode, "mappingsBaseUrl",
                        "http://127.0.0.1:" + s.runtime.target.port(), "authToken", "alice-token"));
                JsonNode recipe = s.recipe(arguments);
                assertThat(recipe.path("requestCandidates").get(0).path("path").asText())
                        .isEqualTo("/api/v1/posts/{postId}");
                assertThat(recipe.path("evidence").toString()).contains("mapping_source=runtime_actuator");
                assertThat(recipe.path("executionPlan").path("probeCallPlan").path("total").asInt()).isZero();
            }
            Map<String, Object> arguments = s.recipeArguments(RouteSynthesisScenario.TAGS, "lockTag");
            arguments.put("discoveryPreference", "runtime_only");
            assertThat(s.result("create_recipe", arguments).path("reasonCode").asText())
                    .isEqualTo("runtime_mappings_input_required");
            arguments.put("mappingsBaseUrl", "http://127.0.0.1:" + freePort());
            assertThat(s.result("create_recipe", arguments).path("reasonCode").asText())
                    .isEqualTo("runtime_mappings_unreachable");
            arguments.put("discoveryPreference", "runtime_first");
            JsonNode fallback = s.recipe(arguments);
            assertThat(fallback.path("requestCandidates").get(0).path("path").asText())
                    .isEqualTo("/api/v2/tenant/{tenantId}/tags/{tagName}/lock");
            assertThat(fallback.path("evidence").toString()).contains("mappingsHost=127.0.0.1");
            arguments.put("discoveryPreference", "runtime_only");
            arguments.put("mappingsBaseUrl", "http://127.0.0.1:" + s.runtime.target.port());
            assertThat(s.recipe(arguments).path("requestCandidates").get(0).path("method").asText()).isEqualTo("POST");
            s.runtime.deactivate();
        }
    }

    @Test
    void ambiguityLimitsAdditionalRootsAndUnavailableRuntime() throws Exception {
        try (RouteSynthesisScenario s = RouteSynthesisScenario.start()) {
            Map<String, Object> arguments = s.arguments(RouteSynthesisScenario.TAGS);
            assertThat(s.result("infer_target", arguments).path("reasonCode").asText())
                    .isEqualTo("runtime_line_unresolved");
            assertThat(s.result("discover_handlers", arguments).path("status").asText()).isEqualTo("partial");
            s.runtime.attach();
            arguments.put("maxCandidates", 1);
            JsonNode ambiguous = s.result("infer_target", arguments);
            assertThat(ambiguous.path("reasonCode").asText()).isEqualTo("target_ambiguous");
            assertThat(ambiguous.path("actionResult").path("candidates")).hasSize(1);
            arguments.remove("maxCandidates");
            var extra = Files.createDirectories(s.runtime.workspace.resolve("extra"));
            Files.writeString(extra.resolve("TagLifecycleController.java"),
                    "package other;\npublic class TagLifecycleController {\n public void absent() {\n }\n}\n");
            arguments.putAll(Map.of("classHint", "TagLifecycleController", "additionalSourceRoots", List.of(extra.toString())));
            assertThat(s.result("class_methods", arguments).path("reasonCode").asText()).isEqualTo("class_ambiguous");
            arguments.put("classHint", RouteSynthesisScenario.TAGS);
            assertThat(s.result("class_methods", arguments).path("status").asText()).isEqualTo("ok");
            s.runtime.deactivate();
            assertThat(s.result("infer_target", s.arguments(RouteSynthesisScenario.TAGS)).path("reasonCode").asText())
                    .isEqualTo("runtime_line_unresolved");
        }
    }

    @Test
    void runtimeFirstFallbackValidatesStrictLineAgainstAttachedProbe() throws Exception {
        try (RouteSynthesisScenario s = RouteSynthesisScenario.start()) {
            s.runtime.attach();
            int line = RouteSynthesisScenario.line(RouteSynthesisScenario.TAGS, "lockTag",
                    "return tagLifecycleService.lockTag(tenantId, tagName, userContext);");
            Map<String, Object> input = s.recipeArguments(RouteSynthesisScenario.TAGS, "lockTag");
            input.putAll(Map.of("intentMode", "line_probe", "lineHint", line, "discoveryPreference", "static_only"));
            JsonNode baseline = s.recipe(input);
            input.put("discoveryPreference", "runtime_first");
            for (String mappings : List.of("", "http://127.0.0.1:" + freePort())) {
                if (mappings.isEmpty()) {
                    input.remove("mappingsBaseUrl");
                } else {
                    input.put("mappingsBaseUrl", mappings);
                }
                JsonNode fallback = s.recipe(input);
                assertThat(fallback.path("requestCandidates")).isEqualTo(baseline.path("requestCandidates"));
                assertThat(fallback.path("executionPlan").path("probeCallPlan").path("total").asInt()).isPositive();
                String key = RouteSynthesisScenario.TAGS + "#lockTag:" + line;
                assertThat(s.runtime.status(key).path("lineValidation").asText()).isEqualTo("resolvable");
                input.put("lineHint", line - 1);
                assertThat(s.result("create_recipe", input).path("reasonCode").asText())
                        .isEqualTo("runtime_line_unresolved");
                input.put("lineHint", line);
            }
            s.runtime.deactivate();
            assertThat(s.result("create_recipe", input).path("reasonCode").asText())
                    .isEqualTo("runtime_line_unresolved");
            JavaItScenario.observe("route_synthesis.create_recipe", "ASSERTED", "runtime_first_strict_line_fallback");
        }
    }
}
