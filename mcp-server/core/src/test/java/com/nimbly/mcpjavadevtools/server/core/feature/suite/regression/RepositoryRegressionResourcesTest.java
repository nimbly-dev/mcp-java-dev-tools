package com.nimbly.mcpjavadevtools.server.core.feature.suite.regression;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.preflight.RegressionPlanPreflight;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Validates Skill template readiness and records the guide's outstanding preflight boundary. */
class RepositoryRegressionResourcesTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void bundledCrafterTemplatePassesTheCurrentPreflight() throws Exception {
        Path templates = root().resolve(
                "skills/mcp-java-dev-tools-regression-plan-crafter/references/templates");
        assertReady(read(templates.resolve("metadata.template.json")),
                read(templates.resolve("contract.template.json")));
    }

    @Test
    void legacyGuideCorrelationCannotBeMistakenForAnExecutablePlan() throws Exception {
        // #665 owns updating the guide's old correlation shape. Artifact validity
        // alone must not be presented as proof of executable Java preflight readiness.
        Path producer = root().resolve("docs/guides/complete-project-example/"
                + ".mcpjvm/example-project/plans/regression/producer");
        var result = new RegressionPlanPreflight().validate(input(
                read(producer.resolve("metadata.json")), read(producer.resolve("contract.json"))));
        assertThat(result.status()).isEqualTo("blocked_invalid");
        assertThat(result.reasonCode()).isEqualTo("correlation_configuration_invalid");
    }

    @ParameterizedTest(name = "guide semantic preflight: {0}")
    @ValueSource(strings = {
            ".mcpjvm/example-project/plans/regression/producer/contract.json",
            ".mcpjvm/example-project/plans/regression/consumer/contract.json",
            "examples/watcher-eventual-field.json", "examples/suite-context-cross-plan.json",
            "examples/correlation-json-path.json"})
    void guideStepsAndExternalVerificationRetainSemanticValidation(String resource) throws Exception {
        ObjectNode input = guideInput(resource);
        assertThat(input.path("metadata").path("execution").path("intent").asText()).isEqualTo("regression");
        assertReady(input.path("metadata"), input.path("contract"));
    }

    @ParameterizedTest(name = "guide semantic rejection: {0}")
    @ValueSource(strings = {"expectation", "condition", "extraction", "external_verification"})
    void guideSemanticChecksRejectCorruptionRatherThanOnlyAcceptingHttp(String mutation) throws Exception {
        String plan = "external_verification".equals(mutation) ? "consumer" : "producer";
        ObjectNode input = guideInput(".mcpjvm/example-project/plans/regression/" + plan + "/contract.json");
        assertReady(input.path("metadata"), input.path("contract"));
        ObjectNode step = (ObjectNode) input.path("contract").path("steps").get(0);
        String expectedReason = switch (mutation) {
            case "expectation" -> {
                ((ObjectNode) step.path("expect").get(0)).put("operator", "unsupported");
                yield "step_expectation_invalid";
            }
            case "condition" -> {
                step.set("when", mapper.valueToTree(Map.of("left", "step[1].status", "op", "exists")));
                yield "step_condition_forward_reference";
            }
            case "extraction" -> {
                ((ObjectNode) step.path("extract").get(0)).put("secret", true);
                yield "suite_context_secret_forbidden";
            }
            case "external_verification" -> {
                ((ObjectNode) input.path("contract").path("externalVerification").get(0).path("provider"))
                        .put("type", "unsupported");
                yield "external_verification_request_invalid";
            }
            default -> throw new IllegalArgumentException(mutation);
        };
        var result = new RegressionPlanPreflight().validate(input);
        assertThat(result.status()).isEqualTo("blocked_invalid");
        assertThat(result.reasonCode()).isEqualTo(expectedReason);
    }

    private ObjectNode guideInput(String resource) throws Exception {
        Path guide = root().resolve("docs/guides/complete-project-example");
        JsonNode metadata = read(guide.resolve(".mcpjvm/example-project/plans/regression/"
                + (resource.contains("/consumer/") ? "consumer" : "producer") + "/metadata.json"));
        ObjectNode contract = (ObjectNode) read(guide.resolve(resource));
        // The removed test validated step/external semantics, not runtime Watcher policy.
        // Keep #665's oversized Watcher timeout and incomplete correlation from masking
        // those independent assertions. Correlation shape and suite/transport references
        // are checked separately in complete_project_example.test.cjs.
        contract.remove(java.util.List.of("watchers", "correlation"));
        return input(metadata, contract);
    }

    private void assertReady(JsonNode metadata, JsonNode contract) {
        var result = new RegressionPlanPreflight().validate(input(metadata, contract));
        assertThat(result.status()).as("%s: %s", result.reasonCode(), result.nextAction())
                .isEqualTo("ready");
        assertThat(result.reasonCode()).isEqualTo("ok");
    }

    private ObjectNode input(JsonNode metadata, JsonNode contract) {
        ObjectNode input = mapper.createObjectNode();
        input.set("metadata", metadata);
        input.set("contract", contract);
        input.set("providedContext", mapper.valueToTree(Map.of(
                "apiBaseUrl", "http://127.0.0.1:9191", "auth.bearer", "runtime-token",
                "eventId", "fixture-event", "suite.eventId", "fixture-event")));
        return input;
    }

    private JsonNode read(Path path) throws Exception {
        return mapper.readTree(path.toFile());
    }

    private Path root() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.isRegularFile(current.resolve("mcp-server/pom.xml"))) {
            current = current.getParent();
        }
        assertThat(current).as("repository root").isNotNull();
        return current;
    }
}
