package com.nimbly.mcpjavadevtools.agent.control.http.capture;

import com.nimbly.mcpjavadevtools.agent.control.http.ProbeHttpJson;
import com.nimbly.mcpjavadevtools.agent.control.http.ProbeHttpMapper;
import com.nimbly.mcpjavadevtools.agent.debug.api.DebugApi;
import com.nimbly.mcpjavadevtools.agent.contract.ContractVersion;
import com.nimbly.mcpjavadevtools.agent.control.auth.ProbeAuth;
import com.nimbly.mcpjavadevtools.agent.control.http.model.ProbeHttpPayloads;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
public final class ProbeCaptureHttpHandler implements HttpHandler {
  private static final String CONTRACT_VERSION = ContractVersion.value();
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

      DebugApi.CaptureRecord capture = DebugApi.captureById(captureId.trim());
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

