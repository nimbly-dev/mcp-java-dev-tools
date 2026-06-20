package com.nimbly.mcpjavadevtools.agent.control.http;

import com.nimbly.mcpjavadevtools.agent.capture.CapturePreviewView;
import com.nimbly.mcpjavadevtools.agent.capture.CaptureRecordView;
import com.nimbly.mcpjavadevtools.agent.capture.CaptureValueView;
import com.nimbly.mcpjavadevtools.agent.capture.ProbeCaptureStore;
import com.nimbly.mcpjavadevtools.agent.control.http.model.ProbeHttpPayloads;
import com.nimbly.mcpjavadevtools.agent.profiler.model.ProfilerStateSnapshot;
import com.nimbly.mcpjavadevtools.agent.profiler.model.ProfilerStopResult;
import com.nimbly.mcpjavadevtools.agent.runtime.ProbeRuntime;
import com.nimbly.mcpjavadevtools.agent.runtime.RuntimePortSignal;
import com.nimbly.mcpjavadevtools.agent.runtime.RuntimeStringSignal;
import com.nimbly.mcpjavadevtools.agent.runtime.model.ActuationState;
import com.nimbly.mcpjavadevtools.agent.runtime.model.KeyStatus;
import com.nimbly.mcpjavadevtools.agent.runtime.model.RuntimeState;

import java.util.ArrayList;
import java.util.List;

final class ProbeHttpMapper {
  private ProbeHttpMapper() {}

  static ProbeHttpPayloads.StatusEnvelope buildStatusEnvelope(String contractVersion, String key) {
    return new ProbeHttpPayloads.StatusEnvelope(
        contractVersion,
        buildProbePayload(key),
        buildCapturePreviewPayload(ProbeCaptureStore.getCapturePreviewForKey(key)),
        buildRuntimePayload()
    );
  }

  static ProbeHttpPayloads.StatusBatchRow buildStatusBatchRow(String key) {
    return new ProbeHttpPayloads.StatusBatchRow(
        true,
        buildProbePayload(key),
        buildCapturePreviewPayload(ProbeCaptureStore.getCapturePreviewForKey(key)),
        buildRuntimePayload()
    );
  }

  static ProbeHttpPayloads.ResetEnvelope buildResetEnvelope(String contractVersion, String key) {
    KeyStatus status = ProbeRuntime.keyStatus(key);
    return new ProbeHttpPayloads.ResetEnvelope(
        contractVersion,
        true,
        key,
        status.lineResolvable(),
        status.lineValidation()
    );
  }

  static ProbeHttpPayloads.ResetRow buildResetRow(String key) {
    KeyStatus status = ProbeRuntime.keyStatus(key);
    return new ProbeHttpPayloads.ResetRow(
        true,
        key,
        status.lineResolvable(),
        status.lineValidation()
    );
  }

  static ProbeHttpPayloads.CaptureEnvelope buildCaptureEnvelope(String contractVersion, CaptureRecordView capture) {
    return new ProbeHttpPayloads.CaptureEnvelope(
        contractVersion,
        buildCaptureRecordPayload(capture)
    );
  }

  static ProbeHttpPayloads.ProfilerEnvelope buildProfilerStateEnvelope(
      String contractVersion,
      String action,
      ProfilerStateSnapshot state
  ) {
    return new ProbeHttpPayloads.ProfilerEnvelope(
        contractVersion,
        true,
        action,
        buildProfilerStatePayload(state)
    );
  }

  static ProbeHttpPayloads.ProfilerEnvelope buildProfilerStopEnvelope(
      String contractVersion,
      String action,
      ProfilerStopResult result
  ) {
    return new ProbeHttpPayloads.ProfilerEnvelope(
        contractVersion,
        true,
        action,
        new ProbeHttpPayloads.ProfilerPayload(
            result.status(),
            result.provider(),
            result.supported(),
            result.sessionId(),
            null,
            result.stoppedAtEpochMs(),
            null,
            null,
            result.outputPath(),
            result.outputFormat(),
            result.detail()
        )
    );
  }

  private static ProbeHttpPayloads.ProbePayload buildProbePayload(String key) {
    KeyStatus status = ProbeRuntime.keyStatus(key);
    return new ProbeHttpPayloads.ProbePayload(
        status.key(),
        status.hitCount(),
        status.lastHitEpoch(),
        status.lineResolvable(),
        status.lineValidation()
    );
  }

  private static ProbeHttpPayloads.CapturePreviewPayload buildCapturePreviewPayload(CapturePreviewView preview) {
    if (preview == null || !preview.available) {
      String redactionMode = preview == null ? ProbeCaptureStore.getCaptureRedactionMode() : preview.redactionMode;
      return new ProbeHttpPayloads.CapturePreviewPayload(
          false,
          redactionMode,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null
      );
    }

    return new ProbeHttpPayloads.CapturePreviewPayload(
        true,
        preview.redactionMode,
        preview.captureId,
        preview.methodKey,
        preview.capturedAtEpoch,
        preview.executionStartedAtEpoch,
        preview.executionEndedAtEpoch,
        preview.executionDurationMs,
        preview.threadAllocatedBytesDelta,
        buildCapturePreviewArgs(preview.argsPreview),
        buildCapturePreviewValue(preview.returnPreview),
        buildCapturePreviewValue(preview.thrownPreview),
        preview.truncatedAny,
        preview.executionPaths == null ? List.of() : preview.executionPaths
    );
  }

  private static List<ProbeHttpPayloads.CapturePreviewArgPayload> buildCapturePreviewArgs(List<CaptureValueView> values) {
    if (values == null || values.isEmpty()) return List.of();
    List<ProbeHttpPayloads.CapturePreviewArgPayload> out = new ArrayList<>();
    for (int i = 0; i < values.size(); i++) {
      CaptureValueView value = values.get(i);
      out.add(new ProbeHttpPayloads.CapturePreviewArgPayload(
          i,
          value.truncated,
          value.originalLength,
          value.redacted
      ));
    }
    return out;
  }

  private static ProbeHttpPayloads.CapturePreviewValuePayload buildCapturePreviewValue(CaptureValueView value) {
    if (value == null) return null;
    return new ProbeHttpPayloads.CapturePreviewValuePayload(
        value.truncated,
        value.originalLength,
        value.redacted
    );
  }

  private static ProbeHttpPayloads.RuntimePayload buildRuntimePayload() {
    RuntimeState runtime = ProbeRuntime.runtimeState();
    ActuationState actuation = runtime.actuation();
    return new ProbeHttpPayloads.RuntimePayload(
        actuation.mode(),
        actuation.sessionId(),
        actuation.actuatorId(),
        actuation.targetKey(),
        actuation.returnBoolean(),
        actuation.expiresAtEpoch(),
        actuation.scopeState(),
        actuation.activeSessionCount(),
        runtime.serverEpoch(),
        buildRuntimeStringSignal(runtime.applicationType()),
        buildRuntimePortSignal(runtime.appPort())
    );
  }

  private static ProbeHttpPayloads.RuntimeStringSignalPayload buildRuntimeStringSignal(RuntimeStringSignal signal) {
    if (signal == null) {
      return new ProbeHttpPayloads.RuntimeStringSignalPayload("unknown", "runtime_introspection", 0.0);
    }
    return new ProbeHttpPayloads.RuntimeStringSignalPayload(signal.value, signal.source, signal.confidence);
  }

  private static ProbeHttpPayloads.RuntimePortSignalPayload buildRuntimePortSignal(RuntimePortSignal signal) {
    if (signal == null) {
      return new ProbeHttpPayloads.RuntimePortSignalPayload(null, "runtime_introspection", 0.0);
    }
    return new ProbeHttpPayloads.RuntimePortSignalPayload(signal.value, signal.source, signal.confidence);
  }

  private static ProbeHttpPayloads.ProfilerPayload buildProfilerStatePayload(ProfilerStateSnapshot state) {
    return new ProbeHttpPayloads.ProfilerPayload(
        state.status(),
        state.provider(),
        state.supported(),
        state.sessionId(),
        state.startedAtEpochMs(),
        null,
        state.event(),
        state.intervalNanos(),
        state.outputPath(),
        state.outputPath() == null ? null : "jfr",
        state.detail()
    );
  }

  private static ProbeHttpPayloads.CaptureRecordPayload buildCaptureRecordPayload(CaptureRecordView capture) {
    return new ProbeHttpPayloads.CaptureRecordPayload(
        capture.captureId,
        capture.methodKey,
        capture.capturedAtEpoch,
        capture.executionStartedAtEpoch,
        capture.executionEndedAtEpoch,
        capture.executionDurationMs,
        capture.threadAllocatedBytesDelta,
        capture.redactionMode,
        buildCaptureArgs(capture.args),
        buildCaptureValue(capture.returnValue),
        buildCaptureValue(capture.thrownValue),
        capture.truncatedAny,
        capture.executionPaths == null ? List.of() : capture.executionPaths
    );
  }

  private static List<ProbeHttpPayloads.CaptureArgPayload> buildCaptureArgs(List<CaptureValueView> values) {
    if (values == null || values.isEmpty()) return List.of();
    List<ProbeHttpPayloads.CaptureArgPayload> out = new ArrayList<>();
    for (int i = 0; i < values.size(); i++) {
      CaptureValueView value = values.get(i);
      out.add(new ProbeHttpPayloads.CaptureArgPayload(
          i,
          value.value,
          value.truncated,
          value.originalLength,
          value.redacted
      ));
    }
    return out;
  }

  private static ProbeHttpPayloads.CaptureValuePayload buildCaptureValue(CaptureValueView value) {
    if (value == null) return null;
    return new ProbeHttpPayloads.CaptureValuePayload(
        value.value,
        value.truncated,
        value.originalLength,
        value.redacted
    );
  }
}
