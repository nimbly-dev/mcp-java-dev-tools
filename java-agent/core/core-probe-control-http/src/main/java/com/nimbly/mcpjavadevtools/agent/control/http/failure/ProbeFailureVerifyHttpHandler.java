package com.nimbly.mcpjavadevtools.agent.control.http.failure;

import com.nimbly.mcpjavadevtools.agent.control.http.ProbeHttpJson;
import com.nimbly.mcpjavadevtools.agent.control.http.ProbeHttpMapper;
import com.nimbly.mcpjavadevtools.agent.debug.api.DebugApi;
import com.nimbly.mcpjavadevtools.agent.contract.ContractVersion;
import com.nimbly.mcpjavadevtools.agent.control.auth.ProbeAuth;
import com.nimbly.mcpjavadevtools.agent.control.http.model.ProbeHttpPayloads;
import com.nimbly.mcpjavadevtools.agent.control.http.model.ProbeHttpRequests;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
public final class ProbeFailureVerifyHttpHandler implements HttpHandler {
  private static final String CONTRACT_VERSION = ContractVersion.value();
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
        ProbeHttpJson.writeJson(exchange, 405, new ProbeHttpPayloads.ErrorEnvelope("method_not_allowed", null));
        return;
      }
      if (!ProbeAuth.authorizeObserve(exchange)) {
        ProbeHttpJson.writeJson(exchange, 401, new ProbeHttpPayloads.ErrorEnvelope("unauthorized", "observe"));
        return;
      }
      ProbeHttpRequests.FailureVerifyRequest request = ProbeHttpJson.readBodyJson(
          exchange.getRequestBody(), ProbeHttpRequests.FailureVerifyRequest.class);
      if (!hasVerificationInput(request)) {
        ProbeHttpJson.writeJson(exchange, 400, new ProbeHttpPayloads.ErrorEnvelope("invalid_failure_verify", null));
        return;
      }
      DebugApi.CaptureRecord capture = DebugApi.captureById(request.captureId().trim());
      if (capture == null) {
        ProbeHttpJson.writeJson(exchange, 404, new ProbeHttpPayloads.CaptureNotFoundEnvelope(
            CONTRACT_VERSION, "capture_not_found", request.captureId().trim()));
        return;
      }
      DebugApi.FailureComparison comparison = DebugApi.compare(
          request.expectedExceptionType().trim(),
          request.expectedRootCauseType().trim(),
          request.expectedNearestApplicationMethodKey().trim(),
          DebugApi.failureFingerprintByCaptureId(request.captureId().trim()));
      ProbeHttpJson.writeJson(exchange, 200, ProbeHttpMapper.buildFailureVerificationEnvelope(
          CONTRACT_VERSION, comparison));
    }

    private static boolean hasVerificationInput(ProbeHttpRequests.FailureVerifyRequest request) {
      if (request == null) return false;
      return notBlank(request.captureId())
          && notBlank(request.expectedExceptionType())
          && notBlank(request.expectedRootCauseType())
          && notBlank(request.expectedNearestApplicationMethodKey());
    }

    private static boolean notBlank(String value) {
      return value != null && !value.isBlank();
    }
  }

