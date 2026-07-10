import {
  compactCapturePreview,
  compactRuntimeHints,
  compactStatusPayload,
} from "./compact_payload.util";

export function readLineValidation(json: Record<string, unknown> | null): {
  lineValidation?: string;
  lineResolvable?: boolean;
  invalidLineTarget: boolean;
} {
  const out: { lineValidation?: string; lineResolvable?: boolean; invalidLineTarget: boolean } = {
    invalidLineTarget: false,
  };
  if (typeof json?.lineValidation === "string") out.lineValidation = json.lineValidation;
  if (typeof json?.lineResolvable === "boolean") out.lineResolvable = json.lineResolvable;
  out.invalidLineTarget = out.lineValidation === "invalid_line_target" || out.lineResolvable === false;
  return out;
}

export function normalizeStatusJson(
  raw: Record<string, unknown> | null,
): Record<string, unknown> | null {
  if (!raw) return null;
  const probe =
    typeof raw.probe === "object" && raw.probe !== null
      ? (raw.probe as Record<string, unknown>)
      : raw;
  const runtime =
    typeof raw.runtime === "object" && raw.runtime !== null
      ? (raw.runtime as Record<string, unknown>)
      : null;
  const out: Record<string, unknown> = { ...probe };
  if (typeof raw.contractVersion === "string") out.contractVersion = raw.contractVersion;
  if (typeof raw.capturePreview === "object" && raw.capturePreview !== null) {
    out.capturePreview = compactCapturePreview(raw.capturePreview as Record<string, unknown>);
  }
  if (runtime) {
    const sanitizedRuntime = compactRuntimeHints(runtime);
    out.runtime = sanitizedRuntime;
    if (typeof sanitizedRuntime.mode === "string") out.mode = sanitizedRuntime.mode;
  }
  return compactStatusPayload(out);
}

export function normalizeStatusBatchRow(raw: Record<string, unknown>): Record<string, unknown> {
  if (typeof raw.probe !== "object" || raw.probe === null) {
    return compactStatusPayload(raw) ?? raw;
  }
  const out: Record<string, unknown> = {
    ...(raw.probe as Record<string, unknown>),
  };
  if (typeof raw.ok === "boolean") out.ok = raw.ok;
  if (typeof raw.capturePreview === "object" && raw.capturePreview !== null) {
    out.capturePreview = compactCapturePreview(raw.capturePreview as Record<string, unknown>);
  }
  if (typeof raw.runtime === "object" && raw.runtime !== null) {
    const sanitizedRuntime = compactRuntimeHints(raw.runtime as Record<string, unknown>);
    out.runtime = sanitizedRuntime;
    const mode = sanitizedRuntime.mode;
    if (typeof mode === "string") out.mode = mode;
  }
  return compactStatusPayload(out) ?? {};
}

export function normalizeStatusBatchPayload(raw: unknown): unknown {
  if (!raw || typeof raw !== "object") return raw;
  const json = raw as Record<string, unknown>;
  const results = Array.isArray(json.results) ? json.results : null;
  if (!results) return raw;
  return {
    ...json,
    results: results
      .map((row) =>
        row && typeof row === "object" ? normalizeStatusBatchRow(row as Record<string, unknown>) : row,
      )
      .filter((row) => !!row),
  };
}

export function invalidLineTargetProbeHitMessage(hitCount: number): string {
  return (
    "target line cannot be resolved to executable bytecode for this Class#method; " +
    `hitCount=${hitCount} is not evidence of method non-execution`
  );
}
