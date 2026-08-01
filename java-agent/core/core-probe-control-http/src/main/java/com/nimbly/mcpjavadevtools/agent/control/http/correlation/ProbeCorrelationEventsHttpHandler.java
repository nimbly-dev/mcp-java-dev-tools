package com.nimbly.mcpjavadevtools.agent.control.http.correlation;

import com.nimbly.mcpjavadevtools.agent.control.http.ProbeHttpJson;
import com.nimbly.mcpjavadevtools.agent.contract.ContractVersion;
import com.nimbly.mcpjavadevtools.agent.control.auth.ProbeAuth;
import com.nimbly.mcpjavadevtools.agent.control.http.model.ProbeHttpPayloads;
import com.nimbly.mcpjavadevtools.agent.runtime.api.RuntimeApi;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
public final class ProbeCorrelationEventsHttpHandler implements HttpHandler {
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
      String sessionId = ProbeHttpJson.queryParam(exchange.getRequestURI(), "sessionId");
      if (sessionId == null || sessionId.isBlank()) {
        ProbeHttpJson.writeJson(exchange, 400, new ProbeHttpPayloads.ErrorEnvelope("missing_session_id", null));
        return;
      }
      long afterSequence = parseLongQuery(exchange, "afterSequence", 0L);
      int limit = (int) parseLongQuery(exchange, "limit", 256L);
      if (afterSequence < 0 || limit < 1 || limit > 10_000) {
        ProbeHttpJson.writeJson(exchange, 400, new ProbeHttpPayloads.ErrorEnvelope("invalid_cursor", null));
        return;
      }
      RuntimeApi.RuntimeLineHitEventPage page = RuntimeApi.runtimeLineHitEventPage(
          sessionId.trim(),
          afterSequence,
          limit
      );
      List<Map<String, Object>> payloadEvents = new ArrayList<>();
      for (RuntimeApi.RuntimeLineHitEvent event : page.events()) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", event.lineKey() + "@" + event.sequence());
        payload.put("sequence", event.sequence());
        payload.put("lastSequence", event.lastSequence());
        payload.put("hitCount", event.hitCount());
        payload.put("correlationSessionId", event.correlationSessionId());
        payload.put("correlationExecutionId", event.correlationExecutionId());
        payload.put("probeId", event.probeId());
        payload.put("runtimeInstanceId", event.runtimeInstanceId());
        payload.put("lineKey", event.lineKey());
        payload.put("timestampEpochMs", event.timestampEpochMs());
        payload.put("firstTimestampEpochMs", event.firstTimestampEpochMs());
        payload.put("keyType", event.keyType());
        payload.put("keyFingerprint", event.keyFingerprint());
        payload.put("eventType", "runtime_line_hit");
        payloadEvents.add(payload);
      }
      Map<String, Object> response = new LinkedHashMap<>();
      response.put("contractVersion", CONTRACT_VERSION);
      response.put("correlationSessionId", sessionId.trim());
      response.put("streamRuntimeInstanceId", RuntimeApi.runtimeInstanceId());
      response.put("streamResetEpoch", RuntimeApi.runtimeLineHitStreamResetEpoch());
      response.put("afterSequence", afterSequence);
      response.put("lastDeliveredSequence", page.lastDeliveredSequence());
      response.put("highWaterSequence", RuntimeApi.runtimeLineHitNextSequence());
      response.put("hasMore", page.hasMore());
      response.put("events", payloadEvents);
      ProbeHttpJson.writeJson(exchange, 200, response);
    }

    private static long parseLongQuery(HttpExchange exchange, String key, long defaultValue) {
      String value = ProbeHttpJson.queryParam(exchange.getRequestURI(), key);
      if (value == null || value.isBlank()) return defaultValue;
      try {
        return Long.parseLong(value);
      } catch (NumberFormatException ignored) {
        return -1L;
      }
    }
  }

