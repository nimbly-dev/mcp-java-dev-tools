package com.nimbly.mcpjavadevtools.agent.control.http.status;

import com.nimbly.mcpjavadevtools.agent.control.http.ProbeHttpJson;
import com.nimbly.mcpjavadevtools.agent.control.http.ProbeHttpMapper;
import com.nimbly.mcpjavadevtools.agent.contract.ContractVersion;
import com.nimbly.mcpjavadevtools.agent.control.auth.ProbeAuth;
import com.nimbly.mcpjavadevtools.agent.control.http.model.ProbeHttpPayloads;
import com.nimbly.mcpjavadevtools.agent.control.http.model.ProbeHttpRequests;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
public final class ProbeStatusHttpHandler implements HttpHandler {
  private static final String CONTRACT_VERSION = ContractVersion.value();
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


