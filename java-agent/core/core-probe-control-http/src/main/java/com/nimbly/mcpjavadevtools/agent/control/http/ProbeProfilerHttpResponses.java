package com.nimbly.mcpjavadevtools.agent.control.http;

import com.nimbly.mcpjavadevtools.agent.control.http.model.ProbeHttpPayloads;
import com.nimbly.mcpjavadevtools.agent.profiler.model.ProfilerStateSnapshot;

final class ProbeProfilerHttpResponses {
  private ProbeProfilerHttpResponses() {}

  static boolean shouldFailClosedOnStart(ProfilerStateSnapshot state) {
    return state == null || !state.supported() || "failed".equals(state.status());
  }

  static int startStatusCode(ProfilerStateSnapshot state) {
    if (state != null && !state.supported()) {
      return 409;
    }
    return 500;
  }

  static ProbeHttpPayloads.ErrorEnvelope startErrorEnvelope(ProfilerStateSnapshot state) {
    return new ProbeHttpPayloads.ErrorEnvelope(startErrorCode(state), null);
  }

  private static String startErrorCode(ProfilerStateSnapshot state) {
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
