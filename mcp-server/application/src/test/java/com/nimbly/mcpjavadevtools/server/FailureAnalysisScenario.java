package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordingFile;

/** Real exception inputs, never synthetic successful captures or investigation state. */
class FailureAnalysisScenario extends McpServerStdioSupport implements AutoCloseable {
    static final String SERVICE = "com.example.social.post.app.service.TagLifecycleService";
    static final String METHOD = SERVICE + "#deleteTag";
    static final String PATH = "/api/v2/tenant/failure-it/tags/controlled";
    final RouteSynthesisScenario routes;
    final JvmProbeScenario runtime;
    final String key;
    String trace;
    JsonNode line;
    JsonNode capture;

    private FailureAnalysisScenario(RouteSynthesisScenario routes, String key) {
        this.routes = routes;
        this.runtime = routes.runtime;
        this.key = key;
    }

    static FailureAnalysisScenario start(String... arguments) throws Exception {
        List<String> options = new java.util.ArrayList<>(List.of(arguments));
        options.add("-XX:FlightRecorderOptions=stackdepth=256");
        RouteSynthesisScenario routes = RouteSynthesisScenario.start(options.toArray(String[]::new));
        try {
            return new FailureAnalysisScenario(routes,
                    JvmProbeScenario.key(SERVICE, "deleteTag", "throw new IllegalStateException("));
        } catch (Throwable failure) {
            routes.close();
            throw failure;
        }
    }

    void attachAndRecord() throws Exception {
        runtime.attach();
        Path recording = runtime.workspace.resolve("exception.jfr");
        Path settings = runtime.workspace.resolve("exception.jfc");
        Files.writeString(settings, """
                <?xml version="1.0" encoding="UTF-8"?>
                <configuration version="2.0" label="Failure IT" description="Controlled exception" provider="IT">
                  <event name="jdk.JavaExceptionThrow">
                    <setting name="enabled">true</setting>
                    <setting name="stackTrace">true</setting>
                  </event>
                </configuration>
                """);
        try {
            jfr("JFR.start", "name=failure-it", "settings=\"" + settings + "\"");
            trigger();
            jfr("JFR.stop", "name=failure-it", "filename=\"" + recording + "\"");
            trace = readTrace(recording);
            assertThat(trace).contains(SERVICE + ".deleteTag(TagLifecycleService.java:");
            JavaItScenario.observe("failure_analysis.analyze_trace", "INPUT_OBSERVED", "real_jfr_exception_complete_stack");
        } finally {
            // Raw JFR data is never retained, including when mcpItRetainFiles is enabled.
            Files.deleteIfExists(recording);
            Files.deleteIfExists(settings);
        }
    }

    private void jfr(String command, String... options) throws Exception {
        String suffix = System.getProperty("os.name").startsWith("Windows") ? ".exe" : "";
        Path executable = Path.of(System.getProperty("java.home"), "bin", "jcmd" + suffix);
        assertThat(executable).as("Full JDK with jcmd and JFR required").isRegularFile();
        List<String> arguments = new java.util.ArrayList<>(List.of(executable.toString(),
                Long.toString(runtime.target.pid()), command));
        arguments.addAll(List.of(options));
        Process process = new ProcessBuilder(arguments).directory(runtime.workspace.toFile())
                .redirectErrorStream(true).start();
        JavaItScenario.own(process);
        try {
            assertThat(process.waitFor(15, TimeUnit.SECONDS)).as("bounded owned JFR command").isTrue();
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(process.exitValue()).as(output).isZero();
            assertThat(output).doesNotContain("Could not", "Unknown argument", "Exception");
        } finally {
            JavaItScenario.stopTree(process.toHandle());
        }
    }

    private String readTrace(Path recording) throws Exception {
        try (RecordingFile file = new RecordingFile(recording)) {
            while (file.hasMoreEvents()) {
                RecordedEvent event = file.readEvent();
                if (event.getEventType().getName().equals("jdk.JavaExceptionThrow")
                        && event.getClass("thrownClass").getName().equals("java.lang.IllegalStateException")
                        && event.getString("message").startsWith("Downstream call failed: DELETE")) {
                    assertThat(event.getStackTrace().isTruncated()).isFalse();
                    return formatTrace(event);
                }
            }
        }
        throw new AssertionError("Controlled fixture exception missing from actual JFR events");
    }

    private String formatTrace(RecordedEvent event) {
        StringBuilder result = new StringBuilder(event.getClass("thrownClass").getName())
                .append(": ").append(event.getString("message")).append('\n');
        for (RecordedFrame frame : event.getStackTrace().getFrames()) {
            String type = frame.getMethod().getType().getName();
            String source = type.substring(type.lastIndexOf('.') + 1).split("\\$")[0] + ".java";
            String location = frame.getLineNumber() > 0 ? source + ":" + frame.getLineNumber() : "Unknown Source";
            result.append("\tat ").append(type).append('.').append(frame.getMethod().getName())
                    .append('(').append(location).append(")\n");
        }
        return result.toString();
    }

    void trigger() throws Exception {
        runtime.success("reset", Map.of("key", key));
        try (ProbeRequestObserver observer = new ProbeRequestObserver(runtime.baseUrl)) {
            runtime.server.send(executeRequest(810, "probe.wait_for_hit", Map.of("baseUrl", observer.baseUrl(),
                    "key", key, "timeoutMs", 5_000, "pollIntervalMs", 50, "maxRetries", 1), false));
            observer.awaitRequests(2);
            JsonNode response = runtime.result("transport_execute.execute", Map.of("protocol", "http",
                    "options", Map.of("wrappedOnly", true), "request", Map.of("method", "DELETE",
                            "url", "http://127.0.0.1:" + runtime.target.port() + PATH, "timeoutMs", 5_000)), true);
            assertThat(response.path("statusCode").asInt()).isEqualTo(500);
            JsonNode wait = toolPayload(runtime.server.responseFor(810)).path("result");
            assertThat(wait.path("reasonCode").asText()).isEqualTo("SUCCESS");
            assertThat(wait.path("actionResult").path("outcome").asText()).isEqualTo("LINE_HIT");
            JavaItScenario.observe("probe.wait_for_hit", "LINE_HIT", key);
        }
        line = runtime.status(key);
        assertThat(line.path("hitCount").asLong()).isPositive();
        assertThat(line.path("lineValidation").asText()).isEqualTo("resolvable");
        String captureId = line.path("capturePreview").path("captureId").asText();
        assertThat(captureId).isNotBlank();
        capture = runtime.success("capture", Map.of("captureId", captureId)).path("capture");
        assertThat(capture.path("captureId").asText()).isEqualTo(captureId);
    }

    Map<String, Object> analysis(String mode) {
        Map<String, Object> input = new LinkedHashMap<>(Map.of("trace", trace, "sidecarBaseUrl", runtime.baseUrl));
        if (mode != null) {
            input.put("investigation", investigation(mode));
        }
        return input;
    }

    static Map<String, Object> investigation(String mode) {
        return Map.of("mode", mode, "attemptLimit", 2, "elapsedTimeLimitMs", 30_000);
    }

    Map<String, Object> verification() {
        return new LinkedHashMap<>(Map.of("captureId", capture.path("captureId").asText(),
                "expectedFingerprint", expected(), "lineHit", Map.of("strictLineKey", key,
                        "hitCount", line.path("hitCount").asLong()), "sidecarBaseUrl", runtime.baseUrl));
    }

    static Map<String, Object> expected() {
        return Map.of("exceptionType", "java.lang.IllegalStateException", "rootCauseType", "java.lang.IllegalStateException",
                "nearestApplicationMethodKey", METHOD);
    }

    JsonNode result(String action, Map<String, Object> input) throws Exception {
        return runtime.result("failure_analysis." + action, input, false);
    }

    void cleanup() throws Exception {
        runtime.success("reset", Map.of("key", key));
        assertThat(runtime.status(key).path("hitCount").asLong()).isZero();
        runtime.deactivate();
    }

    @Override
    public void close() throws Exception {
        routes.close();
    }
}
