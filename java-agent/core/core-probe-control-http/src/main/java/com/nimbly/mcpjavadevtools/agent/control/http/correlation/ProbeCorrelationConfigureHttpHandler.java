package com.nimbly.mcpjavadevtools.agent.control.http.correlation;

import com.nimbly.mcpjavadevtools.agent.control.http.ProbeHttpJson;
import com.nimbly.mcpjavadevtools.agent.contract.ContractVersion;
import com.nimbly.mcpjavadevtools.agent.control.auth.ProbeAuth;
import com.nimbly.mcpjavadevtools.agent.control.http.model.ProbeHttpPayloads;
import com.nimbly.mcpjavadevtools.agent.control.http.model.ProbeHttpRequests;
import com.nimbly.mcpjavadevtools.agent.runtime.api.RuntimeApi;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
public final class ProbeCorrelationConfigureHttpHandler implements HttpHandler {
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
      ProbeHttpRequests.CorrelationConfigRequest request =
          ProbeHttpJson.readBodyJson(exchange.getRequestBody(), ProbeHttpRequests.CorrelationConfigRequest.class);
      if (Boolean.TRUE.equals(request.release())) {
        boolean released = RuntimeApi.releaseCorrelationContext(request.executionId());
        ProbeHttpJson.writeJson(exchange, released ? 200 : 409,
            new ProbeHttpPayloads.ErrorEnvelope(released ? "released" : "correlation_lease_owner_mismatch", null));
        return;
      }
      if (request.sessionId() == null || request.sessionId().isBlank()
          || request.executionId() == null || request.executionId().isBlank()
          || request.eventKeyPath() == null || !request.eventKeyPath().startsWith("$.")) {
        ProbeHttpJson.writeJson(exchange, 400, new ProbeHttpPayloads.ErrorEnvelope("invalid_correlation_config", null));
        return;
      }
      BoundaryValidationResult boundaryValidation = normalizeConsumerBoundaries(
          request.consumerBoundaries());
      if (!boundaryValidation.valid()) {
        ProbeHttpJson.writeJson(exchange, 400,
            new ProbeHttpPayloads.ErrorEnvelope(boundaryValidation.reasonCode(), "actuate"));
        return;
      }
      RuntimeApi.CorrelationConfigureResult configuration = RuntimeApi.tryConfigureCorrelationContext(
          request.sessionId().trim(), request.executionId().trim(), request.eventKeyPath().trim(),
          request.leaseTtlMs() == null ? 300_000L : request.leaseTtlMs(),
          boundaryValidation.boundaries());
      if (!configuration.configured()) {
        ProbeHttpJson.writeJson(exchange, 409,
            new ProbeHttpPayloads.ErrorEnvelope(configuration.reasonCode(), "actuate"));
        return;
      }
      Map<String, Object> response = new LinkedHashMap<>();
      response.put("contractVersion", CONTRACT_VERSION);
      response.put("configured", true);
      response.put("correlationSessionId", request.sessionId().trim());
      response.put("correlationExecutionId", request.executionId().trim());
      response.put("eventKeyPath", request.eventKeyPath().trim());
      response.put("consumerBoundaryCount", boundaryValidation.boundaries().size());
      ProbeHttpJson.writeJson(exchange, 200, response);
    }

  private static BoundaryValidationResult normalizeConsumerBoundaries(
      List<ProbeHttpRequests.CorrelationConsumerBoundaryRequest> requests) {
    if (requests == null || requests.isEmpty()) return BoundaryValidationResult.valid(List.of());
    List<RuntimeApi.CorrelationConsumerBoundary> boundaries = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    for (ProbeHttpRequests.CorrelationConsumerBoundaryRequest request : requests) {
      if (request == null || request.id() == null || !ids.add(request.id().trim())) {
        return BoundaryValidationResult.invalid("invalid_correlation_consumer_boundary");
      }
      if (request.eventArgumentIndex() == null) {
        return BoundaryValidationResult.invalid("invalid_correlation_consumer_boundary");
      }
      try {
        boundaries.add(new RuntimeApi.CorrelationConsumerBoundary(
            request.id(),
            request.fqcn(),
            request.method(),
            request.parameterTypes(),
            request.eventArgumentIndex()));
      } catch (RuntimeException exception) {
        return BoundaryValidationResult.invalid("invalid_correlation_consumer_boundary");
      }
    }
    return BoundaryValidationResult.valid(boundaries);
  }

  private record BoundaryValidationResult(
      boolean valid,
      String reasonCode,
      List<RuntimeApi.CorrelationConsumerBoundary> boundaries
  ) {
    private static BoundaryValidationResult valid(List<RuntimeApi.CorrelationConsumerBoundary> boundaries) {
      return new BoundaryValidationResult(true, "ok", List.copyOf(boundaries));
    }

    private static BoundaryValidationResult invalid(String reasonCode) {
      return new BoundaryValidationResult(false, reasonCode, List.of());
    }
  }
}

