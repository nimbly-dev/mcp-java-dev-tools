package com.nimbly.mcpjavadevtools.agent.control.http;

import com.nimbly.mcpjavadevtools.agent.control.http.model.ProbeHttpPayloads;
import com.nimbly.mcpjavadevtools.agent.profiler.api.ProfilerApi;

public final class ProbeProfilerHttpResponses {
  private ProbeProfilerHttpResponses() {}

  public static boolean shouldFailClosedOnStart(ProfilerApi.State state) {
    return state == null || !state.supported() || "failed".equals(state.status());
  }

  public static int startStatusCode(ProfilerApi.State state) {
    if (state != null && !state.supported()) {
      return 409;
    }
    return 500;
  }

  public static ProbeHttpPayloads.ErrorEnvelope startErrorEnvelope(ProfilerApi.State state) {
    return new ProbeHttpPayloads.ErrorEnvelope(startErrorCode(state), null);
  }

  private static String startErrorCode(ProfilerApi.State state) {
    if (state == null) {
      return "profiler_start_failed";
    }
    String detail = sanitizeDetail(state.detail());
    if (!detail.isBlank()) {
      return detail;
    }
    if (!state.supported()) {
      return "profiler_unsupported_platform";
    }
    return "profiler_start_failed";
  }

  private static String sanitizeDetail(String detail) {
    if (detail == null) {
      return "";
    }
    return detail.trim();
  }
}
