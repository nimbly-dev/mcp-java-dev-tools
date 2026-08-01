package com.nimbly.mcpjavadevtools.agent.control.http.failure;

import com.nimbly.mcpjavadevtools.agent.control.http.ProbeHttpJson;
import com.nimbly.mcpjavadevtools.agent.control.http.ProbeHttpMapper;
import com.nimbly.mcpjavadevtools.agent.contract.ContractVersion;
import com.nimbly.mcpjavadevtools.agent.control.auth.ProbeAuth;
import com.nimbly.mcpjavadevtools.agent.control.http.model.ProbeHttpPayloads;
import com.nimbly.mcpjavadevtools.agent.control.http.model.ProbeHttpRequests;
import com.nimbly.mcpjavadevtools.agent.debug.api.DebugApi;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
public final class ProbeFailureAnalyzeHttpHandler implements HttpHandler {
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
      ProbeHttpRequests.FailureAnalyzeRequest request = ProbeHttpJson.readBodyJson(
          exchange.getRequestBody(), ProbeHttpRequests.FailureAnalyzeRequest.class);
      if (request.trace() == null || request.trace().isBlank()) {
        ProbeHttpJson.writeJson(exchange, 400, new ProbeHttpPayloads.ErrorEnvelope("missing_trace", null));
        return;
      }
      DebugApi.FailureTraceAnalysis analysis = DebugApi.analyze(request.trace());
      ProbeHttpJson.writeJson(exchange, 200, ProbeHttpMapper.buildFailureAnalysisEnvelope(CONTRACT_VERSION, analysis));
    }
  }

