package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("transport")
@Timeout(90)
class McpServerTransportSafetyIT extends McpServerStdioSupport {
    @Test
    void discoveryValidationAndConfirmationPreventNetworkEffects() throws Exception {
        try (TransportHttpTarget target = TransportHttpTarget.start(); TransportScenario s = TransportScenario.start()) {
            s.server.send(request(20, "tools/call", Map.of("name", "operation_catalog",
                    "arguments", Map.of("api", "transport_execute"))));
            JsonNode entries = toolPayload(s.server.responseFor(20)).path("entries");
            assertThat(entries).extracting(entry -> entry.path("operationId").asText()).containsExactly(TransportScenario.OPERATION);
            s.server.send(request(21, "tools/call", Map.of("name", "operation_describe",
                    "arguments", Map.of("operationId", TransportScenario.OPERATION))));
            JsonNode operation = toolPayload(s.server.responseFor(21)).path("operation");
            assertThat(operation.path("safety").path("confirmationRequired").asBoolean()).isTrue();
            assertThat(operation.path("inputSchema").path("required"))
                    .containsExactly(JSON.valueToTree("protocol"), JSON.valueToTree("request"));
            assertThat(operation.path("inputSchema").path("properties").path("options").path("default").path("wrappedOnly").asBoolean())
                    .isTrue();
            Map<String, Object> arguments = Map.of("protocol", "http", "request", Map.of("method", "GET", "url", target.url("/")));
            assertThat(s.call(TransportScenario.OPERATION, arguments, false).path("status").asText()).isEqualTo("confirmation_required");
            for (Map<String, Object> invalid : List.of(Map.<String, Object>of("protocol", "HTTP", "request", Map.of()),
                    Map.<String, Object>of("protocol", "http"), Map.<String, Object>of("protocol", "http", "request", List.of()),
                    Map.<String, Object>of("protocol", "http", "request", Map.of(), "options", Map.of("wrappedOnly", "true")),
                    Map.<String, Object>of("protocol", "http", "request", Map.of(), "unknown", "ignored"))) {
                assertThat(s.call(TransportScenario.OPERATION, invalid, true).path("status").asText()).isEqualTo("invalid_input");
            }
            target.assertNoRequest();
            assertThat(s.result(arguments).path("status").asText()).isEqualTo("pass");
            target.received();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"grpc", "kafka", "custom"})
    void recognizedUnsupportedProvidersNeverContactTheHttpTarget(String protocol) throws Exception {
        try (TransportHttpTarget target = TransportHttpTarget.start(); TransportScenario s = TransportScenario.start()) {
            JsonNode result = s.result(Map.of("protocol", protocol, "request", Map.of("method", "GET", "url", target.url("/"))));
            TransportScenario.assertBlocked(result, "blocked_invalid", "transport_not_supported");
            assertThat(result.path("protocol").asText()).isEqualTo(protocol);
            assertThat(result.path("reasonMeta").path("failedStep").asText()).isEqualTo("transport_execute_protocol");
            target.assertNoRequest();
            JavaItScenario.observe(TransportScenario.OPERATION, "ASSERTED", "unsupported_" + protocol);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void wrappedOnlyUsesTheScenarioRegistryAndExplicitOverride(boolean allowNonWrapped) throws Exception {
        Path workspace = JavaItScenario.workspace();
        Path directory = Files.createDirectories(workspace.resolve(".mcpjvm"));
        JSON.writeValue(directory.resolve("probe-config.json").toFile(), Map.of("defaultProfile", "local", "profiles", Map.of(
                "local", Map.of("global", Map.of("allowNonWrappedExecutable", allowNonWrapped),
                        "probes", Map.of("transport-it", Map.of("baseUrl", "http://127.0.0.1:1"))))));
        try (TransportHttpTarget target = TransportHttpTarget.start(); TransportScenario s = TransportScenario.start(workspace)) {
            Map<String, Object> args = new LinkedHashMap<>(Map.of("protocol", "http", "request",
                    Map.of("method", "GET", "url", target.url("/policy"))));
            JsonNode defaults = s.result(args);
            if (allowNonWrapped) {
                TransportScenario.assertBlocked(defaults, "blocked_invalid", "wrapper_policy_violation");
                target.assertNoRequest();
            } else {
                assertThat(defaults.path("status").asText()).isEqualTo("pass");
                target.received();
            }
            args.put("options", Map.of("wrappedOnly", true));
            assertThat(s.result(args).path("status").asText()).isEqualTo(defaults.path("status").asText());
            if (!allowNonWrapped) {
                target.received();
            }
            args.put("options", Map.of("wrappedOnly", false));
            assertThat(s.result(args).path("status").asText()).isEqualTo("pass");
            assertThat(target.received().uri()).isEqualTo("/policy");
            target.assertNoRequest();
        }
    }

    @Test
    void invalidHttpEnvelopesAreRejectedBeforeSendingAndRecover() throws Exception {
        try (TransportHttpTarget target = TransportHttpTarget.start(); TransportScenario s = TransportScenario.start()) {
            Map<Map<String, Object>, String> cases = invalidHttpCases();
            for (var entry : cases.entrySet()) {
                Map<String, Object> input = new LinkedHashMap<>(Map.of("method", "GET", "url", target.url("/invalid")));
                input.putAll(entry.getKey());
                TransportScenario.assertBlocked(s.execute(input), "blocked_invalid", entry.getValue());
            }
            TransportScenario.assertBlocked(s.execute(Map.of("url", target.url("/"))), "blocked_invalid", "http_payload_invalid");
            TransportScenario.assertBlocked(s.execute(Map.of("method", "GET")), "blocked_invalid", "http_payload_invalid");
            target.assertNoRequest();
            assertThat(s.execute(Map.of("method", "GET", "url", target.url("/recover"))).path("status").asText()).isEqualTo("pass");
            assertThat(target.received().uri()).isEqualTo("/recover");
        }
    }

    private Map<Map<String, Object>, String> invalidHttpCases() {
        Map<Map<String, Object>, String> cases = new LinkedHashMap<>();
        for (Object method : List.of("TRACE", "", 12)) {
            cases.put(Map.of("method", method), method.equals("TRACE") ? "http_method_not_allowed" : "http_payload_invalid");
        }
        for (Object timeout : List.of(0, -1, 300001)) {
            cases.put(Map.of("timeoutMs", timeout), "http_timeout_invalid");
        }
        for (Object timeout : List.of("100", 1.5)) {
            cases.put(Map.of("timeoutMs", timeout), "http_payload_invalid");
        }
        Map<String, String> urls = Map.of("relative", "http_url_invalid", "http://[bad", "http_url_invalid",
                "file:///blocked", "http_scheme_not_allowed", "http:/missing-host", "http_host_required",
                "http://user:private@127.0.0.1/", "http_user_info_not_allowed", "http://example.invalid/", "http_host_not_allowed");
        urls.forEach((url, reason) -> cases.put(Map.of("url", url), reason));
        for (Object headers : List.of("headers", Map.of("X-Value", 1), Map.of("X-Value", "bad\r\ninjected"),
                Map.of("X-Value", "a", "x-value", "b"))) {
            cases.put(Map.of("headers", headers), "http_payload_invalid");
        }
        cases.put(Map.of("headers", Map.of("Invalid Header", "value")), "http_headers_too_large");
        cases.put(Map.of("headers", Map.of("X-Large", "x".repeat(65537))), "http_headers_too_large");
        Map<String, String> headers = new LinkedHashMap<>();
        java.util.stream.IntStream.range(0, 129).forEach(i -> headers.put("X-" + i, "value"));
        cases.put(Map.of("headers", headers), "http_headers_too_large");
        for (Object body : List.of(" ", 12, true)) {
            cases.put(Map.of("body", body), "http_payload_invalid");
        }
        return cases;
    }

    @Test
    void configuredExactHostIsAllowedWithoutAllowingOtherHosts() throws Exception {
        // A noncanonical loopback spelling exercises the extra-host setting without external DNS/network.
        String host = "127.000.000.001";
        Path workspace = JavaItScenario.workspace();
        try (TransportHttpTarget target = TransportHttpTarget.start()) {
            String url = target.url("/configured").replace("127.0.0.1", host);
            try (TransportScenario defaults = TransportScenario.start()) {
                TransportScenario.assertBlocked(defaults.execute(Map.of("method", "GET", "url", url)),
                        "blocked_invalid", "http_host_not_allowed");
                target.assertNoRequest();
            }
            try (TransportScenario configured = TransportScenario.start(workspace,
                    Map.of(), "--mcpjvm.transport-execution.http.allowed-hosts=" + host)) {
                JsonNode result = configured.execute(Map.of("method", "GET", "url", url));
                assertThat(result.path("status").asText()).isEqualTo("pass");
                assertThat(target.received().uri()).isEqualTo("/configured");
                TransportScenario.assertBlocked(configured.execute(Map.of("method", "GET", "url", "http://example.invalid/")),
                        "blocked_invalid", "http_host_not_allowed");
                target.assertNoRequest();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"json", "text", "malformed", "embedded", "long"})
    void successAndHttpFailureRedactHeadersAndBodyBeforePreviewTruncation(String format) throws Exception {
        String secret = UUID.randomUUID().toString();
        String body = switch (format) {
            case "json" -> "{\"safe\":\"ok\",\"nested\":[{\"password\":\"" + secret + "\"}]}";
            case "text" -> "safe=ok password=" + secret + " Authorization: Bearer " + secret;
            case "malformed" -> "{\"token\":\"" + secret;
            case "embedded" -> "prefix {\"credential\":\"" + secret + "\"} suffix";
            default -> "{\"safe\":\"" + "x".repeat(2100) + "\",\"token\":\"" + secret + "\"}";
        };
        try (TransportHttpTarget target = TransportHttpTarget.start(exchange -> {
            for (String name : List.of("Authorization", "Set-Cookie", "X-Api-Key", "X-Auth-Token")) {
                exchange.getResponseHeaders().set(name, secret);
            }
            TransportHttpTarget.respond(exchange, exchange.getRequestURI().getPath().equals("/fail") ? 500 : 200, body);
        }); TransportScenario s = TransportScenario.start(secret)) {
            for (String path : List.of("/pass", "/fail")) {
                JsonNode result = s.execute(Map.of("method", "GET", "url", target.url(path)));
                assertThat(result.path("status").asText()).isEqualTo(path.equals("/fail") ? "fail_http" : "pass");
                TransportScenario.assertSanitized(result.toString(), secret);
                assertThat(result.path("bodyPreview").asText().length()).isLessThanOrEqualTo(2048);
                for (String name : List.of("authorization", "set-cookie", "x-api-key", "x-auth-token")) {
                    String marker = name.equals("authorization") || name.equals("x-auth-token") ? "***REDACTED***" : "[REDACTED]";
                    assertThat(marker.equals(result.path("headers").path(name).asText())).as("sensitive response header redacted").isTrue();
                }
                target.received();
            }
            s.assertSecretsAbsent(secret);
            JavaItScenario.observe(TransportScenario.OPERATION, "ASSERTED", "redaction_" + format);
        }
    }
}
