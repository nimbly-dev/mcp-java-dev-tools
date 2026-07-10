import { synthesizeHttpUrl } from "../../../spec/regression-execution-plan-spec/src/suite_http_request.util";

export function buildHttpPayload(args: {
  resolvedTransport: Record<string, unknown>;
  context: Record<string, unknown>;
}): Record<string, unknown> {
  const transportHttp =
    typeof args.resolvedTransport.http === "object" && args.resolvedTransport.http !== null
      ? { ...(args.resolvedTransport.http as Record<string, unknown>) }
      : {};
  if (!transportHttp.method) transportHttp.method = "GET";
  const synthesizedUrl = synthesizeHttpUrl({
    url: transportHttp.url,
    apiBaseUrl: args.context.apiBaseUrl,
    pathTemplate: transportHttp.pathTemplate,
    path: transportHttp.path,
  });
  if (synthesizedUrl) transportHttp.url = synthesizedUrl;
  if (typeof transportHttp.body === "object" && transportHttp.body !== null && !Array.isArray(transportHttp.body)) {
    transportHttp.body = JSON.stringify(transportHttp.body);
    const headers =
      typeof transportHttp.headers === "object" && transportHttp.headers !== null && !Array.isArray(transportHttp.headers)
        ? (transportHttp.headers as Record<string, unknown>)
        : {};
    const hasContentType = Object.keys(headers).some((key) => key.toLowerCase() === "content-type");
    if (!hasContentType) {
      headers["Content-Type"] = "application/json";
    }
    transportHttp.headers = headers;
  }
  if (typeof transportHttp.timeoutMs !== "number" || !Number.isFinite(transportHttp.timeoutMs) || transportHttp.timeoutMs <= 0) {
    const defaultTimeoutMs = args.context["runtime.requestTimeoutMs"];
    if (typeof defaultTimeoutMs === "number" && Number.isFinite(defaultTimeoutMs) && defaultTimeoutMs > 0) {
      transportHttp.timeoutMs = Math.floor(defaultTimeoutMs);
    }
  }
  return transportHttp;
}
