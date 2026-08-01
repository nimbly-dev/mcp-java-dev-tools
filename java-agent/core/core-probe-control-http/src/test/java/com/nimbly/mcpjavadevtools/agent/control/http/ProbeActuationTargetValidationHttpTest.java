package com.nimbly.mcpjavadevtools.agent.control.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.agent.capture.CapturePreviewView;
import com.nimbly.mcpjavadevtools.agent.capture.ProbeCaptureStore;
import com.nimbly.mcpjavadevtools.agent.runtime.ProbeRuntime;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProbeActuationTargetValidationHttpTest {
  private static final String CLASS_NAME = "example.HttpActuationTarget";
  private static final String METHOD_NAME = "guard";
  private static final String CONDITIONAL_TARGET = CLASS_NAME + "#" + METHOD_NAME + ":30";
  private static final String NON_CONDITIONAL_TARGET = CLASS_NAME + "#" + METHOD_NAME + ":20";
  private static final String UNRESOLVED_TARGET = CLASS_NAME + "#" + METHOD_NAME + ":99";
  private static final ObjectMapper JSON = new ObjectMapper();
  private final HttpClient httpClient = HttpClient.newHttpClient();
  private ProbeHttpServer server;

  @BeforeEach
  void startServer() throws IOException {
    ProbeRuntime.configure("observe", "", "", false);
    ProbeRuntime.registerResolvableLine(CLASS_NAME, METHOD_NAME, 20);
    ProbeRuntime.registerResolvableLine(CLASS_NAME, METHOD_NAME, 30);
    ProbeRuntime.registerActuatableLine(CLASS_NAME, METHOD_NAME, 30);
    ProbeRuntime.registerApplicationClassResolver(className -> className.startsWith("com.example."));
    server = ProbeHttpServer.start("127.0.0.1", 0);
  }

  @AfterEach
  void stopServer() {
    server.stop();
    ProbeRuntime.configure("observe", "", "", false);
    ProbeRuntime.registerApplicationClassResolver(null);
  }

  @Test
  void appliesTheCompleteTargetValidationMatrix() throws Exception {
    assertRejectedArm(
        "invalid-syntax",
        "not-a-strict-line-key",
        "invalid_target_key",
        400,
        null
    );
    assertRejectedArm("unresolved", UNRESOLVED_TARGET, "invalid_line_target", 400, "actuate");
    assertRejectedArm(
        "non-conditional",
        NON_CONDITIONAL_TARGET,
        "target_line_not_actuatable",
        400,
        "actuate"
    );

    HttpResponse<String> accepted = arm("conditional", CONDITIONAL_TARGET);
    JsonNode acceptedBody = JSON.readTree(accepted.body());
    assertEquals(200, accepted.statusCode());
    assertEquals("armed", acceptedBody.get("scopeState").asText());
    assertEquals(1, ProbeRuntime.actuationState().activeSessionCount());
  }

  @Test
  void rejectedArmDoesNotReplaceAnExistingSession() throws Exception {
    HttpResponse<String> accepted = arm("existing", CONDITIONAL_TARGET);
    assertEquals(200, accepted.statusCode());
    assertEquals(1, ProbeRuntime.actuationState().activeSessionCount());

    assertRejectedArm("existing", "not-a-strict-line-key", "invalid_target_key", 400, null);
    assertExistingSessionRemainsArmed();
    assertRejectedArm("existing", UNRESOLVED_TARGET, "invalid_line_target", 400, "actuate");
    assertExistingSessionRemainsArmed();
    assertRejectedArm(
        "existing",
        NON_CONDITIONAL_TARGET,
        "target_line_not_actuatable",
        400,
        "actuate"
    );
    assertExistingSessionRemainsArmed();
  }

  @Test
  void statusExposesRuntimeIdentitySeparatelyFromActuationSession() throws Exception {
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(
            "http://127.0.0.1:" + server.port()
                + "/__probe/status?key=example.HttpActuationTarget%23guard%3A20"))
        .GET()
        .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    JsonNode runtime = JSON.readTree(response.body()).get("runtime");

    assertEquals(200, response.statusCode());
    assertEquals("", runtime.get("sessionId").asText());
    assertEquals(ProbeRuntime.runtimeInstanceId(), runtime.get("runtimeInstanceId").asText());
    assertFalse(runtime.get("runtimeInstanceId").asText().isBlank());
  }

  @Test
  void analyzesFailureTraceWithoutReturningTheOriginalTrace() throws Exception {
    String payload = "{"
        + "\"trace\":\"com.example.OrderFailure: order 123456 failed\\n"
        + "  at com.example.OrderService.submit(OrderService.java:42)\\n"
        + "Caused by: java.lang.IllegalStateException: unavailable\""
        + "}";
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("http://127.0.0.1:" + server.port() + "/__probe/failure/analyze"))
        .header("content-type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(payload))
        .build();

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    JsonNode body = JSON.readTree(response.body());

    assertEquals(200, response.statusCode());
    assertEquals("com.example.OrderFailure", body.get("fingerprint").get("exceptionType").asText());
    assertEquals("java.lang.IllegalStateException", body.get("fingerprint").get("rootCauseType").asText());
    assertEquals(2, body.get("exceptionSections").size());
    assertEquals("com.example.OrderFailure", body.get("exceptionSections").get(0).get("exceptionType").asText());
    assertEquals("com.example.OrderService#submit:42", body.get("investigationCandidates").get(0)
        .get("strictLineKey").asText());
    assertFalse(response.body().contains("order 123456 failed"));
  }

  @Test
  void analyzesUncaughtThreadTraceAndSkipsDependencyFramesOutsideTheConfiguredScope() throws Exception {
    String payload = "{"
        + "\"trace\":\"Exception in thread \\\"main\\\" java.lang.IllegalStateException: failed\\n"
        + "  at io.netty.handler.codec.Decoder.decode(Decoder.java:42)\\n"
        + "  at com.example.OrderService.submit(OrderService.java:51)\""
        + "}";
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("http://127.0.0.1:" + server.port() + "/__probe/failure/analyze"))
        .header("content-type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(payload))
        .build();

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    JsonNode body = JSON.readTree(response.body());

    assertEquals(200, response.statusCode());
    assertEquals("java.lang.IllegalStateException", body.get("fingerprint").get("exceptionType").asText());
    assertEquals("com.example.OrderService#submit:51", body.get("investigationCandidates").get(0)
        .get("strictLineKey").asText());
    assertEquals("io.netty.handler.codec.Decoder", body.get("dependencyBoundary").get("className").asText());
  }

  @Test
  void verifiesAnObservedCaptureAgainstTheExpectedFailureFingerprint() throws Exception {
    String className = "com.example.OrderService";
    String methodName = "submit";
    IllegalStateException failure = new IllegalStateException("inventory unavailable");
    failure.setStackTrace(new StackTraceElement[] {
        new StackTraceElement(className, methodName, "OrderService.java", 42)
    });
    ProbeCaptureStore.captureByClassMethod(
        className,
        methodName,
        new Object[0],
        null,
        failure,
        System.currentTimeMillis(),
        System.currentTimeMillis(),
        -1L
    );
    CapturePreviewView capture = ProbeCaptureStore.getCapturePreviewForKey(className + "#" + methodName);
    String payload = "{"
        + "\"captureId\":\"" + capture.captureId + "\","
        + "\"expectedExceptionType\":\"java.lang.IllegalStateException\","
        + "\"expectedRootCauseType\":\"java.lang.IllegalStateException\","
        + "\"expectedNearestApplicationMethodKey\":\"" + className + "#" + methodName + "\""
        + "}";
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("http://127.0.0.1:" + server.port() + "/__probe/failure/verify"))
        .header("content-type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(payload))
        .build();

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    JsonNode body = JSON.readTree(response.body());

    assertEquals(200, response.statusCode());
    assertEquals("matched", body.get("outcome").asText());
  }

  private void assertRejectedArm(
      String sessionId,
      String targetKey,
      String expectedError,
      int expectedStatus,
      String expectedScope
  ) throws Exception {
    HttpResponse<String> response = arm(sessionId, targetKey);
    JsonNode body = JSON.readTree(response.body());
    assertEquals(expectedStatus, response.statusCode());
    assertEquals(expectedError, body.get("error").asText());
    if (expectedScope == null) {
      assertFalse(body.has("scope"));
    } else {
      assertEquals(expectedScope, body.get("scope").asText());
    }
    if (ProbeRuntime.actuationState().activeSessionCount() == 0) {
      assertEquals("disarmed", ProbeRuntime.actuationState().scopeState());
    }
  }

  private void assertExistingSessionRemainsArmed() {
    assertEquals(1, ProbeRuntime.actuationState().activeSessionCount());
    assertEquals("armed", ProbeRuntime.actuationState().scopeState());
    assertEquals("existing", ProbeRuntime.actuationState().sessionId());
    assertTrue(ProbeRuntime.isLineActuatableKey(CONDITIONAL_TARGET));
    assertEquals(CONDITIONAL_TARGET, ProbeRuntime.sessionState("existing").targetKey());
  }

  private HttpResponse<String> arm(String sessionId, String targetKey) throws Exception {
    String payload = "{"
        + "\"action\":\"arm\","
        + "\"sessionId\":\"" + sessionId + "\","
        + "\"actuatorId\":\"return_boolean\","
        + "\"targetKey\":\"" + targetKey + "\","
        + "\"returnBoolean\":true,"
        + "\"ttlMs\":60000"
        + "}";
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("http://127.0.0.1:" + server.port() + "/__probe/actuate"))
        .header("content-type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(payload))
        .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }
}
