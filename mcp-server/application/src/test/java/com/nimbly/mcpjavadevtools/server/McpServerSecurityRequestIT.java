package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("security")
@Timeout(180)
class McpServerSecurityRequestIT extends SecurityMcpSupport {
    @ParameterizedTest
    @ValueSource(strings = {"own resource", "own + é%"})
    void transportPathFixtureValuesAreEncodedWithoutChangingExistingEscapes(String fixture) throws Exception {
        Path workspace = JavaItScenario.workspace();
        String encoded = fixture.equals("own resource") ? "own%20resource" : "own%20+%20%C3%A9%25";
        try (TransportHttpTarget target = TransportHttpTarget.start(); McpServerProcess server = start(workspace)) {
            saveContext(server, context(workspace, "plain", "encoded"));
            for (String name : List.of("plain", "encoded")) {
                ObjectNode plan = generatedPlan(target.url(""));
                packs(plan, "web-api-core");
                contract(plan).withObject("targetBoundary").putObject("fixtureContext").put("ownResourceId", fixture);
                String prefix = name.equals("plain") ? "" : "/encoded%20prefix";
                String query = name.equals("plain") ? "" : "?keep=one%20two";
                entry(plan).withObject("transport").put("path", prefix + "/resource/${fixture.ownResourceId}" + query);
                save(server, name, plan);
                JsonNode result = run(server, name, name);
                completed(result, "pass", 2);
                assertThat(target.received().uri()).isEqualTo(prefix + "/resource/" + encoded + query);
                target.assertNoRequest();
                assertThat(assertRun(server, workspace, name, name, "pass").path("details")).isEqualTo(result.path("details"));
            }
            JavaItScenario.observe(OPERATION, "ASSERTED", "encoded_transport_path_fixture");
        }
    }

    @Test
    void fixtureExpressionsResolveBeforeEncodingAcrossRequestFieldsAndContextOverrides() throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (TransportHttpTarget target = TransportHttpTarget.start(); McpServerProcess server = start(workspace)) {
            saveContext(server, context(workspace, "fixtures"));
            ObjectNode plan = generatedPlan(target.url(""));
            packs(plan, "web-api-core");
            ObjectNode boundary = contract(plan).withObject("targetBoundary");
            boundary.putObject("fixtureContext").put("ownResourceId", "direct").put("sequence", 7).put("enabled", true);
            boundary.putObject("details").putObject("fixtureContext").put("ownResourceId", "boundary");
            entry(plan).putObject("details").putObject("fixtureContext").put("ownResourceId", "own + é%");
            entry(plan).withObject("transport").put("method", "POST").put("path", "/resource/${fixture.sequence}/{id}");
            ObjectNode baseline = entry(plan).putObject("baseline");
            baseline.putObject("pathParameters").put("id", "${fixture.ownResourceId}");
            baseline.putObject("query").put("q", "prefix-${fixture.ownResourceId}");
            baseline.putObject("headers").put("X-Fixture", "${fixture.sequence}-${fixture.enabled}");
            baseline.putObject("body").putArray("values").add("${fixture.ownResourceId}").add(7).add(true);
            save(server, "fixtures", plan);
            JsonNode result = run(server, "fixtures", "fixtures");
            completed(result, "pass", 2);
            var request = target.received();
            var uri = java.net.URI.create(request.uri());
            assertThat(uri.getPath()).isEqualTo("/resource/7/own + é%");
            assertThat(java.net.URLDecoder.decode(uri.getRawQuery(), java.nio.charset.StandardCharsets.UTF_8))
                    .isEqualTo("q=prefix-own + é%");
            assertThat(request.header("X-Fixture")).isEqualTo("7-true");
            assertThat(JSON.readTree(request.body()).path("values"))
                    .isEqualTo(JSON.readTree("[\"own + é%\",7,true]"));
            target.assertNoRequest();
            assertRun(server, workspace, "fixtures", "fixtures", "pass");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"path", "pathParameters", "query", "headers", "textBody", "objectBody"})
    void missingFixtureExpressionsBlockEveryRequestFieldBeforeHttp(String field) throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (TransportHttpTarget target = TransportHttpTarget.start(); McpServerProcess server = start(workspace)) {
            saveContext(server, context(workspace, "missing-fixture"));
            ObjectNode plan = generatedPlan(target.url(""));
            packs(plan, "web-api-core");
            ObjectNode baseline = entry(plan).putObject("baseline");
            switch (field) {
                case "path" -> entry(plan).withObject("transport").put("path", "/resource/${fixture.missing}");
                case "pathParameters" -> {
                    entry(plan).withObject("transport").put("path", "/resource/{id}");
                    baseline.putObject(field).put("id", "${fixture.missing}");
                }
                case "query", "headers" -> baseline.putObject(field).put("value", "${fixture.missing}");
                case "textBody" -> baseline.put("body", "${fixture.missing}");
                case "objectBody" -> baseline.putObject("body").putArray("nested").add("${fixture.missing}");
                default -> throw new IllegalArgumentException("unknown fixture field");
            }
            save(server, "missing-fixture", plan);
            JsonNode result = run(server, "missing-fixture", "missing-fixture");
            assertBlocked(result, "security_blackbox_fixture_unresolved");
            target.assertNoRequest();
            assertThat(assertRun(server, workspace, "missing-fixture", "missing-fixture", "blocked").path("reasonCode").asText())
                    .isEqualTo("security_blackbox_fixture_unresolved");
            JavaItScenario.observe(OPERATION, "ASSERTED", "unresolved_fixture_" + field);
        }
    }

    @Test
    void explicitAuthenticationHeaderIsNeverReplacedByTheProfileDefault() throws Exception {
        Path workspace = JavaItScenario.workspace();
        Files.writeString(workspace.resolve(".env"), "SECURITY_IT_TOKEN=" + SECRET + "\nBAD_SECURITY_IT_TOKEN=invalid-fixture-token\n");
        try (TransportHttpTarget target = TransportHttpTarget.start(exchange -> {
            boolean authenticated = ("Bearer " + SECRET).equals(exchange.getRequestHeaders().getFirst("Authorization"));
            TransportHttpTarget.respond(exchange, authenticated ? 200 : 401, "{}");
        }); McpServerProcess server = start(workspace)) {
            ObjectNode project = context(workspace, "upper", "lower");
            ((ObjectNode) project.path("workspaces").get(0)).withObject("variables").withObject("contextBindings")
                    .put("bad-token", "BAD_SECURITY_IT_TOKEN");
            saveContext(server, project);
            for (String name : List.of("upper", "lower")) {
                ObjectNode plan = generatedPlan(target.url(""));
                packs(plan, "web-api-core");
                authenticated(plan, "bearer");
                entry(plan).putObject("baseline").putObject("headers")
                        .put(name.equals("upper") ? "Authorization" : "authorization", "Bearer ${bad-token}");
                save(server, name, plan);
                assertBlocked(run(server, name, name), "security_blackbox_baseline_unexpected");
                assertThat("Bearer invalid-fixture-token".equals(target.received().header("Authorization"))).isTrue();
                target.assertNoRequest();
                assertRun(server, workspace, name, name, "blocked");
            }
        }
    }

    @Test
    void missingDeclaredPrePlanScriptBlocksBeforeAnyTargetExecution() throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (TransportHttpTarget target = TransportHttpTarget.start(); McpServerProcess server = start(workspace)) {
            ObjectNode project = context(workspace, "missing-script");
            ((ObjectNode) project.path("workspaces").get(0).path("executionProfiles").get(0))
                    .putArray("scriptRefs").addObject().put("name", "missing").put("phase", "prePlan");
            saveContext(server, project);
            save(server, "missing-script", generatedPlan(target.url("")));
            assertBlocked(run(server, "missing-script", "missing-script"), "security_credential_refresh_failed");
            target.assertNoRequest();
            assertRun(server, workspace, "missing-script", "missing-script", "blocked");
        }
    }

    @Test
    void topLevelEntrypointAndSymbolicCredentialHeaderUseTheOwnedProjectBinding() throws Exception {
        Path workspace = JavaItScenario.workspace();
        Files.writeString(workspace.resolve(".env"), "SECURITY_IT_TOKEN=" + SECRET + "\n");
        try (TransportHttpTarget target = TransportHttpTarget.start(); McpServerProcess server = start(workspace)) {
            saveContext(server, context(workspace, "top-level"));
            ObjectNode plan = generatedPlan(target.url(""));
            authenticated(plan, "custom");
            packs(plan, "web-api-core");
            ObjectNode entry = entry(plan);
            entry.remove("transport");
            entry.put("type", "http").put("method", "POST").put("path", "/safe");
            entry.putObject("baseline").putObject("headers").put("X-Actor", "${actor-token}");
            save(server, "top-level", plan);
            JsonNode result = run(server, "top-level", "top-level");
            completed(result, "pass", 2);
            var observed = target.received();
            assertThat(observed.method()).isEqualTo("POST");
            assertThat(SECRET.equals(observed.header("X-Actor"))).isTrue();
            assertThat(SECRET.equals(observed.header("Authorization"))).isTrue();
            target.assertNoRequest();
            assertRun(server, workspace, "top-level", "top-level", "pass");
        }
    }

    @Test
    void transportWrappedCustomRequestPreservesItsDeclaredPayload() throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (TransportHttpTarget target = TransportHttpTarget.start(); McpServerProcess server = start(workspace)) {
            saveContext(server, context(workspace, "wrapped"));
            ObjectNode plan = generatedPlan(target.url(""));
            packs(plan, "web-api-core");
            entry(plan).withObject("transport").put("method", "POST");
            ObjectNode attack = custom(plan, "customCases", "wrapped", "allow");
            ObjectNode wrapped = attack.withObject("attack").putObject("transport").put("type", "http");
            wrapped.putObject("query").put("q", "observed");
            wrapped.putObject("headers").put("X-Scenario", "wrapped");
            wrapped.putObject("body").put("safe", "wrapped");
            save(server, "wrapped", plan);
            completed(run(server, "wrapped", "wrapped"), "pass", 2);
            target.received();
            target.received();
            var received = target.received();
            assertThat(received.uri()).contains("q=observed");
            assertThat(received.header("X-Scenario")).isEqualTo("wrapped");
            assertThat(JSON.readTree(received.body()).path("safe").asText()).isEqualTo("wrapped");
            target.assertNoRequest();
        }
    }

    @Test
    void encodedPathAndQueryValuesReachTheTargetExactlyOnce() throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (TransportHttpTarget target = TransportHttpTarget.start(); McpServerProcess server = start(workspace)) {
            saveContext(server, context(workspace, "encoding"));
            ObjectNode plan = generatedPlan(target.url(""));
            packs(plan, "web-api-core");
            entry(plan).withObject("transport").put("path", "/resource/{id}");
            ObjectNode baseline = entry(plan).putObject("baseline");
            baseline.putObject("pathParameters").put("id", "own resource");
            baseline.putObject("query").put("q", "spaces + percent% & unicode-é");
            save(server, "encoding", plan);
            completed(run(server, "encoding", "encoding"), "pass", 2);
            var received = target.received();
            var uri = java.net.URI.create(received.uri());
            assertThat(uri.getPath()).isEqualTo("/resource/own resource");
            assertThat(java.net.URLDecoder.decode(uri.getRawQuery(), java.nio.charset.StandardCharsets.UTF_8))
                    .isEqualTo("q=spaces + percent% & unicode-é");
            target.assertNoRequest();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"bearer", "basic", "api_key", "custom", "bearer-prefixed", "basic-prefixed"})
    void projectCredentialsBlockBeforeRequestsThenReachOnlyTheOwnedTarget(String variant) throws Exception {
        String kind = variant.replace("-prefixed", "");
        Path workspace = JavaItScenario.workspace();
        String header = kind.equals("api_key") ? "X-API-Key" : "Authorization";
        String value = kind.equals("basic") ? java.util.Base64.getEncoder().encodeToString(
                ("actor:" + SECRET).getBytes(java.nio.charset.StandardCharsets.UTF_8)) : SECRET;
        if (variant.endsWith("-prefixed")) {
            value = kind + " " + value;
        }
        String bound = value;
        String credential = variant.endsWith("-prefixed") ? bound : switch (kind) {
            case "bearer" -> "Bearer " + SECRET;
            case "basic" -> "Basic " + bound;
            default -> SECRET;
        };
        try (TransportHttpTarget target = TransportHttpTarget.start(exchange -> {
            TransportHttpTarget.respond(exchange, credential.equals(exchange.getRequestHeaders().getFirst(header)) ? 200 : 401,
                    "{\"token\":\"" + SECRET + "\"}");
        }); McpServerProcess server = start(workspace)) {
            saveContext(server, context(workspace, "credentials"));
            ObjectNode plan = generatedPlan(target.url(""));
            authenticated(plan, kind);
            packs(plan, "web-api-core");
            save(server, "credentials", plan);
            assertBlocked(run(server, "credentials", "missing"), "security_runtime_credentials_unavailable");
            target.assertNoRequest();
            assertRun(server, workspace, "credentials", "missing", "blocked");
            Files.writeString(workspace.resolve(".env"), "SECURITY_IT_TOKEN=" + bound + "\n");
            JsonNode result = run(server, "credentials", "resolved");
            completed(result, "pass", 2);
            assertThat(credential.equals(target.received().header(header))).isTrue();
            target.assertNoRequest();
            assertRun(server, workspace, "credentials", "resolved", "pass");
            assertThat(server.stdoutLines().toString()).doesNotContain(SECRET);
            assertThat(server.stderrText()).doesNotContain(SECRET);
            JavaItScenario.observe(OPERATION, "ASSERTED", "project_credential_" + variant + "_missing_then_resolved");
        }
    }

    @Test
    void customCasesPreserveRequestFieldsExplicitOutcomesAndForeignResponseFindings() throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (TransportHttpTarget target = TransportHttpTarget.start(exchange -> {
            String query = exchange.getRequestURI().getQuery();
            int status = query != null && query.contains("denied") ? 403 : query != null && query.contains("error") ? 503 : 200;
            TransportHttpTarget.respond(exchange, status, "{\"safe\":true}");
        }); McpServerProcess server = start(workspace)) {
            saveContext(server, context(workspace, "cases"));
            ObjectNode plan = generatedPlan(target.url(""));
            packs(plan, "web-api-core");
            entry(plan).withObject("transport").put("method", "POST");
            for (String name : List.of("denied", "error", "allowed", "foreign")) {
                ObjectNode custom = custom(plan, "customCases", name,
                        name.equals("foreign") ? "deny" : name.equals("allowed") ? "allow" : name.equals("error") ? "error" : "deny");
                custom.withObject("baseline").putObject("queryParameters").put("q", "baseline");
                ObjectNode attack = custom.withObject("attack");
                attack.putObject("query").put("q", name);
                attack.putObject("headers").put("X-Scenario", name);
                attack.putObject("body").put("safe", name);
                attack.withObject("expect").putArray("statusCodes").add(name.equals("denied") ? 403 : name.equals("error") ? 503 : 200);
            }
            save(server, "cases", plan);
            JsonNode result = run(server, "cases", "cases");
            completed(result, "fail", 5);
            assertThat(target.received().method()).isEqualTo("POST");
            for (String name : List.of("denied", "error", "allowed", "foreign")) {
                assertThat(target.received().uri()).contains("q=baseline");
                TransportHttpTarget.Received attack = target.received();
                assertThat(attack.uri()).contains("q=" + name);
                assertThat(attack.header("X-Scenario")).isEqualTo(name);
                assertThat(JSON.readTree(attack.body()).path("safe").asText()).isEqualTo(name);
            }
            target.assertNoRequest();
            JsonNode cases = result.path("details").path("cases");
            for (int i = 1; i < 4; i++) {
                assertThat(cases.get(i).path("outcome").asText()).isEqualTo("passed");
                assertThat(cases.get(i).path("evidenceRefIds")).hasSize(2);
            }
            assertThat(cases.get(4).path("outcome").asText()).isEqualTo("confirmed");
            assertThat(result.path("details").path("findings")).hasSize(1);
            assertRun(server, workspace, "cases", "cases", "fail");
        }
    }

    @Test
    void prePlanRefreshReloadsCredentialsAndFailedRefreshNeverExecutesTheTarget() throws Exception {
        Path workspace = JavaItScenario.workspace();
        boolean windows = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win");
        Path script = workspace.resolve(windows ? "refresh.ps1" : "refresh.sh");
        Files.writeString(script, windows ? "[Console]::Out.Write(('x' * 1048576)); [Console]::Error.Write(('y' * 1048576))\n"
                + "Set-Content -LiteralPath '.env' -Value 'SECURITY_IT_TOKEN=" + SECRET + "'\n"
                : "printf '%01048576d' 0; printf '%01048576d' 0 >&2\n"
                + "printf '%s\\n' 'SECURITY_IT_TOKEN=" + SECRET + "' > .env\n");
        try (TransportHttpTarget target = TransportHttpTarget.start(); McpServerProcess server = start(workspace)) {
            ObjectNode project = context(workspace, "refresh");
            ObjectNode root = (ObjectNode) project.path("workspaces").get(0);
            ObjectNode refresh = root.putArray("scripts").addObject().put("name", "refresh").put("phase", "prePlan")
                    .put("command", windows ? "ps" : "sh");
            if (windows) {
                refresh.putArray("args").add("-NoProfile").add("-NonInteractive").add("-ExecutionPolicy").add("Bypass")
                        .add("-File").add("./refresh.ps1");
            } else {
                refresh.putArray("args").add("./refresh.sh");
            }
            ((ObjectNode) root.path("executionProfiles").get(0)).putArray("scriptRefs").addObject()
                    .put("name", "refresh").put("phase", "prePlan");
            saveContext(server, project);
            ObjectNode plan = generatedPlan(target.url(""));
            authenticated(plan, "bearer");
            packs(plan, "web-api-core");
            save(server, "refresh", plan);
            completed(run(server, "refresh", "refreshed"), "pass", 2);
            assertThat(target.received().header("Authorization").equals("Bearer " + SECRET)).isTrue();
            target.assertNoRequest();
            assertThat(Files.readString(workspace.resolve(".env")).contains(SECRET)).isTrue();
            assertThat(server.stdoutLines().toString()).doesNotContain("xxxxxxxxxxxxxxxx");
            assertThat(server.stderrText()).doesNotContain("yyyyyyyyyyyyyyyy");
            assertRun(server, workspace, "refresh", "refreshed", "pass");
            Files.writeString(script, "exit 7\n");
            assertBlocked(run(server, "refresh", "failed"), "security_credential_refresh_failed");
            target.assertNoRequest();
            assertRun(server, workspace, "refresh", "failed", "blocked");
            assertThat(server.stdoutLines().toString()).doesNotContain(SECRET);
            assertThat(server.stderrText()).doesNotContain(SECRET);
        }
    }
}
