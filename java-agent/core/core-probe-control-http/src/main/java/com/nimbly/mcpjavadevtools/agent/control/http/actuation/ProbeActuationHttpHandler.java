package com.nimbly.mcpjavadevtools.agent.control.http.actuation;

import com.nimbly.mcpjavadevtools.agent.control.http.ProbeHttpJson;
import com.nimbly.mcpjavadevtools.agent.contract.ContractVersion;
import com.nimbly.mcpjavadevtools.agent.control.auth.ProbeAuth;
import com.nimbly.mcpjavadevtools.agent.control.http.model.ProbeHttpPayloads;
import com.nimbly.mcpjavadevtools.agent.control.http.model.ProbeHttpRequests;
import com.nimbly.mcpjavadevtools.agent.runtime.api.RuntimeApi;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
public final class ProbeActuationHttpHandler implements HttpHandler {
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

    RuntimeApi.ActuationState updated;
      if ("disarm".equals(action)) {
        if (!targetKey.isBlank() || returnBoolean != null || ttlMs != null) {
          ProbeHttpJson.writeJson(exchange, 400, new ProbeHttpPayloads.ErrorEnvelope("disarm_fields_not_allowed", null));
          return;
        }
        updated = RuntimeApi.disarmSession(sessionId);
      } else {
        if (targetKey.isBlank() || returnBoolean == null || ttlMs == null) {
          ProbeHttpJson.writeJson(exchange, 400, new ProbeHttpPayloads.ErrorEnvelope("arm_fields_required", null));
          return;
        }
        if (!RuntimeApi.isLineKey(targetKey)) {
          ProbeHttpJson.writeJson(exchange, 400, new ProbeHttpPayloads.ErrorEnvelope("invalid_target_key", null));
          return;
        }
        if (!RuntimeApi.isLineResolvableKey(targetKey)) {
          ProbeHttpJson.writeJson(exchange, 400, new ProbeHttpPayloads.ErrorEnvelope("invalid_line_target", "actuate"));
          return;
        }
        if (!RuntimeApi.isLineActuatableKey(targetKey)) {
          ProbeHttpJson.writeJson(exchange, 400, new ProbeHttpPayloads.ErrorEnvelope("target_line_not_actuatable", "actuate"));
          return;
        }
        if (ttlMs < RuntimeApi.minTtlMs() || ttlMs > RuntimeApi.maxTtlMs()) {
          ProbeHttpJson.writeJson(
              exchange,
              400,
              new ProbeHttpPayloads.ErrorEnvelope(
                  "ttl_out_of_range[" + RuntimeApi.minTtlMs() + "," + RuntimeApi.maxTtlMs() + "]",
                  null
              )
          );
          return;
        }
        updated = RuntimeApi.armSession(
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

