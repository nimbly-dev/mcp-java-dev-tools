package com.nimbly.mcpjavadevtools.agent.control.http.correlation;

import com.nimbly.mcpjavadevtools.agent.control.http.ProbeHttpJson;
import com.nimbly.mcpjavadevtools.agent.contract.ContractVersion;
import com.nimbly.mcpjavadevtools.agent.control.auth.ProbeAuth;
import com.nimbly.mcpjavadevtools.agent.control.http.model.ProbeHttpPayloads;
import com.nimbly.mcpjavadevtools.agent.runtime.api.RuntimeApi;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
public final class ProbeCorrelationStatusHttpHandler implements HttpHandler {
  private static final String CONTRACT_VERSION = ContractVersion.value();
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
        ProbeHttpJson.writeJson(exchange, 405,
            new ProbeHttpPayloads.ErrorEnvelope("method_not_allowed", null));
        return;
      }
      if (!ProbeAuth.authorizeObserve(exchange)) {
        ProbeHttpJson.writeJson(exchange, 401,
            new ProbeHttpPayloads.ErrorEnvelope("unauthorized", "observe"));
        return;
      }
      String requestedSessionId = ProbeHttpJson.queryParam(
          exchange.getRequestURI(), "sessionId");
      RuntimeApi.KclBindingStatus status = RuntimeApi.kclBindingStatus();
      Map<String, Object> response = new LinkedHashMap<>();
      response.put("contractVersion", CONTRACT_VERSION);
      response.put("outcome", status.outcome());
      response.put("reasonCode", status.reasonCode());
      response.put("correlationSessionId", status.correlationSessionId());
      response.put("correlationExecutionId", status.correlationExecutionId());
      response.put("observedAtEpochMs", status.observedAtEpochMs());
      response.put("sessionMatches", requestedSessionId == null
          || requestedSessionId.trim().equals(status.correlationSessionId()));
      ProbeHttpJson.writeJson(exchange, 200, response);
    }
  }

