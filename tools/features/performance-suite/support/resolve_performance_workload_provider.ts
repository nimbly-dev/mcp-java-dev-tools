import type { PerformanceWorkloadProvider } from "../../performance-workload-jmeter";

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function asTrimmedString(value: unknown): string | undefined {
  return typeof value === "string" && value.trim().length > 0 ? value.trim() : undefined;
}

export function resolvePerformanceWorkloadProvider(
  input: Record<string, unknown>,
): { ok: true; provider: PerformanceWorkloadProvider } | { ok: false; reasonCode: string; requiredUserAction: string[] } {
  const rawProvider = isRecord(input.workloadProvider) ? input.workloadProvider : null;
  if (!rawProvider) return { ok: true, provider: { type: "builtin" } };

  const type = asTrimmedString(rawProvider.type);
  if (type === "builtin") return { ok: true, provider: { type: "builtin" } };
  if (type !== "jmeter") {
    return {
      ok: false,
      reasonCode: "performance_workload_provider_invalid",
      requiredUserAction: ["Set workloadProvider.type to builtin or jmeter."],
    };
  }

  const mode = asTrimmedString(rawProvider.mode);
  if (mode !== "generated_http") {
    return {
      ok: false,
      reasonCode: "performance_workload_provider_invalid",
      requiredUserAction: ["Set workloadProvider.mode=generated_http when workloadProvider.type=jmeter."],
    };
  }

  const options = isRecord(rawProvider.options) ? rawProvider.options : null;
  const installationPath = asTrimmedString(options?.installationPath);
  return {
    ok: true,
    provider: {
      type: "jmeter",
      mode: "generated_http",
      ...(options
        ? {
            options: {
              ...(installationPath ? { installationPath } : {}),
              ...(typeof options.emitJmx === "boolean" ? { emitJmx: options.emitJmx } : {}),
              ...(typeof options.emitJtl === "boolean" ? { emitJtl: options.emitJtl } : {}),
              ...(typeof options.emitLog === "boolean" ? { emitLog: options.emitLog } : {}),
            },
          }
        : {}),
    },
  };
}
