import { fetchJson } from "@tools-core/http";
import { joinUrl, normalizeStatusJson, probeUnreachableMessage, readLineValidation } from "@tools-feature-probe";

export class RuntimeProbeUnreachableError extends Error {
  readonly reasonCode = "runtime_unreachable";

  constructor(message: string) {
    super(message);
    this.name = "RuntimeProbeUnreachableError";
  }
}

export type RuntimeLineSelectionResult = {
  firstExecutableLine: number | null;
  lineSelectionStatus: "validated" | "unresolved";
  lineSelectionSource?: "runtime_probe_validation";
};

export async function selectRuntimeValidatedLine(args: {
  probeBaseUrl: string;
  probeStatusPath: string;
  probeKey: string;
  startLine: number;
  endLine: number;
  maxScanLines: number;
  timeoutMs?: number;
}): Promise<RuntimeLineSelectionResult> {
  const scanStart = Math.max(1, args.startLine);
  const scanEnd = Math.max(
    scanStart,
    Math.min(args.endLine, scanStart + Math.max(1, args.maxScanLines) - 1),
  );

  for (let line = scanStart; line <= scanEnd; line++) {
    const lineKey = `${args.probeKey}:${line}`;
    const url = new URL(joinUrl(args.probeBaseUrl, args.probeStatusPath));
    url.searchParams.set("key", lineKey);
    let response: { status: number; json: unknown; text: string };
    try {
      response = await fetchJson(url.toString(), {
        method: "GET",
        timeoutMs: args.timeoutMs ?? 3_000,
      });
    } catch (err) {
      throw new RuntimeProbeUnreachableError(probeUnreachableMessage(url.toString(), err));
    }
    if (response.status < 200 || response.status >= 300) {
      throw new RuntimeProbeUnreachableError(
        `Probe status request failed: ${url} returned HTTP ${response.status}`,
      );
    }
    const normalized = normalizeStatusJson(
      (response.json as Record<string, unknown> | null) ?? null,
    );
    const lineValidation = readLineValidation(normalized);
    const hasValidationSignal =
      typeof normalized?.lineValidation === "string" ||
      typeof normalized?.lineResolvable === "boolean";
    const isResolvable =
      hasValidationSignal &&
      !lineValidation.invalidLineTarget &&
      (normalized?.lineValidation === "resolvable" || normalized?.lineResolvable === true);
    if (isResolvable) {
      return {
        firstExecutableLine: line,
        lineSelectionStatus: "validated",
        lineSelectionSource: "runtime_probe_validation",
      };
    }
  }

  return {
    firstExecutableLine: null,
    lineSelectionStatus: "unresolved",
  };
}
