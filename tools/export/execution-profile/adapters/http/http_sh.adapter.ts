import type { ShTransportExportAdapter } from "@tools-export-execution-profile/adapters/registry/transport_export_adapter.interface";
import { asRecord } from "@tools-export-execution-profile/adapters/http/http_shared.util";
import { toShellEnvKey } from "@tools-export-execution-profile/common";
import { resolveStepTransport } from "@tools-regression-execution-plan-spec/regression_execution_plan_spec.util";

function normalizePlaceholders(value: string): string {
  return value.replace(/\$\{([^}]+)\}/g, (_full, key: string) => `\${${toShellEnvKey(key)}}`);
}

function shellDoubleQuoted(value: string): string {
  return `"${value.replace(/\\/g, "\\\\").replace(/"/g, '\\"')}"`;
}

function startsWithAbsoluteUrl(value: string): boolean {
  return /^https?:\/\//i.test(value) || /^\$\{[A-Za-z_][A-Za-z0-9_]*\}/.test(value);
}

function buildCurlCommand(http: Record<string, unknown>, context: Record<string, unknown>): string | null {
  const method = typeof http.method === "string" && http.method.trim().length > 0 ? http.method.trim().toUpperCase() : "GET";
  const directUrl = typeof http.url === "string" && http.url.trim().length > 0 ? normalizePlaceholders(http.url.trim()) : undefined;
  const pathTemplate =
    typeof http.pathTemplate === "string" && http.pathTemplate.trim().length > 0 ? normalizePlaceholders(http.pathTemplate.trim()) : undefined;
  const apiBaseUrl =
    typeof context.apiBaseUrl === "string" && context.apiBaseUrl.trim().length > 0 ? normalizePlaceholders(context.apiBaseUrl.trim()) : undefined;

  let url = directUrl;
  if (!url && pathTemplate && apiBaseUrl) {
    url = startsWithAbsoluteUrl(pathTemplate)
      ? pathTemplate
      : `${apiBaseUrl.replace(/\/$/, "")}${pathTemplate.startsWith("/") ? "" : "/"}${pathTemplate}`;
  }
  if (!url && pathTemplate) {
    url = startsWithAbsoluteUrl(pathTemplate)
      ? pathTemplate
      : `\${API_BASE_URL}${pathTemplate.startsWith("/") ? "" : "/"}${pathTemplate}`;
  }
  if (!url) return null;

  const parts: string[] = [`curl -fsS -X ${shellDoubleQuoted(method)}`];
  const headers = asRecord(http.headers);
  if (headers) {
    for (const [key, rawValue] of Object.entries(headers)) {
      parts.push(`-H ${shellDoubleQuoted(normalizePlaceholders(`${key}: ${String(rawValue)}`))}`);
    }
  }
  if (typeof http.body === "string") {
    parts.push(`--data-raw ${shellDoubleQuoted(normalizePlaceholders(http.body))}`);
  } else if (http.body !== null && typeof http.body === "object" && !Array.isArray(http.body)) {
    const bodyText = normalizePlaceholders(JSON.stringify(http.body));
    const hasContentType = headers
      ? Object.keys(headers).some((key) => key.toLowerCase() === "content-type")
      : false;
    if (!hasContentType) {
      parts.push(`-H ${shellDoubleQuoted("Content-Type: application/json")}`);
    }
    parts.push(`--data-raw ${shellDoubleQuoted(bodyText)}`);
  }
  parts.push(shellDoubleQuoted(normalizePlaceholders(url)));
  return parts.join(" ");
}

export const httpShTransportAdapter: ShTransportExportAdapter = {
  canHandle(args) {
    return args.step.protocol === "http" || (typeof args.step.transport.http === "object" && args.step.transport.http !== null);
  },
  render(args) {
    let resolvedTransport: Record<string, unknown>;
    try {
      resolvedTransport = resolveStepTransport(args.step, args.contextResolved);
    } catch {
      resolvedTransport = args.step.transport as Record<string, unknown>;
    }
    const http = asRecord(resolvedTransport.http);
    if (!http) {
      return { handled: false, lines: [] };
    }
    const command = buildCurlCommand(http, args.contextResolved);
    if (!command) {
      return { handled: false, lines: [] };
    }
    return {
      handled: true,
      lines: [command],
    };
  },
};
