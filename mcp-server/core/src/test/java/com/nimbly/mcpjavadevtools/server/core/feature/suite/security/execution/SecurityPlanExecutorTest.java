package com.nimbly.mcpjavadevtools.server.core.feature.suite.security.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.ProbeFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResult;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.knowledge.SecurityKnowledgeCatalog;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.TransportExecutionFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecurityPlanExecutorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final TransportExecutionFeature transport = request -> {
        String url = String.valueOf(((ExecuteTransportRequest) request).request().get("url"));
        int status = url.contains("__mcp_security_rule=") ? 403 : 200;
        return ExecuteTransportResult.httpResponse(status < 400 ? "pass" : "fail_http", "http", status, Map.of(), "", 1);
    };

    @Test
    void executesADeclaredAnonymousBlackboxEntrypointThroughWrappedTransport() throws Exception {
        var result = executor().execute(mapper.readTree(contract("127.0.0.1")));

        assertThat(result.status()).isEqualTo("completed");
        assertThat(result.details()).containsEntry("runStatus", "pass");
        assertThat(result.details().get("coverage").toString()).contains("complete=true");
    }

    @Test
    void blocksEntrypointsOutsideTheDeclaredTargetBoundary() throws Exception {
        var result = executor().execute(mapper.readTree(contract("localhost")));

        assertThat(result.status()).isEqualTo("blocked");
        assertThat(result.reasonCode()).isEqualTo("security_target_boundary_violation");
    }

    @Test
    void rejectsSidecarPlansWithoutTheRequiredRuntimeTargets() throws Exception {
        String sidecar = contract("127.0.0.1").replace("\"securityMode\":\"blackbox\"", "\"securityMode\":\"sidecar_assisted\"")
                .replace("\"authenticationProfiles\"", "\"attackProfiles\":[{\"id\":\"attack\"}],\"authenticationProfiles\"");

        var result = executor().execute(mapper.readTree(sidecar));

        assertThat(result.status()).isEqualTo("blocked");
        assertThat(result.reasonCode()).isEqualTo("security_contract_sidecar_runtime_target_required");
    }

    @Test
    void executesSidecarAttackAndReportsAnExternalBypassFinding() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        TransportExecutionFeature bypassingTransport = request -> {
            requests.incrementAndGet();
            return ExecuteTransportResult.httpResponse("pass", "http", 200, java.util.Map.of(), "", 1);
        };

        var result = executor(bypassingTransport, request -> ProbeResult.success()).execute(mapper.readTree(sidecarContract()));

        assertThat(result.status()).isEqualTo("completed");
        assertThat(result.details()).containsEntry("runStatus", "fail");
        assertThat(result.details().get("findings").toString()).contains("corroborated_external");
        assertThat(requests).hasValue(2);
    }

    @Test
    void executesBoundedBlackboxCustomCasesThroughWrappedTransport() throws Exception {
        String blackbox = contract("127.0.0.1").replace("}]}",
                "}],\"customCases\":[{\"id\":\"idor\",\"category\":\"authorization\",\"entrypointRef\":\"health\","
                        + "\"authenticationProfileRef\":\"anonymous\",\"baseline\":{\"expect\":{\"outcome\":\"allow\"}},"
                        + "\"attack\":{\"expect\":{\"outcome\":\"deny\"}}}]}");

        var result = executor().execute(mapper.readTree(blackbox));

        assertThat(result.status()).isEqualTo("completed");
        assertThat(result.details()).containsEntry("runStatus", "fail");
        assertThat(result.details().get("findings").toString()).contains("finding-idor");
    }

    @Test
    void expandsDefaultKnowledgeRulesIntoBoundedMutationCases() throws Exception {
        var result = executor().execute(mapper.readTree(contract("127.0.0.1")));

        assertThat(result.details().get("cases").toString()).contains("security_generated_rule_not_applicable");
        assertThat(result.details().get("coverage").toString()).contains("plannedCount=10");
    }

    @Test
    void appliesGeneratedRulesAtTheirDeclaredRequestBoundaries() throws Exception {
        List<Map<String, Object>> requests = new CopyOnWriteArrayList<>();
        TransportExecutionFeature recording = request -> {
            requests.add(((ExecuteTransportRequest) request).request());
            return ExecuteTransportResult.httpResponse("pass", "http", 200, Map.of(), "", 1);
        };

        var result = new SecurityPlanExecutor(recording, new SecurityKnowledgeCatalog()).execute(mapper.readTree(contract("127.0.0.1")));

        assertThat(result.details().get("findings")).isEqualTo(List.of());
        assertThat(requests).hasSize(1);
        assertThat(requests.toString()).doesNotContain("__mcp_security_rule");
    }

    @Test
    void confirmsAnAllowedApplicableCatalogMutationWithBoundedHttpEvidence() throws Exception {
        TransportExecutionFeature allowing = request -> ExecuteTransportResult.httpResponse(
                "pass", "http", 200, Map.of(), "", 1);
        String plan = """
                {"suiteType":"security","securityMode":"blackbox",
                 "targetBoundary":{"environment":"local-ci","baseUrl":"http://127.0.0.1:8080",
                 "allowedHosts":["127.0.0.1"],"allowedPorts":[8080],"fixtureContext":{"safeInput":"invalid"}},
                 "entrypoints":[{"id":"health","transport":{"type":"http","method":"GET","path":"/health"},
                 "baseline":{"query":{"q":"ok"}}}],"authenticationProfiles":[{"id":"anonymous","kind":"anonymous"}]}
                """;

        var result = new SecurityPlanExecutor(allowing, new SecurityKnowledgeCatalog()).execute(mapper.readTree(plan));

        assertThat(result.details()).containsEntry("runStatus", "fail");
        assertThat(result.details().get("findings").toString()).contains("web-api-core-http")
                .contains("confirmed");
        assertThat(result.details().get("cases").toString()).contains("http_response");
    }

    @Test
    void resolvesPinnedKnowledgePackReferencesToReviewedRules() {
        var selection = new SecurityKnowledgeCatalog().select(List.of("web-api-core@1.0.0"));

        assertThat(selection.available()).isTrue();
        assertThat(selection.snapshot().packs()).containsExactly("web-api-core@1.0.0");
        assertThat(new SecurityKnowledgeCatalog().generatedCases(selection)).hasSize(1);
    }

    @Test
    void injectsNonAnonymousRuntimeCredentialsWithoutReturningTheSecret() throws Exception {
        List<Map<String, Object>> payloads = new CopyOnWriteArrayList<>();
        TransportExecutionFeature authenticated = request -> {
            payloads.add(((ExecuteTransportRequest) request).request());
            return ExecuteTransportResult.httpResponse("pass", "http", 200, Map.of(), "", 1);
        };
        String contract = contract("127.0.0.1").replace("{\"id\":\"anonymous\",\"kind\":\"anonymous\"}",
                "{\"id\":\"operator\",\"kind\":\"bearer\",\"credentialRef\":\"operator-token\"}");
        var input = mapper.createObjectNode();
        input.set("contract", mapper.readTree(contract));
        String credential = System.getenv("PATH");
        assertThat(credential).isNotBlank();
        input.putObject("credentialBindings").put("operator-token", "PATH");

        var result = new SecurityPlanExecutor(authenticated, new SecurityKnowledgeCatalog()).execute(input);

        assertThat(result.status()).isEqualTo("completed");
        assertThat(payloads.toString()).contains("Bearer " + credential);
        assertThat(result.details().toString()).doesNotContain(credential);
    }

    @Test
    void resolvesNonAnonymousCredentialsFromTheDeclaredProjectEnvFile(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve(".env"), "SECURITY_TOKEN=dotenv-token\n");
        List<Map<String, Object>> payloads = new CopyOnWriteArrayList<>();
        TransportExecutionFeature authenticated = request -> {
            payloads.add(((ExecuteTransportRequest) request).request());
            return ExecuteTransportResult.httpResponse("pass", "http", 200, Map.of(), "", 1);
        };
        String contract = contract("127.0.0.1").replace("{\"id\":\"anonymous\",\"kind\":\"anonymous\"}",
                "{\"id\":\"operator\",\"kind\":\"bearer\",\"credentialRef\":\"operator-token\"}");
        var input = mapper.createObjectNode();
        input.set("contract", mapper.readTree(contract));
        input.putObject("credentialBindings").put("operator-token", "SECURITY_TOKEN");
        input.putObject("credentialSource").put("workspaceRoot", workspace.toString()).put("envFile", ".env");

        var result = new SecurityPlanExecutor(authenticated, new SecurityKnowledgeCatalog()).execute(input);

        assertThat(result.status()).isEqualTo("completed");
        assertThat(payloads.toString()).contains("Bearer dotenv-token");
        assertThat(result.details().toString()).doesNotContain("dotenv-token");
    }

    private SecurityPlanExecutor executor() {
        return new SecurityPlanExecutor(transport, new SecurityKnowledgeCatalog());
    }

    private static SecurityPlanExecutor executor(TransportExecutionFeature transport, ProbeFeature probe) {
        return new SecurityPlanExecutor(transport, new SecurityKnowledgeCatalog(), probe);
    }

    private static String contract(String host) {
        return """
                {"suiteType":"security","securityMode":"blackbox",
                 "targetBoundary":{"environment":"local-ci","baseUrl":"http://%s:8080",
                 "allowedHosts":["127.0.0.1"],"allowedPorts":[8080]},
                 "entrypoints":[{"id":"health","transport":{"type":"http","method":"GET","path":"/health"}}],
                 "authenticationProfiles":[{"id":"anonymous","kind":"anonymous"}]}
                """.formatted(host);
    }

    private static String sidecarContract() {
        return """
                {"suiteType":"security","securityMode":"sidecar_assisted",
                 "targetBoundary":{"environment":"local-ci","baseUrl":"http://127.0.0.1:8080",
                 "allowedHosts":["127.0.0.1"],"allowedPorts":[8080]},
                 "entrypoints":[{"id":"orders","transport":{"type":"http","method":"GET","path":"/orders"}}],
                 "authenticationProfiles":[{"id":"anonymous","kind":"anonymous"}],
                 "runtimeTargets":[{"id":"controller","entrypointRef":"orders","probeId":"local",
                 "strictLineKey":"example.OrderController#get:10"}],
                 "attackProfiles":[{"id":"idor","category":"authorization","entrypointRef":"orders",
                 "authenticationProfileRef":"anonymous","baseline":{"expect":{"outcome":"allow",
                 "mustHitRuntimeTargets":["controller"]}},"attack":{"expect":{"outcome":"deny",
                 "mustHitRuntimeTargets":["controller"]}}}]}
                """;
    }
}
