package com.nimbly.mcpjavadevtools.agent.control.http.profiler;

import com.nimbly.mcpjavadevtools.agent.control.http.ProbeHttpJson;
import com.nimbly.mcpjavadevtools.agent.control.http.ProbeHttpMapper;
import com.nimbly.mcpjavadevtools.agent.control.http.ProbeProfilerHttpResponses;
import com.nimbly.mcpjavadevtools.agent.contract.ContractVersion;
import com.nimbly.mcpjavadevtools.agent.control.auth.ProbeAuth;
import com.nimbly.mcpjavadevtools.agent.control.http.model.ProbeHttpPayloads;
import com.nimbly.mcpjavadevtools.agent.control.http.model.ProbeHttpRequests;
import com.nimbly.mcpjavadevtools.agent.profiler.api.ProfilerApi;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
public final class ProbeProfilerHttpHandler implements HttpHandler {
  private static final String CONTRACT_VERSION = ContractVersion.value();
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
        ProfilerApi.State state = ProfilerApi.state();
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
        ProfilerApi.State state = ProfilerApi.start(
            new ProfilerApi.StartRequest(
                request.provider(),
                request.sessionId(),
                request.event(),
                request.intervalNanos(),
                request.outputPath(),
                request.outputFormat()
            )
        );
        if (ProbeProfilerHttpResponses.shouldFailClosedOnStart(state)) {
          ProbeHttpJson.writeJson(
              exchange,
              ProbeProfilerHttpResponses.startStatusCode(state),
              ProbeProfilerHttpResponses.startErrorEnvelope(state)
          );
          return;
        }
        ProbeHttpJson.writeJson(
            exchange,
            200,
            ProbeHttpMapper.buildProfilerStateEnvelope(CONTRACT_VERSION, "start", state)
        );
        return;
      }
      if ("stop".equals(action)) {
        ProfilerApi.StopResult result = ProfilerApi.stop(
            new ProfilerApi.StopRequest(
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
        ProfilerApi.State state = ProfilerApi.reset();
        ProbeHttpJson.writeJson(
            exchange,
            200,
            ProbeHttpMapper.buildProfilerStateEnvelope(CONTRACT_VERSION, "reset", state)
        );
        return;
      }
      if ("status".equals(action)) {
        ProfilerApi.State state = ProfilerApi.state();
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
      ProfilerApi.State state = ProfilerApi.state();
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

