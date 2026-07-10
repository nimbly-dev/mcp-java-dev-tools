function asTrimmedString(value: unknown): string | undefined {
  return typeof value === "string" && value.trim().length > 0 ? value.trim() : undefined;
}

function isAbsoluteHttpUrl(value: string): boolean {
  return /^https?:\/\//i.test(value);
}

export function joinBaseUrlAndPath(baseUrl: string, requestPath: string): string {
  return `${baseUrl.replace(/\/$/, "")}${requestPath.startsWith("/") ? "" : "/"}${requestPath}`;
}

export function normalizeHttpContextAliases(
  context: Record<string, unknown>,
): Record<string, unknown> {
  const apiBaseUrl = asTrimmedString(context.apiBaseUrl);
  if (apiBaseUrl) {
    return context;
  }
  const legacyBaseUrl = asTrimmedString(context.baseUrl);
  if (!legacyBaseUrl) {
    return context;
  }
  return {
    ...context,
    apiBaseUrl: legacyBaseUrl,
  };
}

export function synthesizeHttpUrl(args: {
  url?: unknown;
  apiBaseUrl?: unknown;
  pathTemplate?: unknown;
  path?: unknown;
}): string | undefined {
  const explicitUrl = asTrimmedString(args.url);
  if (explicitUrl) {
    return explicitUrl;
  }

  const pathTemplate = asTrimmedString(args.pathTemplate);
  const path = asTrimmedString(args.path);
  const requestPath = pathTemplate ?? path;
  if (!requestPath || isAbsoluteHttpUrl(requestPath)) {
    return undefined;
  }

  const apiBaseUrl = asTrimmedString(args.apiBaseUrl);
  if (!apiBaseUrl) {
    return undefined;
  }

  return joinBaseUrlAndPath(apiBaseUrl, requestPath);
}

export function resolveHttpUrlMissingReasonMeta(args: {
  pathTemplate?: unknown;
  path?: unknown;
}): Record<string, unknown> {
  const pathTemplate = asTrimmedString(args.pathTemplate);
  const path = asTrimmedString(args.path);
  const requestPath = pathTemplate ?? path;

  const cause =
    !requestPath
      ? "url_missing"
      : isAbsoluteHttpUrl(requestPath)
        ? (pathTemplate ? "absolute_path_template_not_promoted" : "absolute_path_not_promoted")
        : (pathTemplate ? "api_base_url_missing_for_path_template" : "api_base_url_missing_for_path");

  return {
    missingFields: ["url"],
    cause,
    ...(pathTemplate ? { pathTemplate } : {}),
    ...(path ? { path } : {}),
  };
}
