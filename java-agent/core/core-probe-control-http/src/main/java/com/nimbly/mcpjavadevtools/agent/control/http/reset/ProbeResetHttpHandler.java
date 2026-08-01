package com.nimbly.mcpjavadevtools.agent.control.http.reset;

import com.nimbly.mcpjavadevtools.agent.control.http.ProbeHttpJson;
import com.nimbly.mcpjavadevtools.agent.control.http.ProbeHttpMapper;
import com.nimbly.mcpjavadevtools.agent.debug.api.DebugApi;
import com.nimbly.mcpjavadevtools.agent.contract.ContractVersion;
import com.nimbly.mcpjavadevtools.agent.control.auth.ProbeAuth;
import com.nimbly.mcpjavadevtools.agent.control.http.model.ProbeHttpPayloads;
import com.nimbly.mcpjavadevtools.agent.control.http.model.ProbeHttpRequests;
import com.nimbly.mcpjavadevtools.agent.runtime.api.RuntimeApi;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
public final class ProbeResetHttpHandler implements HttpHandler {
  private static final String CONTRACT_VERSION = ContractVersion.value();
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
        RuntimeApi.reset(key);
        DebugApi.resetByKey(key);
        ProbeHttpJson.writeJson(exchange, 200, ProbeHttpMapper.buildResetEnvelope(CONTRACT_VERSION, key));
        return;
      }

      List<String> resolvedKeys = hasKeys
          ? keys
          : ProbeHttpJson.normalizeDistinctKeys(RuntimeApi.lineKeysForClass(className.trim()));
      List<ProbeHttpPayloads.ResetRow> rows = new ArrayList<>();
      for (String key : resolvedKeys) {
        RuntimeApi.reset(key);
        DebugApi.resetByKey(key);
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

