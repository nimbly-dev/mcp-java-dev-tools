package com.nimbly.mcpjavadevtools.agent.control.http;

import com.nimbly.mcpjavadevtools.agent.capture.CaptureRecordView;
import com.nimbly.mcpjavadevtools.agent.capture.ProbeCaptureStore;
import com.nimbly.mcpjavadevtools.agent.contract.ContractVersion;
import com.nimbly.mcpjavadevtools.agent.control.auth.ProbeAuth;
import com.nimbly.mcpjavadevtools.agent.control.http.model.ProbeHttpPayloads;
import com.nimbly.mcpjavadevtools.agent.control.http.model.ProbeHttpRequests;
import com.nimbly.mcpjavadevtools.agent.profiler.ProbeProfilerRegistry;
import com.nimbly.mcpjavadevtools.agent.profiler.model.ProfilerStartRequest;
import com.nimbly.mcpjavadevtools.agent.profiler.model.ProfilerStateSnapshot;
import com.nimbly.mcpjavadevtools.agent.profiler.model.ProfilerStopRequest;
import com.nimbly.mcpjavadevtools.agent.profiler.model.ProfilerStopResult;
import com.nimbly.mcpjavadevtools.agent.runtime.ProbeRuntime;
import com.nimbly.mcpjavadevtools.agent.runtime.model.ActuationState;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ProbeHttpServer {
  private static final String CONTRACT_VERSION = ContractVersion.value();

  private final HttpServer server;

  private ProbeHttpServer(HttpServer server) {
    this.server = server;
  }

  public static ProbeHttpServer start(String host, int port) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 16);
    server.createContext("/__probe/status", new StatusHandler());
    server.createContext("/__probe/reset", new ResetHandler());
    server.createContext("/__probe/actuate", new ActuateHandler());
    server.createContext("/__probe/capture", new CaptureHandler());
    server.createContext("/__probe/profiler", new ProfilerHandler());
    server.setExecutor(null);
    server.start();
    return new ProbeHttpServer(server);
  }

  public HttpServer rawServer() {
    return server;
  }

  private static final class StatusHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      String method = exchange.getRequestMethod();
      if (!"GET".equalsIgnoreCase(method) && !"POST".equalsIgnoreCase(method)) {
        ProbeHttpJson.writeJson(exchange, 405, new ProbeHttpPayloads.ErrorEnvelope("method_not_allowed", null));
        return;
      }
      if (!ProbeAuth.authorizeObserve(exchange)) {
        ProbeHttpJson.writeJson(exchange, 401, new ProbeHttpPayloads.ErrorEnvelope("unauthorized", "observe"));
        return;
      }

      if ("GET".equalsIgnoreCase(method)) {
        String key = ProbeHttpJson.queryParam(exchange.getRequestURI(), "key");
        if (key == null || key.isEmpty()) {
          ProbeHttpJson.writeJson(exchange, 400, new ProbeHttpPayloads.ErrorEnvelope("missing_key", null));
          return;
        }
        ProbeHttpJson.writeJson(exchange, 200, ProbeHttpMapper.buildStatusEnvelope(CONTRACT_VERSION, key));
        return;
      }

      ProbeHttpRequests.StatusBatchRequest request =
          ProbeHttpJson.readBodyJson(exchange.getRequestBody(), ProbeHttpRequests.StatusBatchRequest.class);
      List<String> keys =
          ProbeHttpJson.normalizeDistinctKeys(request.keys() == null ? List.of() : request.keys());
      if (keys.isEmpty()) {
        ProbeHttpJson.writeJson(exchange, 400, new ProbeHttpPayloads.ErrorEnvelope("missing_keys", null));
        return;
      }

      List<ProbeHttpPayloads.StatusBatchRow> rows = new ArrayList<>();
      for (String key : keys) {
        rows.add(ProbeHttpMapper.buildStatusBatchRow(key));
      }
      ProbeHttpJson.writeJson(
          exchange,
          200,
          new ProbeHttpPayloads.StatusBatchEnvelope(CONTRACT_VERSION, true, rows.size(), rows)
      );
    }
  }

  private static final class ResetHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
        ProbeHttpJson.writeJson(exchange, 405, new ProbeHttpPayloads.ErrorEnvelope("method_not_allowed", null));
        return;
      }
      if (!ProbeAuth.authorizeActuate(exchange)) {
        ProbeHttpJson.writeJson(exchange, 401, new ProbeHttpPayloads.ErrorEnvelope("unauthorized", "actuate"));
        return;
      }
      String keyFromQuery = ProbeHttpJson.queryParam(exchange.getRequestURI(), "key");
      ProbeHttpRequests.ResetRequest request =
          ProbeHttpJson.readBodyJson(exchange.getRequestBody(), ProbeHttpRequests.ResetRequest.class);
      String keyFromBody = request.key();
      List<String> keys = ProbeHttpJson.normalizeDistinctKeys(request.keys() == null ? List.of() : request.keys());
      String className = request.className();

      String selectedKey = (keyFromQuery != null && !keyFromQuery.isBlank()) ? keyFromQuery : keyFromBody;
      boolean hasKey = selectedKey != null && !selectedKey.isBlank();
      boolean hasKeys = !keys.isEmpty();
      boolean hasClass = className != null && !className.isBlank();
      int selectorCount = 0;
      if (hasKey) selectorCount++;
      if (hasKeys) selectorCount++;
      if (hasClass) selectorCount++;
      if (selectorCount == 0) {
        ProbeHttpJson.writeJson(exchange, 400, new ProbeHttpPayloads.ErrorEnvelope("missing_selector", null));
        return;
      }
      if (selectorCount > 1) {
        ProbeHttpJson.writeJson(exchange, 400, new ProbeHttpPayloads.ErrorEnvelope("conflicting_selector", null));
        return;
      }

      if (hasKey) {
        String key = selectedKey.trim();
        ProbeRuntime.reset(key);
        ProbeCaptureStore.resetByKey(key);
        ProbeHttpJson.writeJson(exchange, 200, ProbeHttpMapper.buildResetEnvelope(CONTRACT_VERSION, key));
        return;
      }

      List<String> resolvedKeys = hasKeys
          ? keys
          : ProbeHttpJson.normalizeDistinctKeys(ProbeRuntime.lineKeysForClass(className.trim()));
      List<ProbeHttpPayloads.ResetRow> rows = new ArrayList<>();
      for (String key : resolvedKeys) {
        ProbeRuntime.reset(key);
        ProbeCaptureStore.resetByKey(key);
        rows.add(ProbeHttpMapper.buildResetRow(key));
      }
      ProbeHttpJson.writeJson(
          exchange,
          200,
          new ProbeHttpPayloads.ResetBatchEnvelope(
              CONTRACT_VERSION,
              true,
              hasClass ? "className" : "keys",
              hasClass ? className.trim() : null,
              rows.size(),
              rows,
              hasClass && rows.isEmpty() ? "class_not_found" : null
          )
      );
    }
  }

  private static final class ActuateHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
        ProbeHttpJson.writeJson(exchange, 405, new ProbeHttpPayloads.ErrorEnvelope("method_not_allowed", null));
        return;
      }
      if (!ProbeAuth.authorizeActuate(exchange)) {
        ProbeHttpJson.writeJson(exchange, 401, new ProbeHttpPayloads.ErrorEnvelope("unauthorized", "actuate"));
        return;
      }

      ProbeHttpRequests.ActuateRequest request =
          ProbeHttpJson.readBodyJson(exchange.getRequestBody(), ProbeHttpRequests.ActuateRequest.class);
      String action = request.action() == null ? "" : request.action().trim().toLowerCase();
      String sessionId = request.sessionId() == null ? "" : request.sessionId().trim();
      String actuatorId = request.actuatorId();
      String targetKey = request.targetKey() == null ? "" : request.targetKey().trim();
      Boolean returnBoolean = request.returnBoolean();
      Long ttlMs = request.ttlMs();

      if (sessionId.isBlank()) {
        ProbeHttpJson.writeJson(exchange, 400, new ProbeHttpPayloads.ErrorEnvelope("missing_session_id", null));
        return;
      }
      if (!"arm".equals(action) && !"disarm".equals(action)) {
        ProbeHttpJson.writeJson(exchange, 400, new ProbeHttpPayloads.ErrorEnvelope("invalid_action", null));
        return;
      }

      ActuationState updated;
      if ("disarm".equals(action)) {
        if (!targetKey.isBlank() || returnBoolean != null || ttlMs != null) {
          ProbeHttpJson.writeJson(exchange, 400, new ProbeHttpPayloads.ErrorEnvelope("disarm_fields_not_allowed", null));
          return;
        }
        updated = ProbeRuntime.disarmSession(sessionId);
      } else {
        if (targetKey.isBlank() || returnBoolean == null || ttlMs == null) {
          ProbeHttpJson.writeJson(exchange, 400, new ProbeHttpPayloads.ErrorEnvelope("arm_fields_required", null));
          return;
        }
        if (!ProbeRuntime.isLineKey(targetKey)) {
          ProbeHttpJson.writeJson(exchange, 400, new ProbeHttpPayloads.ErrorEnvelope("invalid_target_key", null));
          return;
        }
        if (ttlMs < ProbeRuntime.minTtlMs() || ttlMs > ProbeRuntime.maxTtlMs()) {
          ProbeHttpJson.writeJson(
              exchange,
              400,
              new ProbeHttpPayloads.ErrorEnvelope(
                  "ttl_out_of_range[" + ProbeRuntime.minTtlMs() + "," + ProbeRuntime.maxTtlMs() + "]",
                  null
              )
          );
          return;
        }
        updated = ProbeRuntime.armSession(
            sessionId,
            actuatorId,
            targetKey,
            returnBoolean,
            ttlMs
        );
      }

      ProbeHttpJson.writeJson(
          exchange,
          200,
          new ProbeHttpPayloads.ActuateEnvelope(
              CONTRACT_VERSION,
              true,
              action,
              updated.mode(),
              updated.sessionId(),
              updated.actuatorId(),
              updated.targetKey(),
              updated.returnBoolean(),
              ttlMs,
              updated.expiresAtEpoch(),
              updated.scopeState()
          )
      );
    }
  }

  private static final class CaptureHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
        ProbeHttpJson.writeJson(exchange, 405, new ProbeHttpPayloads.ErrorEnvelope("method_not_allowed", null));
        return;
      }
      if (!ProbeAuth.authorizeObserve(exchange)) {
        ProbeHttpJson.writeJson(exchange, 401, new ProbeHttpPayloads.ErrorEnvelope("unauthorized", "observe"));
        return;
      }
      String captureId = ProbeHttpJson.queryParam(exchange.getRequestURI(), "captureId");
      if (captureId == null || captureId.isBlank()) {
        ProbeHttpJson.writeJson(exchange, 400, new ProbeHttpPayloads.ErrorEnvelope("missing_capture_id", null));
        return;
      }

      CaptureRecordView capture = ProbeCaptureStore.getCaptureById(captureId.trim());
      if (capture == null) {
        ProbeHttpJson.writeJson(
            exchange,
            404,
            new ProbeHttpPayloads.CaptureNotFoundEnvelope(CONTRACT_VERSION, "capture_not_found", captureId.trim())
        );
        return;
      }

      ProbeHttpJson.writeJson(exchange, 200, ProbeHttpMapper.buildCaptureEnvelope(CONTRACT_VERSION, capture));
    }
  }

  private static final class ProfilerHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      String method = exchange.getRequestMethod();
      if ("GET".equalsIgnoreCase(method)) {
        if (!ProbeAuth.authorizeObserve(exchange)) {
          ProbeHttpJson.writeJson(exchange, 401, new ProbeHttpPayloads.ErrorEnvelope("unauthorized", "observe"));
          return;
        }
        String action = ProbeHttpJson.queryParam(exchange.getRequestURI(), "action");
        if ("download".equalsIgnoreCase(action)) {
          String sessionId = ProbeHttpJson.queryParam(exchange.getRequestURI(), "sessionId");
          streamProfilerOutput(exchange, sessionId);
          return;
        }
        ProfilerStateSnapshot state = ProbeProfilerRegistry.active().state();
        ProbeHttpJson.writeJson(
            exchange,
            200,
            ProbeHttpMapper.buildProfilerStateEnvelope(CONTRACT_VERSION, "status", state)
        );
        return;
      }
      if (!"POST".equalsIgnoreCase(method)) {
        ProbeHttpJson.writeJson(exchange, 405, new ProbeHttpPayloads.ErrorEnvelope("method_not_allowed", null));
        return;
      }
      if (!ProbeAuth.authorizeActuate(exchange)) {
        ProbeHttpJson.writeJson(exchange, 401, new ProbeHttpPayloads.ErrorEnvelope("unauthorized", "actuate"));
        return;
      }
      ProbeHttpRequests.ProfilerRequest request =
          ProbeHttpJson.readBodyJson(exchange.getRequestBody(), ProbeHttpRequests.ProfilerRequest.class);
      String action = request.action() == null ? "" : request.action().trim().toLowerCase();
      if ("start".equals(action)) {
        ProfilerStateSnapshot state = ProbeProfilerRegistry.active().start(
            new ProfilerStartRequest(
                request.sessionId(),
                request.event(),
                request.intervalNanos(),
                request.outputPath(),
                request.outputFormat()
            )
        );
        ProbeHttpJson.writeJson(
            exchange,
            200,
            ProbeHttpMapper.buildProfilerStateEnvelope(CONTRACT_VERSION, "start", state)
        );
        return;
      }
      if ("stop".equals(action)) {
        ProfilerStopResult result = ProbeProfilerRegistry.active().stop(
            new ProfilerStopRequest(
                request.sessionId(),
                request.outputPath(),
                request.outputFormat()
            )
        );
        ProbeHttpJson.writeJson(
            exchange,
            200,
            ProbeHttpMapper.buildProfilerStopEnvelope(CONTRACT_VERSION, "stop", result)
        );
        return;
      }
      if ("reset".equals(action)) {
        ProfilerStateSnapshot state = ProbeProfilerRegistry.active().reset();
        ProbeHttpJson.writeJson(
            exchange,
            200,
            ProbeHttpMapper.buildProfilerStateEnvelope(CONTRACT_VERSION, "reset", state)
        );
        return;
      }
      if ("status".equals(action)) {
        ProfilerStateSnapshot state = ProbeProfilerRegistry.active().state();
        ProbeHttpJson.writeJson(
            exchange,
            200,
            ProbeHttpMapper.buildProfilerStateEnvelope(CONTRACT_VERSION, "status", state)
        );
        return;
      }
      ProbeHttpJson.writeJson(exchange, 400, new ProbeHttpPayloads.ErrorEnvelope("invalid_action", null));
    }

    private static void streamProfilerOutput(HttpExchange exchange, String requestedSessionId) throws IOException {
      ProfilerStateSnapshot state = ProbeProfilerRegistry.active().state();
      if (state.outputPath() == null || state.outputPath().isBlank()) {
        ProbeHttpJson.writeJson(exchange, 404, new ProbeHttpPayloads.ErrorEnvelope("profiler_output_missing", null));
        return;
      }
      if (requestedSessionId != null && !requestedSessionId.isBlank()) {
        String activeSessionId = state.sessionId();
        if (activeSessionId == null || activeSessionId.isBlank() || !activeSessionId.equals(requestedSessionId.trim())) {
          ProbeHttpJson.writeJson(exchange, 409, new ProbeHttpPayloads.ErrorEnvelope("profiler_session_mismatch", null));
          return;
        }
      }
      Path outputPath = Path.of(state.outputPath()).toAbsolutePath().normalize();
      if (!Files.isRegularFile(outputPath)) {
        ProbeHttpJson.writeJson(exchange, 404, new ProbeHttpPayloads.ErrorEnvelope("profiler_output_not_found", null));
        return;
      }
      exchange.getResponseHeaders().set("content-type", "application/octet-stream");
      exchange.getResponseHeaders().set(
          "content-disposition",
          "attachment; filename=\"" + outputPath.getFileName() + "\""
      );
      exchange.sendResponseHeaders(200, Files.size(outputPath));
      try (InputStream input = Files.newInputStream(outputPath)) {
        input.transferTo(exchange.getResponseBody());
      } finally {
        exchange.close();
      }
    }
  }
}
