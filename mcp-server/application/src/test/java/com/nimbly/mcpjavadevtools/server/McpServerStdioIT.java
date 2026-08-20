package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

class McpServerStdioIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);
    private static final String PROTOCOL_VERSION = "2025-03-26";

    @Test
    void executableJarStartsInStdioModeWithoutContaminatingStdout() throws Exception {
        Path workspaceRoot = workspaceRoot();
        writeStdioFixture(workspaceRoot);
        HttpServer routeProbe = HttpServer.create(new InetSocketAddress(0), 0);
        routeProbe.createContext("/__probe/status", exchange -> respondProbe(exchange, """
                {"probe":{"key":"example.StdioController#run:7","hitCount":0,
                "lineResolvable":true,"lineValidation":"resolvable"}}
                """));
        routeProbe.createContext("/actuator/mappings", exchange -> respondProbe(exchange, """
                {"handler":"example.StdioController#run()",
                "predicate":"{GET [/stdio/run]}"}
                """));
        routeProbe.createContext("/__probe/failure/analyze", exchange -> respondProbe(exchange, """
                {"fingerprint":{"exceptionType":"java.lang.IllegalStateException",
                "rootCauseType":"java.lang.IllegalArgumentException",
                "nearestApplicationMethodKey":"example.StdioController#run:7",
                "complete":true,"normalizedMessage":"safe failure"}}
                """));
        routeProbe.createContext("/__probe/failure/verify", exchange -> respondProbe(exchange, """
                {"outcome":"matched","observedFingerprint":{"exceptionType":"java.lang.IllegalStateException",
                "rootCauseType":"java.lang.IllegalArgumentException",
                "nearestApplicationMethodKey":"example.StdioController#run:7","complete":true}}
                """));
        routeProbe.start();
        try (McpServerProcess server = McpServerProcess.start(jarPath(), workspaceRoot)) {
            server.send(initializeRequest());

            JsonNode initialize = server.responseFor(1);
            assertThat(initialize.path("result").path("protocolVersion").asText())
                    .isEqualTo(PROTOCOL_VERSION);

            server.send(Map.of("jsonrpc", "2.0", "method", "notifications/initialized", "params", Map.of()));
            server.send(request(2, "tools/list", Map.of()));
            JsonNode tools = server.responseFor(2).path("result").path("tools");
            assertThat(tools.isArray()).isTrue();
            assertThat(tools).extracting(node -> node.path("name").asText())
                    .containsExactlyInAnyOrder(
                            "debug_check", "jvm_lifecycle", "probe", "route_synthesis", "failure_analysis",
                            "artifact_management");
            JsonNode routeTool = null;
            for (JsonNode tool : tools) {
                if ("route_synthesis".equals(tool.path("name").asText())) {
                    routeTool = tool;
                    break;
                }
            }
            assertThat(routeTool).isNotNull();
            JsonNode routeSchema = routeTool.path("inputSchema");
            assertThat(routeSchema.path("oneOf").isArray())
                    .as("route_synthesis schema: %s", routeSchema)
                    .isTrue();
            assertThat(routeSchema.path("oneOf").size()).isEqualTo(4);
            JsonNode targetInputSchema = routeSchema.path("oneOf").get(0).path("properties").path("input");
            JsonNode recipeInputSchema = routeSchema.path("oneOf").get(3).path("properties").path("input");
            assertThat(routeSchema.path("oneOf").get(0).path("properties").path("action").path("const").asText())
                    .isEqualTo("infer_target");
            assertThat(routeSchema.path("oneOf").get(1).path("properties").path("action").path("const").asText())
                    .isEqualTo("class_methods");
            assertThat(routeSchema.path("oneOf").get(2).path("properties").path("action").path("const").asText())
                    .isEqualTo("discover_handlers");
            assertThat(routeSchema.path("oneOf").get(3).path("properties").path("action").path("const").asText())
                    .isEqualTo("create_recipe");
            assertThat(targetInputSchema.path("required"))
                    .isEqualTo(JSON.readTree("[\"projectRootAbs\"]"));
            assertThat(recipeInputSchema.path("required"))
                    .isEqualTo(JSON.readTree(
                            "[\"projectRootAbs\",\"classHint\",\"methodHint\",\"intentMode\"]"));
            assertThat(recipeInputSchema.path("properties").path("intentMode").path("enum"))
                    .isEqualTo(JSON.readTree("[\"line_probe\",\"regression\"]"));
            assertThat(recipeInputSchema.path("properties").path("discoveryPreference").path("enum"))
                    .isEqualTo(JSON.readTree("[\"static_only\",\"runtime_first\",\"runtime_only\"]"));
            assertThat(targetInputSchema.path("properties").path("lineHint").path("minimum").asInt())
                    .isEqualTo(1);
            assertThat(targetInputSchema.path("properties").path("additionalSourceRoots").path("maxItems").asInt())
                    .isEqualTo(10);
            assertThat(targetInputSchema.path("properties").path("maxCandidates").path("minimum").asInt())
                    .isEqualTo(1);
            JsonNode failureTool = null;
            for (JsonNode tool : tools) {
                if ("failure_analysis".equals(tool.path("name").asText())) {
                    failureTool = tool;
                    break;
                }
            }
            assertThat(failureTool).isNotNull();
            JsonNode failureSchema = failureTool.path("inputSchema");
            assertThat(failureSchema.path("oneOf")).hasSize(2);
            assertThat(failureSchema.path("oneOf").get(0).path("properties").path("action").path("const").asText())
                    .isEqualTo("analyze_trace");
            JsonNode verifySchema = failureSchema.path("oneOf").get(1).path("properties").path("input");
            assertThat(verifySchema.path("oneOf")).hasSize(2);
            JsonNode artifactTool = null;
            for (JsonNode tool : tools) {
                if ("artifact_management".equals(tool.path("name").asText())) {
                    artifactTool = tool;
                    break;
                }
            }
            assertThat(artifactTool).isNotNull();
            assertThat(artifactTool.path("inputSchema").path("oneOf")).hasSize(30);

            server.send(request(3, "tools/call", Map.of("name", "debug_check", "arguments", Map.of())));
            JsonNode debugCheck = server.responseFor(3);
            JsonNode debugCheckPayload = toolPayload(debugCheck);
            JsonNode structuredDebugCheck = debugCheck.path("result").path("structuredContent");
            assertThat(debugCheckPayload.path("ok").asBoolean()).isTrue();
            assertThat(debugCheckPayload.path("version").asText()).isEqualTo("0.1.9");
            assertThat(debugCheckPayload.path("workspaceRoot").asText()).isEqualTo(workspaceRoot.toString());
            assertThat(structuredDebugCheck.isObject()).isTrue();
            assertThat(structuredDebugCheck.path("ok").asBoolean()).isTrue();
            assertThat(structuredDebugCheck.path("workspaceRoot").asText()).isEqualTo(workspaceRoot.toString());

            server.send(request(6, "tools/call", Map.of(
                    "name", "probe",
                    "arguments", Map.of("action", "capture", "input", Map.of("captureId", "capture-1")))));
            JsonNode probe = toolPayload(server.responseFor(6));
            assertThat(probe.path("status").asText()).isEqualTo("probe_selection_failed");
            assertThat(probe.path("reasonCode").asText()).isEqualTo("probe_id_required");
            assertThat(probe.path("nextActionCode").asText()).isEqualTo("provide_probe_id");

            assertMissingProbeTarget(server, 7, "check", Map.of());
            assertMissingProbeTarget(server, 8, "status", Map.of("key", "example.Work#doIt:17"));
            assertMissingProbeTarget(server, 9, "reset", Map.of("className", "example.Work"));
            assertMissingProbeTarget(server, 10, "wait_for_hit", Map.of("key", "example.Work#doIt:17"));
            assertMissingProbeTarget(server, 11, "actuate", Map.of(
                    "action", "arm",
                    "sessionId", "session-1",
                    "targetKey", "example.Work#doIt:17",
                    "returnBoolean", true,
                    "ttlMs", 1000));
            assertMissingProbeTarget(server, 12, "profiler", Map.of("action", "status"));

            server.send(request(13, "tools/call", Map.of(
                    "name", "route_synthesis",
                    "arguments", Map.of("action", "infer_target", "input", Map.of(
                            "projectRootAbs", workspaceRoot.toString(), "classHint", "example.Missing")))));
            JsonNode routeSynthesis = toolPayload(server.responseFor(13));
            assertThat(routeSynthesis.path("resultType").asText()).isEqualTo("report");
            assertThat(routeSynthesis.path("reasonCode").asText()).isNotBlank();
            Map<String, Object> routeInput = Map.of(
                    "projectRootAbs", workspaceRoot.toString(), "classHint", "example.Missing");
            assertRouteSynthesisReport(server, 14, "class_methods", routeInput);
            assertRouteSynthesisReport(server, 15, "discover_handlers", routeInput);
            Map<String, Object> recipeFailureInput = Map.of(
                    "projectRootAbs", workspaceRoot.toString(), "classHint", "example.Missing",
                    "methodHint", "missing", "intentMode", "regression");
            assertRouteSynthesisReport(server, 16, "create_recipe", recipeFailureInput);
            Map<String, Object> successfulInferInput = Map.of(
                    "projectRootAbs", workspaceRoot.toString(),
                    "classHint", "example.StdioController", "methodHint", "run",
                    "probeBaseUrl", "http://127.0.0.1:" + routeProbe.getAddress().getPort());
            assertRouteSynthesisSuccess(server, 17, "infer_target", successfulInferInput, "ranked_candidates");
            Map<String, Object> successfulClassInput = Map.of(
                    "projectRootAbs", workspaceRoot.toString(), "classHint", "example.StdioController");
            assertRouteSynthesisSuccess(server, 18, "class_methods", successfulClassInput, "class_methods");
            assertRouteSynthesisSuccess(server, 19, "discover_handlers", successfulClassInput, "handler_inventory");
            Map<String, Object> successfulRecipeInput = Map.of(
                    "projectRootAbs", workspaceRoot.toString(), "classHint", "example.StdioController",
                    "methodHint", "run", "lineHint", 7, "intentMode", "line_probe",
                    "discoveryPreference", "static_only",
                    "probeBaseUrl", "http://127.0.0.1:" + routeProbe.getAddress().getPort());
            assertRouteSynthesisSuccess(server, 20, "create_recipe", successfulRecipeInput, "recipe");
            Map<String, Object> runtimeRecipeInput = Map.of(
                    "projectRootAbs", workspaceRoot.toString(), "classHint", "example.StdioController",
                    "methodHint", "run", "intentMode", "regression", "discoveryPreference", "runtime_only",
                    "mappingsBaseUrl", "http://127.0.0.1:" + routeProbe.getAddress().getPort()
                            + "/actuator/mappings");
            assertRuntimeRouteSynthesisSuccess(server, 21, runtimeRecipeInput);
            String sidecarBaseUrl = "http://127.0.0.1:" + routeProbe.getAddress().getPort();
            server.send(request(22, "tools/call", Map.of(
                    "name", "failure_analysis",
                    "arguments", Map.of(
                            "action", "analyze_trace",
                            "input", Map.of(
                                    "trace", "java.lang.IllegalStateException: secret-token\\n"
                                            + "    at example.StdioController.run(StdioController.java:7)",
                                    "sidecarBaseUrl", sidecarBaseUrl,
                                    "sidecarAuthorization", "Bearer stdio-secret",
                                    "timeoutMs", 15000)))));
            JsonNode analyzedFailure = toolPayload(server.responseFor(22));
            assertThat(analyzedFailure.path("outcome").asText()).isEqualTo("ANALYZED");
            assertThat(analyzedFailure.path("fingerprint").path("exceptionType").asText())
                    .isEqualTo("java.lang.IllegalStateException");
            assertThat(analyzedFailure.toString()).doesNotContain("secret-token", "stdio-secret");

            server.send(request(23, "tools/call", Map.of(
                    "name", "failure_analysis",
                    "arguments", Map.of(
                            "action", "verify_reproduction",
                            "input", Map.of(
                                    "captureId", "capture-stdio",
                                    "expectedFingerprint", Map.of(
                                            "exceptionType", "java.lang.IllegalStateException",
                                            "rootCauseType", "java.lang.IllegalArgumentException",
                                            "nearestApplicationMethodKey", "example.StdioController#run:7"),
                                    "lineHit", Map.of(
                                            "strictLineKey", "example.StdioController#run:7", "hitCount", 1),
                                    "sidecarBaseUrl", sidecarBaseUrl,
                                    "sidecarAuthorization", "Bearer stdio-secret",
                                    "timeoutMs", 15000)))));
            JsonNode reproducedFailure = toolPayload(server.responseFor(23));
            assertThat(reproducedFailure.path("outcome").asText()).isEqualTo("REPRODUCED");
            assertThat(reproducedFailure.path("reasonCode").asText()).isEqualTo("ok");
            assertThat(reproducedFailure.path("lineHit").path("hitCount").asInt()).isEqualTo(1);
            assertThat(reproducedFailure.toString()).doesNotContain("stdio-secret");

            server.send(request(24, "tools/call", Map.of(
                    "name", "failure_analysis",
                    "arguments", Map.of(
                            "action", "verify_reproduction",
                            "input", Map.of("terminalState", Map.of(
                                    "outcome", "BLOCKED_MISSING_AUTH",
                                    "reasonCode", "missing_auth",
                                    "cleanupStatus", "cleanup_confirmed",
                                    "attemptCount", 1))))));
            JsonNode failureAnalysis = toolPayload(server.responseFor(24));
            assertThat(failureAnalysis.path("outcome").asText()).isEqualTo("BLOCKED_MISSING_AUTH");
            assertThat(failureAnalysis.path("reasonCode").asText()).isEqualTo("missing_auth");

            server.send(request(25, "tools/call", Map.of(
                    "name", "artifact_management",
                    "arguments", Map.of(
                            "artifactType", "probe_config",
                            "action", "read",
                            "input", Map.of()))));
            JsonNode artifactRead = toolPayload(server.responseFor(25));
            assertThat(artifactRead.path("status").asText()).isEqualTo("not_configured");
            assertThat(artifactRead.path("reasonCode").asText()).isEqualTo("probe_registry_not_configured");
            assertThat(artifactRead.path("artifactType").asText()).isEqualTo("probe_config");
            assertThat(artifactRead.path("nextActionCode").asText()).isEqualTo("set_probe_registry_config");

            server.send(request(26, "tools/call", Map.of(
                    "name", "artifact_management",
                    "arguments", Map.of(
                            "artifactType", "probe_config",
                            "action", "upsert",
                            "input", Map.of("payload", Map.of())))));
            JsonNode artifactUpserted = toolPayload(server.responseFor(26));
            assertThat(artifactUpserted.path("status").asText()).isEqualTo("ok");
            assertThat(artifactUpserted.path("artifactType").asText()).isEqualTo("probe_config");

            server.send(request(27, "tools/call", Map.of(
                    "name", "artifact_management",
                    "arguments", Map.of(
                            "artifactType", "project_context",
                            "action", "list",
                            "input", Map.of()))));
            JsonNode projectList = toolPayload(server.responseFor(27));
            assertThat(projectList.path("status").asText()).isEqualTo("ok");
            assertThat(projectList.path("projectNames").isArray()).isTrue();

            server.send(request(28, "tools/call", Map.of(
                    "name", "artifact_management",
                    "arguments", Map.of(
                            "artifactType", "performance_plan",
                            "action", "list",
                            "input", Map.of("projectName", "demo")))));
            JsonNode performanceList = toolPayload(server.responseFor(28));
            assertThat(performanceList.path("status").asText()).isEqualTo("ok");
            assertThat(performanceList.path("planNames").isArray()).isTrue();

            server.send(request(4, "resources/list", Map.of()));
            JsonNode resources = server.responseFor(4).path("result").path("resources");
            assertThat(resources).extracting(node -> node.path("uri").asText())
                    .contains("mcp-java-dev-tools://status");

            server.send(request(5, "resources/read", Map.of("uri", "mcp-java-dev-tools://status")));
            JsonNode status = server.responseFor(5);
            JsonNode statusPayload = resourcePayload(status);
            assertThat(statusPayload.path("ok").asBoolean()).isTrue();
            assertThat(statusPayload.path("workspaceRootSource").asText()).isEqualTo("roots");
            assertThat(statusPayload.path("rootsDiscoveryStatus").asText()).isEqualTo("available");

            server.closeInputAndAwaitTermination();
            assertThat(server.stdoutLines()).allSatisfy(this::assertJsonRpcMessage);
            assertThat(server.stderrText()).isNotBlank();
        } finally {
            routeProbe.stop(0);
        }
    }

    @Test
    void executableJarReportsStartupFailureWithoutLeakingConfiguration() throws Exception {
        Path workspaceRoot = workspaceRoot();
        try (McpServerProcess server = McpServerProcess.start(
                jarPath(), workspaceRoot, "--spring.ai.mcp.server.stdio=not-a-boolean")) {
            server.awaitStartupFailure();

            assertThat(server.stdoutLines()).isEmpty();
            assertThat(server.stderrText())
                    .contains("mcp_java_dev_tools_startup_failed reasonCode=startup_failed")
                    .doesNotContain("not-a-boolean");
        }
    }

    @Test
    void executableJarReachesAllJvmLifecycleActionsThroughStdio() throws Exception {
        Path workspaceRoot = workspaceRoot();
        try (McpServerProcess server = McpServerProcess.start(jarPath(), workspaceRoot)) {
            server.send(initializeRequest());
            server.responseFor(1);
            server.send(Map.of("jsonrpc", "2.0", "method", "notifications/initialized", "params", Map.of()));

            server.send(request(2, "tools/call", Map.of(
                    "name", "jvm_lifecycle",
                    "arguments", Map.of("action", "list_jvms", "input", Map.of()))));
            JsonNode discovery = toolPayload(server.responseFor(2));
            assertThat(discovery.path("status").asText()).isIn("ok", "blocked");
            assertThat(discovery.path("reasonCode").asText()).isNotBlank();

            Map<String, Object> fencedInput = Map.of(
                    "pid", Long.toString(server.pid()),
                    "expectedProcessStartEpochMs", server.processStartEpochMs(),
                    "confirm", true);
            server.send(request(3, "tools/call", Map.of(
                    "name", "jvm_lifecycle",
                    "arguments", Map.of("action", "attach", "input", fencedInput))));
            assertThat(toolPayload(server.responseFor(3)).path("reasonCode").asText())
                    .isEqualTo("mcp_server_attach_forbidden");

            server.send(request(4, "tools/call", Map.of(
                    "name", "jvm_lifecycle",
                    "arguments", Map.of("action", "deactivate", "input", fencedInput))));
            assertThat(toolPayload(server.responseFor(4)).path("reasonCode").asText())
                    .isEqualTo("mcp_server_attach_forbidden");
        }
    }

    @Test
    void executableJarPerformsRealAttachAndDeactivateAgainstSurvivingTarget() throws Exception {
        Path serverJar = jarPath();
        assertThat(serverJar.getParent().resolve("sidecar/jvm-attach-helper.jar"))
                .isRegularFile();
        assertThat(serverJar.getParent().resolve("sidecar/sidecar-agent.jar"))
                .isRegularFile();
        try (LifecycleTargetProcess target = LifecycleTargetProcess.start();
                McpServerProcess server = McpServerProcess.start(serverJar, workspaceRoot())) {
            server.send(initializeRequest());
            server.responseFor(1);
            server.send(Map.of("jsonrpc", "2.0", "method", "notifications/initialized", "params", Map.of()));

            server.send(request(2, "tools/call", Map.of(
                    "name", "jvm_lifecycle",
                    "arguments", Map.of("action", "list_jvms", "input", Map.of()))));
            JsonNode discovery = toolPayload(server.responseFor(2));
            JsonNode selected = null;
            for (JsonNode candidate : discovery.path("jvms")) {
                if (Long.toString(target.pid()).equals(candidate.path("pid").asText())) {
                    selected = candidate;
                    break;
                }
            }
            assertThat(selected).isNotNull();
            assertThat(selected.path("processStartEpochMs").asLong())
                    .isEqualTo(target.processStartEpochMs());
            assertThat(selected.path("attachmentState").asText()).isEqualTo("unverified");
            assertThat(selected.path("probeState").asText()).isEqualTo("unverified");

            int probePort = freePort();
            Map<String, Object> attachInput = Map.of(
                    "pid", Long.toString(target.pid()),
                    "expectedProcessStartEpochMs", target.processStartEpochMs(),
                    "confirm", true,
                    "probeHost", "127.0.0.1",
                    "probePort", probePort);
            server.send(request(3, "tools/call", Map.of(
                    "name", "jvm_lifecycle",
                    "arguments", Map.of("action", "attach", "input", attachInput))));
            JsonNode attach = toolPayload(server.responseFor(3));
            assertThat(attach.path("status").asText()).isEqualTo("ok");
            assertThat(attach.path("reasonCode").asText()).isEqualTo("active");
            assertThat(attach.path("lifecycle").path("outcome").asText()).isEqualTo("active");
            assertThat(target.isAlive()).isTrue();

            server.send(request(4, "tools/call", Map.of(
                    "name", "jvm_lifecycle",
                    "arguments", Map.of("action", "deactivate", "input", Map.of(
                            "pid", Long.toString(target.pid()),
                            "expectedProcessStartEpochMs", target.processStartEpochMs(),
                            "confirm", true)))));
            JsonNode deactivate = toolPayload(server.responseFor(4));
            assertThat(deactivate.path("status").asText()).isEqualTo("ok");
            assertThat(deactivate.path("reasonCode").asText()).isEqualTo("deactivated");
            assertThat(deactivate.path("lifecycle").path("outcome").asText())
                    .isEqualTo("deactivated");
            assertThat(target.isAlive()).isTrue();
        }
    }

    @Test
    void executableJarInvokesARegisteredProbeThroughStdio() throws Exception {
        AtomicBoolean resetCalled = new AtomicBoolean();
        AtomicReference<String> statusQuery = new AtomicReference<>();
        HttpServer sidecar = HttpServer.create(new InetSocketAddress(0), 0);
        sidecar.createContext("/__probe/reset", exchange -> {
            resetCalled.set(true);
            respondProbe(exchange, "{\"ok\":true}");
        });
        sidecar.createContext("/__probe/status", exchange -> {
            statusQuery.set(exchange.getRequestURI().getRawQuery());
            respondProbe(exchange, "{\"probe\":{\"key\":\"mcp.jvm.diagnose#key\",\"hitCount\":0}}");
        });
        sidecar.start();
        try (McpServerProcess server = McpServerProcess.start(
                jarPath(),
                workspaceRoot(),
                "--mcpjvm.probe.registry.registrations[0].id=orders",
                "--mcpjvm.probe.registry.registrations[0].base-url=http://127.0.0.1:" + sidecar.getAddress().getPort())) {
            server.send(initializeRequest());
            server.responseFor(1);
            server.send(Map.of("jsonrpc", "2.0", "method", "notifications/initialized", "params", Map.of()));
            server.send(request(13, "tools/call", Map.of(
                    "name", "probe",
                    "arguments", Map.of("action", "check", "input", Map.of("probeId", "orders")))));

            JsonNode response = toolPayload(server.responseFor(13));
            assertThat(response.path("status").asText()).isEqualTo("ok");
            assertThat(response.path("reasonCode").asText()).isEqualTo("success");
            assertThat(response.path("nextActionCode").isMissingNode()).isTrue();
            assertThat(resetCalled).isTrue();
            assertThat(statusQuery.get()).isEqualTo("key=mcp.jvm.diagnose%23key");
        } finally {
            sidecar.stop(0);
        }
    }

    @Test
    void executableJarInvokesEveryProbeActionThroughStdio() throws Exception {
        AtomicInteger statusCalls = new AtomicInteger();
        HttpServer sidecar = HttpServer.create(new InetSocketAddress(0), 0);
        sidecar.createContext("/__probe/reset", exchange -> {
            String key = JSON.readTree(exchange.getRequestBody()).path("key").asText();
            respondProbe(exchange, """
                    {"key":"%s","ok":true,"lineResolvable":true,"lineValidation":"resolvable"}
                    """.formatted(key));
        });
        sidecar.createContext("/__probe/status", exchange -> {
            int call = statusCalls.incrementAndGet();
            String key = queryValue(exchange.getRequestURI().getRawQuery(), "key");
            long hitCount = call > 3 ? 1 : 0;
            long lastHitEpoch = hitCount == 0 ? 0 : System.currentTimeMillis();
            respondProbe(exchange, """
                    {"probe":{"key":"%s","hitCount":%d,"lastHitEpoch":%d,
                    "lineResolvable":true,"lineValidation":"resolvable"}}
                    """.formatted(key, hitCount, lastHitEpoch));
        });
        sidecar.createContext("/__probe/capture", exchange -> respondProbe(exchange, """
                {"capture":{"captureId":"capture-1","methodKey":"example.Work#doIt",
                "capturedAtEpoch":1,"args":[],"executionPaths":[]}}
                """));
        sidecar.createContext("/__probe/actuate", exchange -> respondProbe(exchange, """
                {"ok":true,"action":"arm","sessionId":"session-1","targetKey":"example.Work#doIt:17",
                "returnBoolean":true,"ttlMs":1000,"scopeState":"armed","mode":"actuate"}
                """));
        sidecar.createContext("/__probe/profiler", exchange -> respondProbe(exchange, """
                {"ok":true,"profiler":{"status":"idle","supported":true}}
                """));
        sidecar.start();
        try (McpServerProcess server = McpServerProcess.start(
                jarPath(),
                workspaceRoot(),
                "--mcpjvm.probe.registry.registrations[0].id=orders",
                "--mcpjvm.probe.registry.registrations[0].base-url=http://127.0.0.1:" + sidecar.getAddress().getPort())) {
            server.send(initializeRequest());
            server.responseFor(1);
            server.send(Map.of("jsonrpc", "2.0", "method", "notifications/initialized", "params", Map.of()));

            server.send(request(20, "tools/call", Map.of(
                    "name", "probe",
                    "arguments", Map.of("action", "check", "input", Map.of("probeId", "orders")))));
            assertSuccessfulProbe(server.responseFor(20), "check");

            server.send(request(21, "tools/call", Map.of(
                    "name", "probe",
                    "arguments", Map.of("action", "status", "input", Map.of(
                            "probeId", "orders", "key", "example.Work#doIt:17")))));
            assertSuccessfulProbe(server.responseFor(21), "status");

            server.send(request(22, "tools/call", Map.of(
                    "name", "probe",
                    "arguments", Map.of("action", "reset", "input", Map.of(
                            "probeId", "orders", "key", "example.Work#doIt:17")))));
            assertSuccessfulProbe(server.responseFor(22), "reset");

            server.send(request(23, "tools/call", Map.of(
                    "name", "probe",
                    "arguments", Map.of("action", "wait_for_hit", "input", Map.of(
                            "probeId", "orders", "key", "example.Work#doIt:17",
                            "timeoutMs", 1000, "pollIntervalMs", 100, "maxRetries", 1)))));
            assertSuccessfulProbe(server.responseFor(23), "wait_for_hit");

            server.send(request(24, "tools/call", Map.of(
                    "name", "probe",
                    "arguments", Map.of("action", "capture", "input", Map.of(
                            "probeId", "orders", "captureId", "capture-1")))));
            assertSuccessfulProbe(server.responseFor(24), "capture");

            server.send(request(25, "tools/call", Map.of(
                    "name", "probe",
                    "arguments", Map.of("action", "actuate", "input", Map.of(
                            "probeId", "orders", "action", "arm", "sessionId", "session-1",
                            "targetKey", "example.Work#doIt:17", "returnBoolean", true, "ttlMs", 1000)))));
            assertSuccessfulProbe(server.responseFor(25), "actuate");

            server.send(request(26, "tools/call", Map.of(
                    "name", "probe",
                    "arguments", Map.of("action", "profiler", "input", Map.of(
                            "probeId", "orders", "action", "status")))));
            assertSuccessfulProbe(server.responseFor(26), "profiler");

            server.closeInputAndAwaitTermination();
            assertThat(server.stdoutLines()).allSatisfy(this::assertJsonRpcMessage);
        } finally {
            sidecar.stop(0);
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void executableJarStopsAfterSigint() throws Exception {
        assertPosixSignalStopsExecutable("INT");
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void executableJarStopsAfterSigterm() throws Exception {
        assertPosixSignalStopsExecutable("TERM");
    }

    private static Map<String, Object> initializeRequest() {
        return request(1, "initialize", Map.of(
                "protocolVersion", PROTOCOL_VERSION,
                "capabilities", Map.of("roots", Map.of("listChanged", true)),
                "clientInfo", Map.of("name", "mcp-server-foundation-it", "version", "1.0")));
    }

    private static Map<String, Object> request(int id, String method, Map<String, Object> params) {
        return Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params);
    }

    private static Path jarPath() {
        String configured = System.getProperty("mcpServerJar");
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("Failsafe system property mcpServerJar is missing");
        }
        return Path.of(configured).toAbsolutePath();
    }

    private static Path workspaceRoot() {
        return Path.of(System.getProperty("java.io.tmpdir"), "mcp-java-dev-tools-stdio-it-workspace");
    }

    private static void writeStdioFixture(Path workspaceRoot) throws IOException {
        Files.deleteIfExists(workspaceRoot.resolve(".mcpjvm/probe-config.json"));
        Path sourceRoot = workspaceRoot.resolve("src/main/java/example");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("StdioController.java"), """
                package example;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;
                @RestController
                public class StdioController {
                    @GetMapping("/stdio/run")
                    public String run() {
                        return "ok";
                    }
                }
                """);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private void assertPosixSignalStopsExecutable(String signal) throws Exception {
        try (McpServerProcess server = McpServerProcess.start(jarPath(), workspaceRoot())) {
            server.send(initializeRequest());
            server.responseFor(1);
            server.sendPosixSignal(signal);
            assertThat(server.stdoutLines()).allSatisfy(this::assertJsonRpcMessage);
        }
    }

    private static JsonNode toolPayload(JsonNode response) throws IOException {
        String text = response.path("result").path("content").get(0).path("text").asText();
        try {
            return JSON.readTree(text);
        } catch (IOException exception) {
            throw new AssertionError("Probe Tool payload was not JSON: " + text, exception);
        }
    }

    private static JsonNode resourcePayload(JsonNode response) throws IOException {
        String text = response.path("result").path("contents").get(0).path("text").asText();
        return JSON.readTree(text);
    }

    private static void respondProbe(HttpExchange exchange, String payload) throws IOException {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("content-type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static String queryValue(String query, String key) {
        if (query == null) {
            return "";
        }
        for (String parameter : query.split("&")) {
            String[] pair = parameter.split("=", 2);
            if (pair.length == 2 && key.equals(pair[0])) {
                return URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private static void assertRouteSynthesisReport(
            McpServerProcess server,
            int requestId,
            String action,
            Map<String, Object> input) throws Exception {
        server.send(request(requestId, "tools/call", Map.of(
                "name", "route_synthesis",
                "arguments", Map.of("action", action, "input", input))));
        JsonNode payload = toolPayload(server.responseFor(requestId));
        assertThat(payload.path("resultType").asText()).as("result type for %s", action)
                .isNotBlank();
        assertThat(payload.path("status").asText()).as("status for %s", action).isNotBlank();
        assertThat(payload.path("reasonCode").asText()).as("reason code for %s", action).isNotBlank();
    }

    private static void assertRouteSynthesisSuccess(
            McpServerProcess server,
            int requestId,
            String action,
            Map<String, Object> input,
            String resultType) throws Exception {
        server.send(request(requestId, "tools/call", Map.of(
                "name", "route_synthesis",
                "arguments", Map.of("action", action, "input", input))));
        JsonNode payload = toolPayload(server.responseFor(requestId));
        assertThat(payload.path("resultType").asText()).as("result type for %s payload=%s", action, payload)
                .isEqualTo(resultType);
        assertThat(payload.path("status").asText()).as("status for %s", action)
                .isIn("ok", "ready", "partial");
        JsonNode details = payload.path("details");
        assertThat(details.isObject()).as("details for %s", action).isTrue();
        if ("recipe".equals(resultType)) {
            assertThat(details.path("requestCandidates").isArray()).isTrue();
            assertThat(details.path("requestCandidates")).isNotEmpty();
            assertThat(details.path("requestCandidates").get(0).path("path").asText())
                    .isEqualTo("/stdio/run");
            assertThat(details.path("executionPlan").isObject()).isTrue();
            assertThat(details.path("executionPlan").path("selectedMode").asText())
                    .isEqualTo("single_line_probe");
            assertThat(details.path("runtimeCapture").path("status").asText())
                    .isEqualTo("available");
        }
    }

    private static void assertRuntimeRouteSynthesisSuccess(
            McpServerProcess server,
            int requestId,
            Map<String, Object> input) throws Exception {
        server.send(request(requestId, "tools/call", Map.of(
                "name", "route_synthesis",
                "arguments", Map.of("action", "create_recipe", "input", input))));
        JsonNode payload = toolPayload(server.responseFor(requestId));
        assertThat(payload.path("resultType").asText()).isEqualTo("recipe");
        assertThat(payload.path("status").asText()).isEqualTo("ready");
        JsonNode details = payload.path("details");
        assertThat(details.path("requestCandidates").get(0).path("path").asText())
                .isEqualTo("/stdio/run");
        assertThat(details.path("executionPlan").path("selectedMode").asText())
                .isEqualTo("regression");
        assertThat(payload.toString()).doesNotContain("mappingsBaseUrl");
    }

    private static void assertSuccessfulProbe(JsonNode response, String action) throws IOException {
        JsonNode payload = toolPayload(response);
        assertThat(payload.path("status").asText()).as("status for %s", action).isEqualTo("ok");
        assertThat(payload.path("reasonCode").asText()).as("reason code for %s", action).isEqualTo("success");
        assertThat(payload.path("resultType").asText()).as("result type for %s", action).isEqualTo("report");
        assertThat(payload.path("request").isObject()).as("request details for %s", action).isTrue();
        JsonNode details = payload.path("details");
        assertThat(details.isObject()).as("details envelope for %s", action).isTrue();
        assertThat(details.path("request").isObject()).as("nested request for %s", action).isTrue();
        switch (action) {
            case "check" -> assertCheckCompatibility(details);
            case "status" -> assertStatusCompatibility(details);
            case "reset" -> assertResetCompatibility(details, payload);
            case "wait_for_hit" -> assertWaitCompatibility(details);
            case "capture" -> assertCaptureCompatibility(details, payload);
            case "actuate" -> assertActuateCompatibility(details, payload);
            case "profiler" -> assertProfilerCompatibility(details, payload);
            default -> fail("Unknown Probe action in compatibility matrix: " + action);
        }
    }

    private static void assertCheckCompatibility(JsonNode details) {
        assertThat(details.path("config").isObject()).isTrue();
        assertThat(details.path("checks").path("reset").isObject()).isTrue();
        assertThat(details.path("checks").path("status").isObject()).isTrue();
        assertThat(details.path("recommendations").isArray()).isTrue();
    }

    private static void assertStatusCompatibility(JsonNode details) {
        assertThat(details.path("targetKey").asText()).isEqualTo("example.Work#doIt:17");
        assertThat(details.path("executionHit").asText()).isEqualTo("not_hit");
        assertThat(details.path("response").path("json").path("key").asText())
                .isEqualTo("example.Work#doIt:17");
    }

    private static void assertResetCompatibility(JsonNode details, JsonNode payload) {
        assertThat(details.path("response").path("status").asInt()).isEqualTo(200);
        assertThat(payload.path("result").path("entries").isArray()).isTrue();
    }

    private static void assertWaitCompatibility(JsonNode details) {
        assertThat(details.path("targetKey").asText()).isEqualTo("example.Work#doIt:17");
        assertThat(details.path("executionHit").asText()).isEqualTo("line_hit");
        assertThat(details.path("probeHit").asText()).startsWith("hitCount=");
    }

    private static void assertCaptureCompatibility(JsonNode details, JsonNode payload) {
        assertThat(details.path("request").path("captureId").asText()).isEqualTo("capture-1");
        assertThat(details.path("targetKey").asText()).isEqualTo("example.Work#doIt");
        assertThat(payload.path("result").path("found").asBoolean()).isTrue();
    }

    private static void assertActuateCompatibility(JsonNode details, JsonNode payload) {
        assertThat(details.path("response").path("json").path("action").asText()).isEqualTo("arm");
        assertThat(details.path("apiOutcome").asText()).isEqualTo("ok");
        assertThat(payload.path("result").path("actuated").asBoolean()).isTrue();
    }

    private static void assertProfilerCompatibility(JsonNode details, JsonNode payload) {
        assertThat(details.path("response").path("json").path("status").asText()).isEqualTo("idle");
        assertThat(details.path("apiOutcome").asText()).isEqualTo("ok");
        assertThat(payload.path("result").path("status").asText()).isEqualTo("idle");
    }

    private static void assertMissingProbeTarget(
            McpServerProcess server,
            int requestId,
            String action,
            Map<String, Object> input) throws Exception {
        server.send(request(requestId, "tools/call", Map.of(
                "name", "probe",
                "arguments", Map.of("action", action, "input", input))));
        JsonNode response = toolPayload(server.responseFor(requestId));
        assertThat(response.path("status").asText()).isEqualTo("probe_selection_failed");
        assertThat(response.path("reasonCode").asText()).isEqualTo("probe_id_required");
        assertThat(response.path("nextActionCode").asText()).isEqualTo("provide_probe_id");
    }

    private void assertJsonRpcMessage(String line) {
        try {
            JsonNode message = JSON.readTree(line);
            assertThat(message.path("jsonrpc").asText()).isEqualTo("2.0");
        } catch (IOException exception) {
            fail("stdout line was not valid JSON-RPC: " + line, exception);
        }
    }

    private static final class LifecycleTargetProcess implements AutoCloseable {

        private final Process process;

        private LifecycleTargetProcess(Process process) {
            this.process = process;
        }

        static LifecycleTargetProcess start() throws IOException {
            Process process = new ProcessBuilder(
                    McpServerProcess.javaBinary(),
                    "-cp", System.getProperty("java.class.path"),
                    LifecycleTargetMain.class.getName())
                    .redirectErrorStream(true)
                    .start();
            return new LifecycleTargetProcess(process);
        }

        long pid() {
            return process.pid();
        }

        long processStartEpochMs() {
            return process.toHandle().info().startInstant().orElseThrow().toEpochMilli();
        }

        boolean isAlive() {
            return process.isAlive();
        }

        @Override
        public void close() throws Exception {
            process.destroy();
            if (!process.waitFor(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
            assertThat(process.isAlive()).isFalse();
        }
    }

    private static class McpServerProcess implements AutoCloseable {

        private final Process process;
        private final OutputStream stdin;
        private final BlockingQueue<String> stdoutQueue = new LinkedBlockingQueue<>();
        private final List<String> stdoutLines = Collections.synchronizedList(new ArrayList<>());
        private final StringBuilder stderr = new StringBuilder();
        private final ExecutorService readers = Executors.newFixedThreadPool(2);
        private final String workspaceRootUri;
        private boolean closed;

        private McpServerProcess(Process process, Path workspaceRoot) {
            this.process = process;
            this.stdin = process.getOutputStream();
            this.workspaceRootUri = workspaceRoot.toUri().toString();
            readers.submit(() -> collectStdout(process.getInputStream()));
            readers.submit(() -> collectStderr(process.getErrorStream()));
        }

        static McpServerProcess start(Path jar, Path workspaceRoot, String... applicationArguments)
                throws IOException {
            List<String> command = new ArrayList<>(List.of(javaBinary(), "-jar", jar.toString()));
            command.addAll(List.of(applicationArguments));
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(false)
                    .start();
            return new McpServerProcess(process, workspaceRoot);
        }

        void send(Map<String, Object> message) throws IOException {
            stdin.write(JSON.writeValueAsBytes(message));
            stdin.write('\n');
            stdin.flush();
        }

        void send(JsonNode message) throws IOException {
            stdin.write(JSON.writeValueAsBytes(message));
            stdin.write('\n');
            stdin.flush();
        }

        JsonNode responseFor(int id) throws Exception {
            Instant deadline = Instant.now().plus(RESPONSE_TIMEOUT);
            while (Instant.now().isBefore(deadline)) {
                String line = stdoutQueue.poll(100, TimeUnit.MILLISECONDS);
                if (line == null) {
                    continue;
                }
                JsonNode message = JSON.readTree(line);
                if ("roots/list".equals(message.path("method").asText())) {
                    respondToRootsRequest(message);
                    continue;
                }
                if (message.path("id").asInt(-1) == id) {
                    return message;
                }
            }
            throw new AssertionError("Timed out waiting for JSON-RPC response " + id + ". stderr=" + stderrText());
        }

        long pid() {
            return process.pid();
        }

        long processStartEpochMs() {
            return process.toHandle().info().startInstant().orElseThrow().toEpochMilli();
        }

        private void respondToRootsRequest(JsonNode request) throws IOException {
            ObjectNode response = JSON.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.set("id", request.path("id"));
            response.putObject("result")
                    .putArray("roots")
                    .addObject()
                    .put("uri", workspaceRootUri)
                    .put("name", "stdio-integration-workspace");
            send(response);
        }

        List<String> stdoutLines() {
            synchronized (stdoutLines) {
                return List.copyOf(stdoutLines);
            }
        }

        String stderrText() {
            synchronized (stderr) {
                return stderr.toString();
            }
        }

        void closeInputAndAwaitTermination() throws Exception {
            if (closed) {
                return;
            }
            closed = true;
            stdin.close();
            if (!process.waitFor(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                fail("MCP process did not terminate after stdin closed within " + SHUTDOWN_TIMEOUT);
            }
            awaitReaders();
            assertThat(process.exitValue()).isZero();
        }

        void awaitStartupFailure() throws Exception {
            if (!process.waitFor(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                fail("MCP process did not terminate after startup failure within " + SHUTDOWN_TIMEOUT);
            }
            closed = true;
            awaitReaders();
            assertThat(process.exitValue()).isNotZero();
        }

        void sendPosixSignal(String signal) throws Exception {
            Process signalProcess = new ProcessBuilder("kill", "-" + signal, Long.toString(process.pid()))
                    .redirectErrorStream(true)
                    .start();
            assertThat(signalProcess.waitFor(RESPONSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
            assertThat(signalProcess.exitValue()).isZero();
            awaitSignalTermination(signal);
        }

        @Override
        public void close() throws Exception {
            try {
                closeInputAndAwaitTermination();
            } finally {
                awaitReaders();
            }
        }

        private void awaitReaders() throws InterruptedException {
            readers.shutdown();
            if (!readers.awaitTermination(RESPONSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                readers.shutdownNow();
                readers.awaitTermination(2, TimeUnit.SECONDS);
                fail("MCP output readers did not drain within " + RESPONSE_TIMEOUT);
            }
        }

        private void awaitSignalTermination(String signal) throws Exception {
            if (!process.waitFor(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                fail("MCP process did not terminate after SIG" + signal + " within " + SHUTDOWN_TIMEOUT);
            }
            closed = true;
            awaitReaders();
        }

        private void collectStdout(InputStream stream) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        stdoutLines.add(line);
                        stdoutQueue.offer(line);
                    }
                }
            } catch (IOException exception) {
                appendStderr("stdout reader error: " + exception.getMessage());
            }
        }

        private void collectStderr(InputStream stream) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    appendStderr(line);
                }
            } catch (IOException exception) {
                appendStderr("stderr reader error: " + exception.getMessage());
            }
        }

        private void appendStderr(String line) {
            synchronized (stderr) {
                stderr.append(line).append('\n');
            }
        }

        private static String javaBinary() {
            boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
            String executable = windows ? "java.exe" : "java";
            return Path.of(System.getProperty("java.home"), "bin", executable).toString();
        }
    }
}
