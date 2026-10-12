package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Route-specific inputs over the existing owned fixture/STDIO/Probe scenario. */
class RouteSynthesisScenario implements AutoCloseable {
    static final String TAGS = "com.example.social.post.app.controller.TagLifecycleController";
    final JvmProbeScenario runtime;
    final Path sources;

    private RouteSynthesisScenario(JvmProbeScenario runtime, Path sources) {
        this.runtime = runtime;
        this.sources = sources;
    }

    static RouteSynthesisScenario start(String... javaArguments) throws Exception {
        JvmProbeScenario runtime = JvmProbeScenario.start(javaArguments);
        try {
            Path sources = Files.createDirectories(runtime.workspace.resolve("sources"));
            Path original = McpServerStdioSupport.repositoryRoot().resolve(JvmProbeScenario.SOURCE);
            copySources(original, sources);
            return new RouteSynthesisScenario(runtime, sources);
        } catch (Throwable failure) {
            runtime.close();
            throw failure;
        }
    }

    private static void copySources(Path original, Path destination) throws Exception {
        try (var files = Files.walk(original)) {
            for (Path source : files.filter(Files::isRegularFile).toList()) {
                Path copy = destination.resolve(original.relativize(source));
                Files.createDirectories(copy.getParent());
                Files.copy(source, copy);
            }
        }
    }

    Map<String, Object> arguments(String classHint) {
        return new LinkedHashMap<>(Map.of("projectRootAbs", sources.toString(),
                "classHint", classHint, "probeBaseUrl", runtime.baseUrl));
    }

    Map<String, Object> recipeArguments(String classHint, String method) {
        Map<String, Object> arguments = arguments(classHint);
        arguments.put("methodHint", method);
        arguments.put("intentMode", "regression");
        return arguments;
    }

    JsonNode result(String action, Map<String, Object> arguments) throws Exception {
        return runtime.result("route_synthesis." + action, arguments, "create_recipe".equals(action));
    }

    JsonNode recipe(Map<String, Object> arguments) throws Exception {
        JsonNode result = result("create_recipe", arguments);
        assertThat(result.path("resultType").asText()).as(result.toPrettyString()).isEqualTo("recipe");
        assertThat(result.path("status").asText()).isEqualTo("ready");
        assertThat(result.path("actionResult").path("synthesizerUsed").asText()).isEqualTo("spring");
        return result.path("actionResult");
    }

    static int line(String className, String method, String snippet) throws Exception {
        String key = JvmProbeScenario.key(className, method, snippet);
        return Integer.parseInt(key.substring(key.lastIndexOf(':') + 1));
    }

    @Override
    public void close() throws Exception {
        runtime.close();
    }
}
