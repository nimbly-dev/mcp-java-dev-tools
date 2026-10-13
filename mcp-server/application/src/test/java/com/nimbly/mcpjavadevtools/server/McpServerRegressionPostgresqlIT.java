package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.security.KeyStore;
import java.util.Base64;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("regression")
@Tag("regression-postgresql")
@Timeout(240)
class McpServerRegressionPostgresqlIT extends RegressionMcpSupport {
    @ParameterizedTest
    @ValueSource(strings = {"disable", "require", "verify-full"})
    void projectOwnedPostgresqlConnectionVerifiesTheRowInsertedByARealTrigger(String tlsMode) throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (PostgresqlTarget database = PostgresqlTarget.start(workspace, tlsMode);
                TransportHttpTarget target = TransportHttpTarget.start(exchange -> {
                    try (var connection = DriverManager.getConnection(database.jdbc(), "fixture", database.password);
                            var statement = connection.prepareStatement("INSERT INTO effects(id, state) VALUES (?, ?)")) {
                        if (exchange.getRequestURI().getPath().equals("/insert")) {
                            statement.setString(1, "created-by-http");
                            statement.setString(2, "ready");
                            statement.executeUpdate();
                        }
                    } catch (java.sql.SQLException exception) {
                        throw new java.io.IOException("PostgreSQL fixture insert failed");
                    }
                    TransportHttpTarget.respond(exchange, 200, "{\"id\":\"created-by-http\"}");
                }); McpServerProcess server = McpServerProcess.startWithEnvironment(jarPath(), workspace,
                        Map.of("JAVA_TOOL_OPTIONS", "-Duser.home=\"" + workspace + "\"", "APPDATA", workspace.resolve("appdata").toString()),
                        "--workspace-root=" + workspace)) {
            initialize(server);
            saveContext(server, project(workspace, target, database, tlsMode));
            ObjectNode plan = plan(target, tlsMode);
            save(server, "postgresql", plan);
            JsonNode result = run(server, "postgresql", "postgresql");
            assertThat(result.path("details").path("runStatus").asText()).as(result.toPrettyString()).isEqualTo("pass");
            assertThat(result.path("details").path("externalVerification").get(0).path("sql").path("firstRow").path("state").asText())
                    .isEqualTo("ready");
            assertThat(target.received().uri()).isEqualTo("/insert");
            assertThat(assertRun(server, workspace, "postgresql", "postgresql", "pass").toString()).doesNotContain(database.password);
            assertThat(String.join("\n", server.stdoutLines())).doesNotContain(database.password);
            if (tlsMode.equals("verify-full")) {
                assertUntrustedCertificate(server, workspace, target, plan);
            }
        }
    }

    private ObjectNode project(Path workspace, TransportHttpTarget target, PostgresqlTarget database, String tlsMode) {
        ObjectNode project = context(workspace, target.url(""), "postgresql");
        ObjectNode root = (ObjectNode) project.path("workspaces").get(0);
        root.put("envFile", "database.env").putObject("variables").putObject("contextBindings")
                .put("sql.connection.effects.password", "POSTGRES_PASSWORD");
        ObjectNode provided = (ObjectNode) root.path("executionProfiles").get(0).path("providedContext");
        provided.put("sql.connection.effects.kind", "postgresql").put("sql.connection.effects.host", "127.0.0.1")
                .put("sql.connection.effects.port", database.port).put("sql.connection.effects.database", "fixture")
                .put("sql.connection.effects.username", "fixture").put("sql.connection.effects.tls.mode", tlsMode)
                .put("externalState", "ready");
        return project;
    }

    private ObjectNode plan(TransportHttpTarget target, String tlsMode) {
        ObjectNode plan = generatedPlan(target.url("/insert"));
        step(plan, 0).putArray("extract").addObject().put("from", "response.bodyJson.id").put("as", "createdId");
        McpServerRegressionVerificationIT.sql(plan);
        ObjectNode sql = (ObjectNode) contract(plan).path("externalVerification").get(0);
        ((ObjectNode) sql.path("request").path("sql")).put("statement",
                "SELECT state, ':literal' AS literal, (SELECT ssl FROM pg_stat_ssl WHERE pid=pg_backend_pid()) AS encrypted "
                        + "FROM effects WHERE id = :id");
        expect(sql, "sql.firstRow.encrypted", "field_equals", !tlsMode.equals("disable"));
        return plan;
    }

    private void assertUntrustedCertificate(McpServerProcess server, Path workspace, TransportHttpTarget target, ObjectNode plan)
            throws Exception {
        Files.delete(workspace.resolve(".postgresql/root.crt"));
        Files.delete(workspace.resolve("appdata/postgresql/root.crt"));
        http(step(plan, 0)).put("url", target.url("/observe"));
        save(server, "postgresql", plan);
        JsonNode result = run(server, "postgresql", "untrusted");
        assertThat(result.path("reasonCode").asText()).isEqualTo("external_verification_execution_failed");
        assertThat(target.received().uri()).isEqualTo("/observe");
        assertRun(server, workspace, "postgresql", "untrusted", "fail");
    }

    private static class PostgresqlTarget implements AutoCloseable {
        private final Path workspace;
        private final String name = "mcpjvm-652-" + UUID.randomUUID();
        private final String password = UUID.randomUUID().toString();
        private int port;
        private String containerId;

        private PostgresqlTarget(Path workspace) {
            this.workspace = workspace;
        }

        static PostgresqlTarget start(Path workspace, String tlsMode) throws Exception {
            PostgresqlTarget target = new PostgresqlTarget(workspace);
            try {
                target.command(List.of("info", "--format", "{{.ServerVersion}}"), 15);
                Files.writeString(workspace.resolve("database.env"), "POSTGRES_USER=fixture\nPOSTGRES_DB=fixture\nPOSTGRES_PASSWORD="
                        + target.password + "\n");
                target.containerId = target.command(List.of("run", "--pull=missing", "--detach", "--rm", "--name", target.name,
                        "--label", "mcpjvm.scenario=" + target.name, "--env-file", workspace.resolve("database.env").toString(),
                        "--publish", "127.0.0.1::5432", "--tmpfs", "/var/lib/postgresql/data:rw", "--tmpfs", "/tmp:rw", "postgres:16-alpine"),
                        120).trim();
                assertThat(target.containerId).matches("[a-f0-9]{64}");
                String address = target.command(List.of("port", target.containerId, "5432/tcp"), 10).trim();
                target.port = Integer.parseInt(address.substring(address.lastIndexOf(':') + 1));
                target.awaitReady();
                if (!tlsMode.equals("disable")) {
                    target.enableTls();
                }
                return target;
            } catch (Throwable failure) {
                target.close();
                throw failure;
            }
        }

        String jdbc() {
            return "jdbc:postgresql://127.0.0.1:" + port + "/fixture?connectTimeout=1&socketTimeout=3&sslmode=disable";
        }

        private void enableTls() throws Exception {
            Path store = McpServerTransportTlsIT.createKeyStore(workspace);
            KeyStore keys = KeyStore.getInstance("PKCS12");
            try (var input = Files.newInputStream(store)) {
                keys.load(input, McpServerTransportTlsIT.STORE_PASSWORD.toCharArray());
            }
            Path certificate = workspace.resolve("server.crt");
            Path privateKey = workspace.resolve("server.key");
            Files.writeString(certificate, pem("CERTIFICATE", keys.getCertificate("transport-it").getEncoded()));
            Files.writeString(privateKey, pem("PRIVATE KEY", keys.getKey("transport-it", McpServerTransportTlsIT.STORE_PASSWORD.toCharArray())
                    .getEncoded()));
            for (String directory : List.of(".postgresql", "appdata/postgresql")) {
                Path trust = Files.createDirectories(workspace.resolve(directory));
                Files.copy(certificate, trust.resolve("root.crt"));
            }
            // Docker cp cannot populate the running container's tmpfs mount on every supported engine.
            command(List.of("cp", certificate.toString(), containerId + ":/server.crt"), 10);
            command(List.of("cp", privateKey.toString(), containerId + ":/server.key"), 10);
            command(List.of("exec", "--user", "0", containerId, "sh", "-c",
                    "chown postgres:postgres /server.crt /server.key && chmod 600 /server.key"), 10);
            try (var connection = DriverManager.getConnection(jdbc(), "fixture", password); var statement = connection.createStatement()) {
                statement.execute("ALTER SYSTEM SET ssl = 'on'");
                statement.execute("ALTER SYSTEM SET ssl_cert_file = '/server.crt'");
                statement.execute("ALTER SYSTEM SET ssl_key_file = '/server.key'");
                statement.execute("SELECT pg_reload_conf()");
            }
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline) {
                try (var connection = DriverManager.getConnection(jdbc().replace("sslmode=disable", "sslmode=require"), "fixture", password)) {
                    return;
                } catch (java.sql.SQLException pending) {
                    Thread.sleep(100);
                }
            }
            throw new AssertionError("PostgreSQL fixture TLS did not become ready");
        }

        private String pem(String type, byte[] data) {
            return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(data)
                    + "\n-----END " + type + "-----\n";
        }

        private void awaitReady() throws Exception {
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (System.nanoTime() < deadline) {
                try (var connection = DriverManager.getConnection(jdbc(), "fixture", password);
                        var statement = connection.createStatement()) {
                    statement.execute("CREATE TABLE effects (id TEXT PRIMARY KEY, state TEXT)");
                    return;
                } catch (java.sql.SQLException unavailable) {
                    Thread.sleep(100);
                }
            }
            throw new AssertionError("PostgreSQL fixture did not become ready within its deadline");
        }

        private String command(List<String> arguments, int seconds) throws Exception {
            Path output = Files.createTempFile(workspace, "docker-", ".out");
            try {
                var command = new java.util.ArrayList<>(List.of("docker"));
                command.addAll(arguments);
                Process process = new ProcessBuilder(command).directory(workspace.toFile())
                        .redirectErrorStream(true).redirectOutput(output.toFile()).start();
                JavaItScenario.own(process);
                assertThat(process.waitFor(seconds, TimeUnit.SECONDS)).as("bounded PostgreSQL Docker command").isTrue();
                assertThat(Files.size(output)).isLessThan(4096);
                assertThat(process.exitValue()).as("postgresql_it_docker_command_failed: %s", Files.readString(output).replace(password, "[REDACTED]"))
                        .isZero();
                return Files.readString(output);
            } finally {
                Files.deleteIfExists(output);
            }
        }

        @Override
        public void close() throws Exception {
            // Name and label are unique to this scenario, including a partially completed docker run.
            String owned = command(List.of("ps", "-aq", "--filter", "label=mcpjvm.scenario=" + name), 10).trim();
            if (!owned.isEmpty()) {
                assertThat(owned).matches("[a-f0-9]{12,64}");
                command(List.of("rm", "--force", owned), 10);
                assertThat(command(List.of("ps", "-aq", "--filter", "label=mcpjvm.scenario=" + name), 10).trim()).isEmpty();
            }
            JavaItScenario.observe("fixture.postgresql", "CLEANED", "owned_container_removed");
        }
    }
}
