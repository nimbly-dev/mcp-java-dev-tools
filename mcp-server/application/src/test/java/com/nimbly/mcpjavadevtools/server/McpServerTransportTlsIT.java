package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Tag("transport")
@Timeout(90)
class McpServerTransportTlsIT extends McpServerStdioSupport {
    // Ephemeral fixture key material; never a user or product credential.
    static final String STORE_PASSWORD = "transport-fixture-only";

    @Test
    void httpsUsesNormalCertificateValidationAndBlocksDowngradeBeforeHttpRequest() throws Exception {
        Path workspace = JavaItScenario.workspace();
        Path store = createKeyStore(workspace);
        try (TransportHttpTarget http = TransportHttpTarget.start();
                TransportHttpTarget https = TransportHttpTarget.startTls(tlsContext(store), exchange -> {
                    if (exchange.getRequestURI().getPath().equals("/downgrade")) {
                        exchange.getResponseHeaders().set("Location", http.url("/blocked"));
                        TransportHttpTarget.respond(exchange, 302, "redirect");
                    } else {
                        TransportHttpTarget.respond(exchange, 200, "secure-response");
                    }
                })) {
            try (TransportScenario untrusted = TransportScenario.start()) {
                JsonNode failure = untrusted.execute(Map.of("method", "GET", "url", https.url("/untrusted")));
                TransportScenario.assertBlocked(failure, "blocked_runtime", "transport_request_failed");
                https.assertNoRequest();
            }
            Map<String, String> environment = Map.of("JAVA_TOOL_OPTIONS", "-Djavax.net.ssl.trustStore=\"" + store
                    + "\" -Djavax.net.ssl.trustStorePassword=" + STORE_PASSWORD);
            try (TransportScenario trusted = TransportScenario.start(workspace, environment)) {
                JsonNode result = trusted.execute(Map.of("method", "GET", "url", https.url("/secure")));
                assertThat(result.path("status").asText()).isEqualTo("pass");
                assertThat(result.path("bodyPreview").asText()).isEqualTo("secure-response");
                assertThat(https.received().uri()).isEqualTo("/secure");
                JsonNode downgrade = trusted.execute(Map.of("method", "GET", "url", https.url("/downgrade")));
                TransportScenario.assertBlocked(downgrade, "blocked_invalid", "http_redirect_downgrade");
                assertThat(https.received().uri()).isEqualTo("/downgrade");
                http.assertNoRequest();
            }
        }
    }

    static Path createKeyStore(Path workspace) throws Exception {
        Path store = workspace.resolve("fixture.p12");
        String suffix = System.getProperty("os.name").toLowerCase().contains("win") ? ".exe" : "";
        Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool" + suffix);
        assertThat(keytool).as("JDK keytool is required for the HTTPS fixture").isRegularFile();
        Process process = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "transport-it", "-keyalg", "RSA",
                "-storetype", "PKCS12", "-keystore", store.toString(), "-storepass", STORE_PASSWORD,
                "-keypass", STORE_PASSWORD, "-dname", "CN=localhost", "-validity", "1",
                "-ext", "SAN=ip:127.0.0.1,dns:localhost", "-noprompt")
                .redirectErrorStream(true).redirectOutput(workspace.resolve("keytool.log").toFile()).start();
        JavaItScenario.own(process);
        assertThat(process.waitFor(20, TimeUnit.SECONDS)).as("bounded fixture certificate creation").isTrue();
        assertThat(process.exitValue()).isZero();
        return store;
    }

    private SSLContext tlsContext(Path store) throws Exception {
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(store)) {
            keys.load(input, STORE_PASSWORD.toCharArray());
        }
        KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keys, STORE_PASSWORD.toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(managers.getKeyManagers(), null, null);
        return context;
    }
}
