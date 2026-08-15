package com.nimbly.mcpjavadevtools.server.mcp.tools.routesynthesis;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.DefaultRouteSynthesisFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.RouteSynthesisFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.RouteSynthesisActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.impl.ClassMethodsAction;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.impl.CreateRecipeAction;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.impl.DiscoverHandlersAction;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.impl.InferTargetAction;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.authentication.DefaultRouteSynthesisAuthenticationResolver;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.discovery.FileSystemJavaSourceDiscovery;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.discovery.RouteSynthesisHandlerDiscovery;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.discovery.SpringHttpHandlerDiscovery;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.RouteSynthesisAction;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.classmethods.ClassMethodsRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.createrecipe.CreateRecipeRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.createrecipe.SynthesizerSelection;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.discoverhandlers.DiscoverHandlersRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.infertarget.InferTargetRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.request.RouteSynthesisRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.result.RouteSynthesisResult;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.ranking.DeterministicRouteTargetRanker;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.routing.RouteSynthesisProbeRouteResolution;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.runtime.RouteSynthesisRuntimeLineResolution;
import com.nimbly.mcpjavadevtools.server.core.synthesis.registry.DefaultSynthesizerRegistry;
import com.nimbly.mcpjavadevtools.server.core.synthesis.registry.SynthesizerRegistry;
import com.nimbly.mcpjavadevtools.server.core.synthesis.springhttp.SpringHttpSynthesizer;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.workspace.RouteSynthesisWorkspaceSnapshot;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.workspace.RouteSynthesisWorkspaceProvider;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Executes the Java action graph against the checked-in TypeScript-derived
 * compatibility expectations.
 */
class RouteSynthesisMcpTypeScriptParityFixtureTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> ACTIONS = Set.of(
            "infer_target", "class_methods", "discover_handlers", "create_recipe");

    @TempDir
    Path tempDirectory;

    @Test
    void executesJavaAgainstTheTypeScriptDerivedParityMatrix() throws Exception {
        JsonNode fixture = loadFixture();
        assertThat(fixture.path("contract").asText())
                .isEqualTo("typescript-route-synthesis-action-parity-v1");
        assertThat(fixture.path("sourceReferences").size()).isGreaterThanOrEqualTo(5);
        Set<String> coveredActions = new HashSet<>();
        for (JsonNode testCase : fixture.path("cases")) {
            coveredActions.add(testCase.path("action").asText());
            runCase(testCase);
        }
        assertThat(coveredActions).containsExactlyInAnyOrderElementsOf(ACTIONS);
        assertThat(fixture.path("cases").size()).isGreaterThanOrEqualTo(19);
    }

    private JsonNode loadFixture() throws IOException {
        try (InputStream stream = getClass().getResourceAsStream(
                "/route-synthesis/route-synthesis-typescript-java-parity.json")) {
            assertThat(stream).isNotNull();
            return JSON.readTree(stream);
        }
    }

    private void runCase(JsonNode testCase) throws Exception {
        String name = testCase.path("name").asText();
        Path workspace = tempDirectory.resolve(name.replace(' ', '_'));
        writeFixtureProject(workspace, name.contains("ambiguity") || name.contains("ambiguous class"));
        RouteSynthesisFeature feature = featureFor(workspace, name);
        RouteSynthesisRequest coreRequest = request(
                testCase.path("action").asText(), testCase.path("input"));
        RouteSynthesisResult actual = feature.execute(coreRequest);
        compareExpected(testCase, actual);
        assertRedaction(testCase, actual);
        if (testCase.has("ordering")) {
            RouteSynthesisResult repeat = feature.execute(coreRequest);
            assertThat(JSON.writeValueAsString(actual.actionResult()))
                    .isEqualTo(JSON.writeValueAsString(repeat.actionResult()));
        }
    }

    private RouteSynthesisFeature featureFor(Path workspace, String name) {
        boolean workspaceFailure = name.contains("workspace failure")
                || name.contains("missing workspace");
        RouteSynthesisWorkspaceSnapshot snapshot = new RouteSynthesisWorkspaceSnapshot(workspace);
        RouteSynthesisWorkspaceProvider workspaceProvider;
        if (workspaceFailure) {
            workspaceProvider = Optional::empty;
        } else {
            workspaceProvider = () -> Optional.of(snapshot);
        }
        var sourceDiscovery = new FileSystemJavaSourceDiscovery(snapshot);
        RouteSynthesisHandlerDiscovery handlerDiscovery = new SpringHttpHandlerDiscovery(sourceDiscovery);
        var routeResolver = (com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.routing
                .RouteSynthesisProbeRouteResolver) (probeId, baseUrl) ->
                RouteSynthesisProbeRouteResolution.resolved("http://probe.example");
        var runtime = (com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.runtime
                .RouteSynthesisRuntimeEvidenceProvider) (key, start, end, route) ->
                runtimeEvidence(name, start);
        InferTargetAction infer = new InferTargetAction(
                workspaceProvider, sourceDiscovery, routeResolver, runtime,
                new DeterministicRouteTargetRanker());
        ClassMethodsAction methods = new ClassMethodsAction(
                workspaceProvider, sourceDiscovery, routeResolver, runtime);
        DiscoverHandlersAction handlers = new DiscoverHandlersAction(
                workspaceProvider, handlerDiscovery, routeResolver, runtime);
        CreateRecipeAction recipe = new CreateRecipeAction(
                workspaceProvider, handlerDiscovery, new DefaultRouteSynthesisAuthenticationResolver(),
                registry(name));
        List<RouteSynthesisActionHandler> actions = List.of(infer, methods, handlers, recipe);
        return new DefaultRouteSynthesisFeature(actions);
    }

    private RouteSynthesisRuntimeLineResolution runtimeEvidence(String name, int startLine) {
        if (name.contains("line-resolution failure")
                || name.contains("partial line validation")
                || name.contains("unresolved runtime line")) {
            return RouteSynthesisRuntimeLineResolution.unresolved("runtime_line_unresolved");
        }
        return RouteSynthesisRuntimeLineResolution.resolved(startLine);
    }

    private SynthesizerRegistry registry(String name) {
        if (name.contains("external-plugin blocker")) {
            return new DefaultSynthesizerRegistry(new SpringHttpSynthesizer(), 2);
        }
        if (name.contains("no compatible Synthesizer")) {
            return request -> new SynthesizerSelection(false, false, 0, "synthesizer_not_installed");
        }
        return new DefaultSynthesizerRegistry(new SpringHttpSynthesizer(), 0);
    }

    private RouteSynthesisRequest request(String action, JsonNode input) {
        RouteSynthesisAction selected = RouteSynthesisAction.fromValue(action).orElseThrow();
        List<String> roots = strings(input.path("additionalSourceRoots"));
        String project = input.path("projectRootAbs").asText(null);
        String classHint = input.path("classHint").asText(null);
        return switch (selected) {
            case INFER_TARGET -> new InferTargetRequest(
                    project, roots, classHint, input.path("methodHint").asText(null),
                    integer(input, "lineHint"), integer(input, "maxCandidates"),
                    input.path("probeId").asText(null), input.path("probeBaseUrl").asText(null));
            case CLASS_METHODS -> new ClassMethodsRequest(
                    project, roots, classHint, input.path("probeId").asText(null),
                    input.path("probeBaseUrl").asText(null));
            case DISCOVER_HANDLERS -> new DiscoverHandlersRequest(
                    project, roots, classHint, input.path("probeId").asText(null),
                    input.path("probeBaseUrl").asText(null));
            case CREATE_RECIPE -> new CreateRecipeRequest(
                    project, roots, classHint, input.path("methodHint").asText(null),
                    integer(input, "lineHint"), input.path("mappingsBaseUrl").asText(null),
                    input.path("discoveryPreference").asText(null), input.path("apiBasePath").asText(null),
                    input.path("intentMode").asText(null), input.path("authToken").asText(null),
                    input.path("authUsername").asText(null), input.path("authPassword").asText(null),
                    booleanValue(input, "actuationEnabled"), booleanValue(input, "actuationReturnBoolean"),
                    input.path("actuationActuatorId").asText(null), input.path("outputTemplate").asText(null),
                    input.path("probeId").asText(null),
                    input.path("probeBaseUrl").asText(null));
        };
    }

    private void compareExpected(JsonNode testCase, RouteSynthesisResult actual) {
        JsonNode expected = testCase.path("expected");
        String name = testCase.path("name").asText();
        assertThat(actual.resultType()).as(name)
                .isEqualTo(expected.path("resultType").asText());
        assertThat(actual.status()).as(name)
                .isEqualTo(expected.path("status").asText());
        assertEnvelopeShape(name, actual);
        compareOptional(expected, "reasonCode", actual.reasonCode());
        compareOptional(expected, "failedStep", actual.failedStep());
        compareOptional(expected, "nextActionCode", actual.nextActionCode());
        compareDefaults(testCase, actual);
        assertCompleteActionShape(name, testCase.path("action").asText(), actual.actionResult());
        if (expected.has("framework")) {
            assertThat(actual.actionResult()).isNotNull();
            String framework = JSON.convertValue(actual.actionResult(), JsonNode.class)
                    .path("framework").asText();
            assertThat(framework).isEqualTo(expected.path("framework").asText());
        }
        if (expected.has("auth.status")) {
            JsonNode output = JSON.valueToTree(actual.actionResult());
            assertThat(output.path("auth").path("status").asText())
                    .isEqualTo(expected.path("auth.status").asText());
            assertThat(output.path("auth").path("strategy").asText())
                    .isEqualTo(expected.path("auth.strategy").asText());
        }
        if (testCase.has("evidence")) {
            assertThat(actual.evidence()).containsExactlyElementsOf(strings(testCase.path("evidence")));
        }
        if (expected.has("attemptedStrategies")) {
            assertThat(actual.attemptedStrategies())
                    .containsExactlyElementsOf(strings(expected.path("attemptedStrategies")));
        }
        if (expected.has("shapeAssertions")) {
            JsonNode output = JSON.valueToTree(actual.actionResult());
            for (JsonNode assertion : expected.path("shapeAssertions")) {
                JsonNode value = nodeAtPath(output, assertion.path("path").asText());
                String description = testCase.path("name").asText()
                        + " shape " + assertion.path("path").asText();
                assertThat(value).as(description).isNotNull();
                if (assertion.has("equals")) {
                    assertThat(value).as(description)
                            .isEqualTo(assertion.path("equals"));
                }
                if (assertion.has("size")) {
                    assertThat(value.isArray()).as(description).isTrue();
                    assertThat(value.size()).as(description)
                            .isEqualTo(assertion.path("size").asInt());
                }
            }
        }
    }

    private void assertEnvelopeShape(String name, RouteSynthesisResult actual) {
        assertThat(actual.resultType()).as(name + " resultType").isNotBlank();
        assertThat(actual.status()).as(name + " status").isNotBlank();
        assertThat(actual.evidence()).as(name + " evidence").isNotNull();
        assertThat(actual.attemptedStrategies()).as(name + " attemptedStrategies").isNotNull();
    }

    private void compareDefaults(JsonNode testCase, RouteSynthesisResult actual) {
        JsonNode defaults = testCase.path("defaults");
        if (!defaults.isObject() || actual.actionResult() == null) {
            return;
        }
        JsonNode output = JSON.valueToTree(actual.actionResult());
        if (defaults.has("discoveryMode")) {
            assertThat(output.path("hints").path("discoveryMode"))
                    .as(testCase.path("name").asText() + " discovery default")
                    .isEqualTo(defaults.path("discoveryMode"));
        }
        if (defaults.has("maxCandidates")) {
            assertThat(output.path("candidates").isArray())
                    .as(testCase.path("name").asText() + " maxCandidates output")
                    .isTrue();
            assertThat(output.path("candidates").size())
                    .as(testCase.path("name").asText() + " maxCandidates default")
                    .isLessThanOrEqualTo(defaults.path("maxCandidates").asInt());
        }
    }

    private void assertCompleteActionShape(String name, String action, Object actionResult) {
        if (actionResult == null) {
            return;
        }
        JsonNode output = JSON.valueToTree(actionResult);
        assertFields(name, output, "projectRootAbs", "hints", "additionalSourceRoots");
        switch (action) {
            case "infer_target" -> {
                assertFields(name, output, "scannedJavaFiles", "candidates");
                assertFieldsOnArray(name, output.path("candidates"),
                        "file", "className", "fqcn", "methodName", "signature", "returnsBoolean",
                        "line", "declarationLine", "endLine", "firstExecutableLine", "lineSelectionStatus",
                        "lineSelectionSource", "key", "reasons");
            }
            case "class_methods" -> {
                assertFields(name, output, "scannedJavaFiles");
                if (output.has("target") && !output.path("target").isNull()) {
                    assertFields(name, output.path("target"), "file", "className", "fqcn", "methods");
                    assertFieldsOnArray(name, output.path("target").path("methods"),
                            "methodName", "signature", "startLine", "endLine", "firstExecutableLine",
                            "lineSelectionStatus", "lineSelectionSource", "probeKey");
                }
                if (output.has("matches")) {
                    assertFieldsOnArray(name, output.path("matches"), "file", "className", "fqcn");
                }
            }
            case "discover_handlers" -> {
                assertFields(name, output, "scannedJavaFiles", "framework", "controllerFqcn", "matchedTypeFile", "handlers",
                        "evidence", "attemptedStrategies");
                assertFieldsOnArray(name, output.path("handlers"),
                        "httpMethod", "path", "methodName", "signature", "runtimeClassFqcn",
                        "declarationLine", "endLine", "firstExecutableLine", "lineSelectionStatus",
                        "lineSelectionSource", "lineSelectionReasonCode", "strictLineKey");
            }
            case "create_recipe" -> {
                assertFields(name, output, "applicationType", "synthesizerUsed", "selectedHandler",
                        "requestCandidates", "executionPlan", "executionReadiness", "intentMode", "auth",
                        "evidence", "attemptedStrategies", "runtimeCapture", "rendered");
                assertFields(name, output.path("selectedHandler"),
                        "httpMethod", "path", "methodName", "signature", "runtimeClassFqcn",
                        "declarationLine", "endLine", "firstExecutableLine", "lineSelectionStatus",
                        "lineSelectionSource", "lineSelectionReasonCode", "strictLineKey");
                assertFieldsOnArray(name, output.path("requestCandidates"),
                        "method", "path", "queryTemplate", "fullUrlHint", "bodyTemplate", "assumptions",
                        "needsConfirmation", "rationale");
                assertFields(name, output.path("executionPlan"),
                        "selectedMode", "routingReason", "steps", "probeCallPlan");
                assertFieldsOnArray(name, output.path("executionPlan").path("steps"),
                        "phase", "title", "instruction");
                assertFields(name, output.path("executionPlan").path("probeCallPlan"),
                        "total", "verificationMethod", "actuated", "probeReset", "probeWaitForHit",
                        "probeGetStatus", "probeEnable", "byTool");
                assertFields(name, output.path("auth"), "status", "strategy", "missing", "headers", "source");
                assertFields(name, output.path("runtimeCapture"), "status", "reason", "lineValidation",
                        "lineResolvable", "captureId", "capturedAtEpoch", "executionPaths");
            }
            default -> throw new IllegalArgumentException("unsupported parity action: " + action);
        }
    }

    private void assertFields(String name, JsonNode object, String... fields) {
        for (String field : fields) {
            assertThat(object.has(field)).as(name + " missing field " + field).isTrue();
        }
    }

    private void assertFieldsOnArray(String name, JsonNode values, String... fields) {
        if (!values.isArray()) {
            return;
        }
        for (JsonNode value : values) {
            assertFields(name, value, fields);
        }
    }

    private void compareOptional(JsonNode expected, String field, String actual) {
        if (expected.has(field)) {
            assertThat(actual).isEqualTo(expected.path(field).asText());
        }
    }

    private void assertRedaction(JsonNode testCase, RouteSynthesisResult actual) throws IOException {
        if (!testCase.has("redaction")) {
            return;
        }
        String output = JSON.writeValueAsString(actual);
        for (JsonNode forbidden : testCase.path("redaction").path("forbiddenValues")) {
            assertThat(output).doesNotContain(forbidden.asText());
        }
        assertThat(output).contains("auto_resolved");
        assertThat(output).contains("bearer");
    }

    private void writeFixtureProject(Path workspace, boolean duplicate) throws IOException {
        Path project = workspace.resolve("fixture-project");
        Path source = project.resolve("src/main/java/example");
        Files.createDirectories(source);
        Files.writeString(source.resolve("Work.java"), """
                package example;
                public class Work {
                    public void before() {
                        int value = 1;
                    }
                    public void run() {
                        int value = 2;
                    }
                }
                """);
        Files.writeString(source.resolve("WorkController.java"), """
                package example;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RestController;
                @RestController
                @RequestMapping("/work")
                public class WorkController {
                    @GetMapping("/run")
                    public String run() {
                        return "ok";
                    }
                    @PostMapping("/save")
                    public String save() {
                        return "saved";
                    }
                }
                """);
        Files.writeString(source.resolve("UnsupportedController.java"), """
                package example;
                public class UnsupportedController {
                    public void run() {
                        int value = 1;
                    }
                }
                """);
        if (duplicate) {
            Path secondary = project.resolve("secondary-src/example");
            Files.createDirectories(secondary);
            Files.writeString(secondary.resolve("Work.java"), Files.readString(source.resolve("Work.java")));
        }
    }

    private List<String> strings(JsonNode values) {
        List<String> result = new ArrayList<>();
        if (values.isArray()) {
            for (JsonNode value : values) {
                result.add(value.asText());
            }
        }
        return result;
    }

    private Integer integer(JsonNode input, String field) {
        return input.has(field) && !input.path(field).isNull() ? input.path(field).asInt() : null;
    }

    private Boolean booleanValue(JsonNode input, String field) {
        return input.has(field) && !input.path(field).isNull() ? input.path(field).asBoolean() : null;
    }

    private JsonNode nodeAtPath(JsonNode root, String path) {
        JsonNode current = root;
        for (String segment : path.split("\\.")) {
            if (segment.chars().allMatch(Character::isDigit)) {
                current = current.path(Integer.parseInt(segment));
            } else {
                current = current.path(segment);
            }
        }
        return current.isMissingNode() ? null : current;
    }
}
