package com.nimbly.mcpjavadevtools.agent.control.http;

import com.nimbly.mcpjavadevtools.agent.debug.api.DebugApi;
import com.nimbly.mcpjavadevtools.agent.control.http.model.ProbeHttpPayloads;
import com.nimbly.mcpjavadevtools.agent.profiler.api.ProfilerApi;
import com.nimbly.mcpjavadevtools.agent.runtime.api.RuntimeApi;

import java.util.ArrayList;
import java.util.List;

public final class ProbeHttpMapper {
  private ProbeHttpMapper() {}

  public static ProbeHttpPayloads.StatusEnvelope buildStatusEnvelope(String contractVersion, String key) {
    return new ProbeHttpPayloads.StatusEnvelope(
        contractVersion,
        buildProbePayload(key),
        buildCapturePreviewPayload(DebugApi.capturePreviewForKey(key)),
        buildRuntimePayload()
    );
  }

  public static ProbeHttpPayloads.StatusBatchRow buildStatusBatchRow(String key) {
    return new ProbeHttpPayloads.StatusBatchRow(
        true,
        buildProbePayload(key),
        buildCapturePreviewPayload(DebugApi.capturePreviewForKey(key)),
        buildRuntimePayload()
    );
  }

  public static ProbeHttpPayloads.ResetEnvelope buildResetEnvelope(String contractVersion, String key) {
    RuntimeApi.KeyStatus status = RuntimeApi.keyStatus(key);
    return new ProbeHttpPayloads.ResetEnvelope(
        contractVersion,
        true,
        key,
        status.lineResolvable(),
        status.lineValidation()
    );
  }

  public static ProbeHttpPayloads.ResetRow buildResetRow(String key) {
    RuntimeApi.KeyStatus status = RuntimeApi.keyStatus(key);
    return new ProbeHttpPayloads.ResetRow(
        true,
        key,
        status.lineResolvable(),
        status.lineValidation()
    );
  }

  public static ProbeHttpPayloads.CaptureEnvelope buildCaptureEnvelope(String contractVersion, DebugApi.CaptureRecord capture) {
    return new ProbeHttpPayloads.CaptureEnvelope(
        contractVersion,
        buildCaptureRecordPayload(capture)
    );
  }

  public static ProbeHttpPayloads.FailureAnalysisEnvelope buildFailureAnalysisEnvelope(
      String contractVersion,
      DebugApi.FailureTraceAnalysis analysis
  ) {
    return new ProbeHttpPayloads.FailureAnalysisEnvelope(
        contractVersion,
        buildFailureFingerprintPayload(analysis.fingerprint()),
        analysis.investigationCandidates().stream().map(ProbeHttpMapper::buildFailureFramePayload).toList(),
        buildFailureFramePayload(analysis.dependencyBoundary()),
        analysis.exceptionSections().stream().map(ProbeHttpMapper::buildFailureExceptionSectionPayload).toList(),
        analysis.reasons());
  }

  public static ProbeHttpPayloads.FailureVerificationEnvelope buildFailureVerificationEnvelope(
      String contractVersion,
      DebugApi.FailureComparison comparison
  ) {
    return new ProbeHttpPayloads.FailureVerificationEnvelope(
        contractVersion,
        comparison.outcome(),
        buildFailureFingerprintPayload(comparison.observedFingerprint()),
        comparison.reasons());
  }

  public static ProbeHttpPayloads.ProfilerEnvelope buildProfilerStateEnvelope(
      String contractVersion,
      String action,
      ProfilerApi.State state
  ) {
    return new ProbeHttpPayloads.ProfilerEnvelope(
        contractVersion,
        true,
        action,
        buildProfilerStatePayload(state)
    );
  }

  public static ProbeHttpPayloads.ProfilerEnvelope buildProfilerStopEnvelope(
      String contractVersion,
      String action,
      ProfilerApi.StopResult result
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
    RuntimeApi.KeyStatus status = RuntimeApi.keyStatus(key);
    return new ProbeHttpPayloads.ProbePayload(
        status.key(),
        status.hitCount(),
        status.lastHitEpoch(),
        status.lineResolvable(),
        status.lineValidation()
    );
  }

  private static ProbeHttpPayloads.CapturePreviewPayload buildCapturePreviewPayload(DebugApi.CapturePreview preview) {
    if (preview == null || !preview.available) {
      String redactionMode = preview == null ? DebugApi.captureRedactionMode() : preview.redactionMode;
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

  private static List<ProbeHttpPayloads.CapturePreviewArgPayload> buildCapturePreviewArgs(List<DebugApi.CaptureValue> values) {
    if (values == null || values.isEmpty()) return List.of();
    List<ProbeHttpPayloads.CapturePreviewArgPayload> out = new ArrayList<>();
    for (int i = 0; i < values.size(); i++) {
      DebugApi.CaptureValue value = values.get(i);
      out.add(new ProbeHttpPayloads.CapturePreviewArgPayload(
          i,
          value.truncated(),
          value.originalLength(),
          value.redacted()
      ));
    }
    return out;
  }

  private static ProbeHttpPayloads.CapturePreviewValuePayload buildCapturePreviewValue(DebugApi.CaptureValue value) {
    if (value == null) return null;
    return new ProbeHttpPayloads.CapturePreviewValuePayload(
        value.truncated(),
        value.originalLength(),
        value.redacted()
    );
  }

  private static ProbeHttpPayloads.RuntimePayload buildRuntimePayload() {
    RuntimeApi.RuntimeState runtime = RuntimeApi.runtimeState();
    RuntimeApi.ActuationState actuation = runtime.actuation();
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
        buildRuntimePortSignal(runtime.appPort()),
        RuntimeApi.runtimeInstanceId()
    );
  }

  private static ProbeHttpPayloads.RuntimeStringSignalPayload buildRuntimeStringSignal(RuntimeApi.RuntimeStringSignal signal) {
    if (signal == null) {
      return new ProbeHttpPayloads.RuntimeStringSignalPayload("unknown", "runtime_introspection", 0.0);
    }
    return new ProbeHttpPayloads.RuntimeStringSignalPayload(signal.value(), signal.source(), signal.confidence());
  }

  private static ProbeHttpPayloads.RuntimePortSignalPayload buildRuntimePortSignal(RuntimeApi.RuntimePortSignal signal) {
    if (signal == null) {
      return new ProbeHttpPayloads.RuntimePortSignalPayload(null, "runtime_introspection", 0.0);
    }
    return new ProbeHttpPayloads.RuntimePortSignalPayload(signal.value(), signal.source(), signal.confidence());
  }

  private static ProbeHttpPayloads.ProfilerPayload buildProfilerStatePayload(ProfilerApi.State state) {
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

  private static ProbeHttpPayloads.CaptureRecordPayload buildCaptureRecordPayload(DebugApi.CaptureRecord capture) {
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

  private static List<ProbeHttpPayloads.CaptureArgPayload> buildCaptureArgs(List<DebugApi.CaptureValue> values) {
    if (values == null || values.isEmpty()) return List.of();
    List<ProbeHttpPayloads.CaptureArgPayload> out = new ArrayList<>();
    for (int i = 0; i < values.size(); i++) {
      DebugApi.CaptureValue value = values.get(i);
      out.add(new ProbeHttpPayloads.CaptureArgPayload(
          i,
          value.value(),
          value.truncated(),
          value.originalLength(),
          value.redacted()
      ));
    }
    return out;
  }

  private static ProbeHttpPayloads.CaptureValuePayload buildCaptureValue(DebugApi.CaptureValue value) {
    if (value == null) return null;
    return new ProbeHttpPayloads.CaptureValuePayload(
        value.value(),
        value.truncated(),
        value.originalLength(),
        value.redacted()
    );
  }

  private static ProbeHttpPayloads.FailureFingerprintPayload buildFailureFingerprintPayload(
      DebugApi.FailureFingerprint fingerprint
  ) {
    if (fingerprint == null) return null;
    return new ProbeHttpPayloads.FailureFingerprintPayload(
        fingerprint.exceptionType(),
        fingerprint.rootCauseType(),
        buildFailureFramePayload(fingerprint.nearestApplicationFrame()),
        fingerprint.normalizedMessage(),
        fingerprint.complete(),
        fingerprint.incompletenessReasons());
  }

  private static ProbeHttpPayloads.FailureFramePayload buildFailureFramePayload(DebugApi.FailureFrame frame) {
    if (frame == null) return null;
    return new ProbeHttpPayloads.FailureFramePayload(
        frame.className(),
        frame.methodName(),
        frame.sourceFile(),
        frame.lineNumber(),
        frame.ownership(),
        frame.codeSource(),
        frame.strictLineKey(),
        frame.methodDescriptor(),
        frame.codeSourceCandidates(),
        frame.resolutionReason());
  }

  private static ProbeHttpPayloads.FailureExceptionSectionPayload buildFailureExceptionSectionPayload(
      DebugApi.FailureExceptionSection section
  ) {
    return new ProbeHttpPayloads.FailureExceptionSectionPayload(
        section.exceptionType(),
        section.suppressed(),
        section.elidedFrames(),
        section.frames().stream().map(ProbeHttpMapper::buildFailureFramePayload).toList());
  }
}
