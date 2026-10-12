package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Tag("transport")
@Timeout(90)
class McpServerTransportCredentialIT extends McpServerStdioSupport {
    @Test
    void credentialHeadersStayAtTheIntendedOriginWhileSafeHeadersReachTheRedirect() throws Exception {
        String secret = UUID.randomUUID().toString();
        try (TransportHttpTarget destination = TransportHttpTarget.start();
                TransportHttpTarget source = TransportHttpTarget.start(exchange -> {
                    exchange.getResponseHeaders().set("Location", destination.url("/destination"));
                    TransportHttpTarget.respond(exchange, 307, "redirect");
                }); TransportScenario s = TransportScenario.start(secret)) {
            List<String> sensitive = List.of("X-Api-Key", "Api-Key", "X-Auth-Token", "X-Client-Secret");
            Map<String, String> headers = new java.util.LinkedHashMap<>(Map.of("X-Safe", "retained"));
            sensitive.forEach(name -> headers.put(name, secret));
            assertThat(s.execute(Map.of("method", "GET", "url", source.url("/redirect"), "headers", headers))
                    .path("status").asText()).isEqualTo("pass");
            TransportHttpTarget.Received intended = source.received();
            TransportHttpTarget.Received redirected = destination.received();
            for (String name : sensitive) {
                assertThat(secret.equals(intended.header(name))).as("intended-origin credential %s", name).isTrue();
                assertThat(redirected.header(name) == null).as("cross-origin credential %s must be removed", name).isTrue();
            }
            assertThat(redirected.header("X-Safe")).isEqualTo("retained");
            s.assertSecretsAbsent(secret);
            JavaItScenario.observe(TransportScenario.OPERATION, "ASSERTED", "sensitive_header_origin_isolation");
        }
    }
}
