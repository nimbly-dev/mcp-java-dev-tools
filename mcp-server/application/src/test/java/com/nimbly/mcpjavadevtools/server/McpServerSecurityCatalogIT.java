package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
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
class McpServerSecurityCatalogIT extends SecurityMcpSupport {
    @Test
    void generatedQueryMutationReplacesAllExistingValuesInsteadOfManufacturingAFinding() throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (TransportHttpTarget target = TransportHttpTarget.start(exchange -> {
            String first = java.util.Arrays.stream(exchange.getRequestURI().getRawQuery().split("&"))
                    .filter(pair -> pair.startsWith("q=")).findFirst().orElse("");
            TransportHttpTarget.respond(exchange, first.equals("q=foreign") ? 403 : 200, "{}");
        }); McpServerProcess server = start(workspace)) {
            saveContext(server, context(workspace, "replace"));
            ObjectNode plan = generatedPlan(target.url(""));
            packs(plan, "web-api-core");
            contract(plan).withObject("targetBoundary").putObject("fixtureContext").put("safeInput", "foreign");
            entry(plan).withObject("transport").put("path", "/safe?keep=one%20two&q=own&q=duplicate");
            entry(plan).putObject("baseline").putObject("query").put("q", "own");
            save(server, "replace", plan);
            JsonNode result = run(server, "replace", "replace");
            completed(result, "pass", 2);
            assertThat(result.path("details").path("findings")).isEmpty();
            assertThat(result.path("details").path("cases").get(1).path("statusCode").asInt()).isEqualTo(403);
            assertThat(target.received().uri()).isEqualTo("/safe?keep=one%20two&q=own");
            assertThat(target.received().uri()).isEqualTo("/safe?keep=one%20two&q=own");
            assertThat(target.received().uri()).isEqualTo("/safe?keep=one%20two&q=foreign");
            target.assertNoRequest();
            JsonNode persisted = assertRun(server, workspace, "replace", "replace", "pass");
            assertThat(persisted.path("details")).isEqualTo(result.path("details"));
        }
    }

    @Test
    void customOverridesNeverExecuteUnrequestedCatalogMutations() throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (TransportHttpTarget target = TransportHttpTarget.start(); McpServerProcess server = start(workspace)) {
            saveContext(server, context(workspace, "override", "empty"));
            ObjectNode plan = generatedPlan(target.url(""));
            packs(plan, "web-api-core");
            contract(plan).withObject("targetBoundary").putObject("fixtureContext").put("safeInput", "foreign");
            entry(plan).putObject("baseline").putObject("query").put("q", "own");
            custom(plan, "customCases", "declared", "allow").withObject("attack").putObject("query").put("q", "declared");
            save(server, "override", plan);
            completed(run(server, "override", "override"), "pass", 2);
            assertThat(target.received().uri()).contains("q=own");
            assertThat(target.received().uri()).doesNotContain("foreign");
            assertThat(target.received().uri()).contains("q=declared");
            target.assertNoRequest();
            assertRun(server, workspace, "override", "override", "pass");
            contract(plan).putArray("customCases");
            save(server, "empty", plan);
            completed(run(server, "empty", "empty"), "pass", 1);
            assertThat(target.received().uri()).contains("q=own");
            target.assertNoRequest();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"bearer", "basic", "api_key", "custom"})
    void authenticationCatalogMutationRemovesEveryDeclaredCredentialHeader(String kind) throws Exception {
        Path workspace = JavaItScenario.workspace();
        String header = kind.equals("api_key") ? "X-API-Key" : kind.equals("custom") ? "X-Custom-Credential" : "Authorization";
        String credential = switch (kind) {
            case "bearer" -> "Bearer " + SECRET;
            case "basic" -> "Basic " + java.util.Base64.getEncoder().encodeToString(
                    ("actor:" + SECRET).getBytes(StandardCharsets.UTF_8));
            default -> SECRET;
        };
        Files.writeString(workspace.resolve(".env"), "SECURITY_IT_TOKEN=" + credential + "\n");
        try (TransportHttpTarget target = TransportHttpTarget.start(exchange -> {
            TransportHttpTarget.respond(exchange, credential.equals(exchange.getRequestHeaders().getFirst(header)) ? 200 : 401, "{}");
        }); McpServerProcess server = start(workspace)) {
            saveContext(server, context(workspace, "remove"));
            ObjectNode plan = generatedPlan(target.url(""));
            authenticated(plan, kind);
            packs(plan, "authentication-boundary");
            entry(plan).putObject("baseline").putObject("headers").put(header, "${actor-token}");
            save(server, "remove", plan);
            JsonNode result = run(server, "remove", "remove");
            completed(result, "pass", 2);
            for (int i = 0; i < 2; i++) {
                assertThat(credential.equals(target.received().header(header))).isTrue();
            }
            assertThat(target.received().header(header)).isNull();
            target.assertNoRequest();
            assertThat(result.path("details").path("cases").get(1).path("statusCode").asInt()).isEqualTo(401);
            assertRun(server, workspace, "remove", "remove", "pass");
            JavaItScenario.observe(OPERATION, "ASSERTED", "removed_credential_" + kind);
        }
    }

    @Test
    void actualMatrixMultipliesDeclaredEntrypointsAndAuthenticationProfiles() throws Exception {
        Path workspace = JavaItScenario.workspace();
        Files.writeString(workspace.resolve(".env"), "SECURITY_IT_TOKEN=" + SECRET + "\n");
        try (TransportHttpTarget target = TransportHttpTarget.start(); McpServerProcess server = start(workspace)) {
            saveContext(server, context(workspace, "matrix"));
            ObjectNode plan = generatedPlan(target.url(""));
            packs(plan, "web-api-core");
            ObjectNode second = entry(plan).deepCopy();
            second.put("id", "second");
            second.withObject("transport").put("path", "/second");
            contract(plan).withArray("entrypoints").add(second);
            contract(plan).withArray("authenticationProfiles").addObject().put("id", "authenticated")
                    .put("kind", "bearer").put("credentialRef", "actor-token");
            save(server, "matrix", plan);
            JsonNode result = run(server, "matrix", "matrix");
            completed(result, "pass", 8);
            assertThat(result.path("details").path("cases")).hasSize(8);
            java.util.Set<String> identities = new java.util.HashSet<>();
            for (JsonNode row : result.path("details").path("cases")) {
                assertThat(row.path("authenticationProfileRef").asText()).isIn("actor", "authenticated");
                assertThat(identities.add(row.path("caseId").asText())).as("each matrix case needs an unambiguous identity").isTrue();
            }
            for (String path : List.of("/safe", "/second")) {
                assertThat(target.received().uri()).isEqualTo(path);
                var authenticated = target.received();
                assertThat(authenticated.uri()).isEqualTo(path);
                assertThat(authenticated.header("Authorization").equals("Bearer " + SECRET)).isTrue();
            }
            target.assertNoRequest();
            assertRun(server, workspace, "matrix", "matrix", "pass");
        }
    }

    @Test
    @Tag("security-smoke")
    @Tag("smoke")
    void defaultReviewedMatrixAndImmutableSnapshotSurvivePlanChangeAndReplay() throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (TransportHttpTarget target = TransportHttpTarget.start(); McpServerProcess server = start(workspace)) {
            saveContext(server, context(workspace, "default", "override"));
            ObjectNode plan = generatedPlan(target.url(""));
            save(server, "default", plan);
            JsonNode result = run(server, "default", "default");
            completed(result, "pass", 10);
            assertThat(target.received().uri()).isEqualTo("/safe");
            target.assertNoRequest();
            JsonNode details = result.path("details");
            assertThat(details.path("knowledgeSnapshot").path("packs")).hasSize(9);
            assertThat(details.path("knowledgeSnapshot").path("digest").asText()).matches("[a-f0-9]{64}");
            assertThat(details.path("cases")).hasSize(10);
            assertThat(details.path("cases").get(0).path("statusCode").asInt()).isEqualTo(200);
            for (int i = 1; i < 10; i++) {
                assertThat(details.path("cases").get(i).path("outcome").asText()).isEqualTo("not_applicable");
            }
            assertThat(details.path("findings")).isEmpty();
            JsonNode persisted = assertRun(server, workspace, "default", "default", "pass");
            assertThat(persisted.path("details").path("knowledgeSnapshot")).isEqualTo(details.path("knowledgeSnapshot"));
            packs(plan, "web-api-core", "web-api-core@1.0.0", "web-api-core");
            save(server, "default", plan);
            assertThat(run(server, "default", "default")).isEqualTo(result);
            target.assertNoRequest();
            save(server, "override", plan);
            JsonNode override = run(server, "override", "override");
            completed(override, "pass", 2);
            assertThat(override.path("details").path("knowledgeSnapshot").path("packs"))
                    .isEqualTo(JSON.valueToTree(List.of("web-api-core@1.0.0")));
            assertThat(override.path("details").path("knowledgeSnapshot")).isNotEqualTo(details.path("knowledgeSnapshot"));
            assertThat(target.received().uri()).isEqualTo("/safe");
            target.assertNoRequest();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"web-api-core", "authentication-boundary", "authorization-idor", "authorization-cross-tenant",
            "deserialization-boundary", "file-upload-boundary", "input-injection-boundary", "path-traversal", "ssrf-boundary"})
    void everyReviewedRuleSendsItsActualMutationAndDistinguishesDenialFromFinding(String pack) throws Exception {
        Path workspace = JavaItScenario.workspace();
        Files.writeString(workspace.resolve(".env"), "SECURITY_IT_TOKEN=" + SECRET + "\n");
        try (TransportHttpTarget target = TransportHttpTarget.start(exchange -> {
            boolean baseline = exchange.getRequestURI().getPath().contains("own")
                    || "q=own".equals(exchange.getRequestURI().getQuery());
            if (pack.contains("authorization") || pack.equals("path-traversal")) {
                baseline = exchange.getRequestURI().getPath().contains("/own");
            }
            if (pack.equals("authentication-boundary")) {
                baseline = exchange.getRequestHeaders().containsKey("Authorization");
            } else if (pack.equals("deserialization-boundary") || pack.equals("file-upload-boundary")) {
                baseline = exchange.getRequestHeaders().getFirst("Content-Length") != null
                        && Integer.parseInt(exchange.getRequestHeaders().getFirst("Content-Length")) > 20;
            }
            int status = baseline || exchange.getRequestURI().getPath().startsWith("/allowed") ? 200 : deniedStatus(pack);
            TransportHttpTarget.respond(exchange, status, "{\"safe\":\"fixture\"}");
        }); McpServerProcess server = start(workspace)) {
            saveContext(server, context(workspace, "denied", "allowed"));
            ObjectNode plan = rulePlan(target.url(""), pack);
            save(server, "denied", plan);
            JsonNode denied = run(server, "denied", "denied");
            completed(denied, "pass", 2);
            assertMutation(target, pack);
            JsonNode mutation = denied.path("details").path("cases").get(1);
            assertThat(mutation.path("outcome").asText()).isEqualTo("passed");
            assertThat(mutation.path("knowledgePackRef").asText()).isEqualTo(pack + "@1.0.0");
            assertThat(mutation.path("statusCode").asInt()).isEqualTo(deniedStatus(pack));
            assertThat(mutation.path("evidenceRefIds")).hasSize(2);
            assertThat(mutation.path("evidence").get(1).path("statusCode").asInt()).isEqualTo(deniedStatus(pack));
            assertRun(server, workspace, "denied", "denied", "pass");
            ObjectNode transport = entry(plan).withObject("transport");
            transport.put("path", "/allowed" + transport.path("path").asText());
            save(server, "allowed", plan);
            JsonNode allowed = run(server, "allowed", "allowed");
            completed(allowed, "fail", 2);
            assertMutation(target, pack);
            JsonNode finding = allowed.path("details").path("findings").get(0);
            assertThat(finding.path("outcome").asText()).isEqualTo("confirmed");
            assertThat(finding.path("proofClassification").asText()).isEqualTo("external");
            assertThat(finding.path("cwe")).isNotEmpty();
            for (JsonNode cwe : finding.path("cwe")) {
                assertThat(cwe.asText()).matches("CWE-[0-9]+");
            }
            JsonNode evidence = allowed.path("details").path("cases").get(1).path("evidence");
            assertThat(evidence).hasSize(2);
            assertThat(evidence.get(0).path("phase").asText()).isEqualTo("baseline");
            assertThat(evidence.get(1).path("statusCode").asInt()).isEqualTo(200);
            assertRun(server, workspace, "allowed", "allowed", "fail");
            assertThat(server.stdoutLines().toString()).doesNotContain(SECRET);
            assertThat(server.stderrText()).doesNotContain(SECRET);
            JavaItScenario.observe(OPERATION, "ASSERTED", "reviewed_rule_" + pack + "_denied_and_confirmed");
        }
    }

    private static int deniedStatus(String pack) {
        return pack.equals("deserialization-boundary") || pack.equals("file-upload-boundary") ? 400 : 403;
    }

    private ObjectNode rulePlan(String url, String pack) {
        ObjectNode plan = generatedPlan(url);
        packs(plan, pack + "@1.0.0");
        authenticated(plan, "bearer");
        ObjectNode fixture = contract(plan).withObject("targetBoundary").putObject("fixtureContext");
        Map.of("safeInput", "foreign", "ownResourceId", "own", "foreignResourceId", "foreign", "tenantA", "a",
                "tenantB", "b", "safeSerializedValue", "foreign", "safeUploadContent", "foreign",
                "invalidInput", "foreign", "foreignPath", "foreign").forEach(fixture::put);
        fixture.put("localCallbackUrl", "foreign").put("allowListedHost", "127.0.0.1");
        ObjectNode baseline = entry(plan).putObject("baseline");
        baseline.putObject("query").put("q", "own");
        if (pack.contains("authorization") || pack.equals("path-traversal")) {
            entry(plan).withObject("transport").put("path", "/resource/{id}");
            baseline.putObject("pathParameters").put("id", "own");
        } else if (pack.equals("deserialization-boundary") || pack.equals("file-upload-boundary")) {
            entry(plan).withObject("transport").put("method", "POST");
            baseline.remove("query");
            baseline.putObject("body").put("safe", "baseline-content-long-enough");
        }
        return plan;
    }

    private void assertMutation(TransportHttpTarget target, String pack) throws Exception {
        assertThat(target.received().header("Authorization").equals("Bearer " + SECRET)).isTrue();
        assertThat(target.received().header("Authorization").equals("Bearer " + SECRET)).isTrue();
        TransportHttpTarget.Received attack = target.received();
        if (pack.equals("authentication-boundary")) {
            assertThat(attack.header("Authorization")).isNull();
        } else if (pack.contains("authorization") || pack.equals("path-traversal")) {
            assertThat(java.net.URI.create(attack.uri()).getPath()).contains("/foreign").doesNotContain("{", "own");
        } else if (pack.equals("deserialization-boundary") || pack.equals("file-upload-boundary")) {
            assertThat(attack.method()).isEqualTo("POST");
            assertThat(attack.body()).isEqualTo("foreign");
        } else {
            assertThat(URLDecoder.decode(URIQuery(attack.uri()), StandardCharsets.UTF_8)).isEqualTo("q=foreign");
        }
        target.assertNoRequest();
    }

    private String URIQuery(String uri) {
        return java.net.URI.create(uri).getRawQuery();
    }
}
