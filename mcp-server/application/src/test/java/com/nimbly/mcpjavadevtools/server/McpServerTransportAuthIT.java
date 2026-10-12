package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("transport")
@Timeout(90)
class McpServerTransportAuthIT extends McpServerStdioSupport {
    @ParameterizedTest
    @ValueSource(strings = {"bearer", "basic", "cookie"})
    void missingBindingsGateRecipesAndResolvedCredentialsReachOnlyTheTarget(String strategy) throws Exception {
        String secret = UUID.randomUUID().toString();
        String username = "scenario-" + UUID.randomUUID();
        String header = strategy.equals("cookie") ? "Cookie" : "Authorization";
        String value = credential(strategy, username, secret);
        try (TransportHttpTarget target = TransportHttpTarget.start(exchange -> {
            boolean authorized = value.equals(exchange.getRequestHeaders().getFirst(header));
            TransportHttpTarget.respond(exchange, authorized ? 200 : 401, "{\"safe\":\"authenticated\"}");
        }); TransportScenario s = TransportScenario.start(secret, username, value)) {
            Path sources = authSources(s.workspace, strategy);
            Map<String, Object> input = new LinkedHashMap<>(Map.of("projectRootAbs", sources.toString(),
                    "classHint", "transport.fixture.SecuredController", "methodHint", "read", "intentMode", "regression"));
            assertMissing(s, input, strategy.equals("basic") ? "authUsername" : "authToken");
            if (strategy.equals("basic")) {
                input.put("authUsername", username);
                assertMissing(s, input, "authPassword");
                input.put("authPassword", secret);
            } else {
                input.put("authToken", strategy.equals("cookie") ? value : secret);
            }
            target.assertNoRequest();
            JsonNode ready = s.call("route_synthesis.create_recipe", input, true).path("result");
            assertThat(ready.path("status").asText()).isEqualTo("ready");
            assertThat(ready.path("actionResult").path("auth").path("strategy").asText()).isEqualTo(strategy);
            JsonNode candidate = ready.path("actionResult").path("requestCandidates").get(0);
            assertThat(candidate.path("path").asText()).isEqualTo("/secured");
            JsonNode result = s.execute(Map.of("method", candidate.path("method").asText(),
                    "url", target.url(candidate.path("path").asText()), "headers", Map.of(header, value)));
            assertThat(result.path("statusCode").asInt()).isEqualTo(200);
            assertThat(result.path("status").asText()).isEqualTo("pass");
            assertThat(value.equals(target.received().header(header))).as("target authenticates with the scenario binding").isTrue();
            assertThat(s.execute(Map.of("method", "GET", "url", target.url("/secured"))).path("statusCode").asInt()).isEqualTo(401);
            assertThat(target.received().header(header) == null).as("credentials must not bleed into a later operation").isTrue();
            s.assertSecretsAbsent(secret, username, value);
            JavaItScenario.observe("route_synthesis.create_recipe", "ASSERTED", "auth_missing_blocked_then_resolved");
            JavaItScenario.observe(TransportScenario.OPERATION, "ASSERTED", "auth_" + strategy);
        }
    }

    private void assertMissing(TransportScenario s, Map<String, Object> input, String missing) throws Exception {
        JsonNode result = s.call("route_synthesis.create_recipe", input, true).path("result");
        assertThat(result.path("status").asText()).isEqualTo("execution_input_required");
        assertThat(result.path("reasonCode").asText()).isEqualTo("auth_input_required");
        assertThat(result.path("actionResult").path("executionReadiness").asText()).isEqualTo("needs_user_input");
        assertThat(result.path("actionResult").path("auth").path("missing")).contains(JSON.valueToTree(missing));
    }

    private String credential(String strategy, String username, String secret) {
        return switch (strategy) {
            case "basic" -> "Basic " + Base64.getEncoder().encodeToString((username + ":" + secret).getBytes(StandardCharsets.UTF_8));
            case "cookie" -> "session=" + secret;
            default -> "Bearer " + secret;
        };
    }

    private Path authSources(Path workspace, String strategy) throws Exception {
        Path source = Files.createDirectories(workspace.resolve("sources"));
        Files.writeString(source.resolve("SecuredController.java"), """
                package transport.fixture;
                import org.springframework.web.bind.annotation.RestController;
                import org.springframework.web.bind.annotation.GetMapping;
                @RestController
                public class SecuredController {
                    @GetMapping("/secured")
                    public String read() {
                        return "secured";
                    }
                }
                """);
        String security = strategy.equals("cookie") ? "type: apiKey\n    in: cookie" : "type: http\n    scheme: " + strategy;
        Files.writeString(source.resolve("openapi.yaml"), "openapi: 3.0.0\nsecurity:\n  - scenario: []\n"
                + "components:\n  securitySchemes:\n    scenario:\n    " + security + "\n");
        return source;
    }
}
