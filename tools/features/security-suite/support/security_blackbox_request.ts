import net from "node:net";

import type {
  SecurityAuthenticationProfile,
  SecurityAttackRequest,
  SecurityEntrypoint,
  SecurityPlanContract,
} from "@tools-security-execution-plan-spec";

type StringRecord = Record<string, string>;

async function isTcpReachable(args: {
  host: string;
  port: number;
  timeoutMs: number;
}): Promise<boolean> {
  return await new Promise<boolean>((resolve) => {
    const socket = new net.Socket();
    let settled = false;
    const finish = (ok: boolean): void => {
      if (settled) return;
      settled = true;
      socket.destroy();
      resolve(ok);
    };
    socket.setTimeout(args.timeoutMs, () => finish(false));
    socket.once("error", () => finish(false));
    socket.connect(args.port, args.host, () => finish(true));
  });
}

export async function waitForSecurityTargetReachability(args: {
  baseUrl: string;
  timeoutMs?: number;
}): Promise<boolean> {
  let target: URL;
  try {
    target = new URL(args.baseUrl);
  } catch {
    return false;
  }
  const port = target.port
    ? Number(target.port)
    : target.protocol === "https:"
      ? 443
      : target.protocol === "http:"
        ? 80
        : NaN;
  if (!target.hostname || !Number.isInteger(port) || port <= 0 || port > 65535) return false;
  const timeoutMs = Math.max(1_000, args.timeoutMs ?? 10_000);
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (
      await isTcpReachable({
        host: target.hostname,
        port,
        timeoutMs: Math.min(500, Math.max(100, timeoutMs)),
      })
    ) {
      return true;
    }
    await new Promise((resolve) => setTimeout(resolve, Math.min(250, Math.max(100, timeoutMs / 8))));
  }
  return false;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function asStringRecord(value: unknown): StringRecord {
  if (!isRecord(value)) return {};
  const output: StringRecord = {};
  for (const [key, child] of Object.entries(value)) {
    if (typeof child === "string") output[key] = child;
    else if (typeof child === "number" || typeof child === "boolean") output[key] = String(child);
  }
  return output;
}

function envNames(key: string): string[] {
  const trimmed = key.trim();
  return [trimmed, trimmed.toUpperCase().replaceAll(/[^A-Z0-9]+/g, "_")];
}

function readRuntimeValue(key: string, context: Record<string, string>): string | undefined {
  if (typeof context[key] === "string") return context[key];
  for (const name of envNames(key)) {
    const value = process.env[name];
    if (typeof value === "string" && value.length > 0) return value;
  }
  return undefined;
}

function collectFixtureContext(
  contract: SecurityPlanContract,
  entrypoint: SecurityEntrypoint,
): Record<string, string> {
  const boundary = contract.targetBoundary as SecurityPlanContract["targetBoundary"] &
    Record<string, unknown>;
  const boundaryDetails = isRecord(boundary.details) ? boundary.details : {};
  const entrypointDetails = isRecord(entrypoint.details) ? entrypoint.details : {};
  const boundaryFixtures = isRecord(boundaryDetails.fixtureContext)
    ? boundaryDetails.fixtureContext
    : {};
  const directBoundaryFixtures = isRecord(boundary.fixtureContext) ? boundary.fixtureContext : {};
  const entrypointFixtures = isRecord(entrypointDetails.fixtureContext)
    ? entrypointDetails.fixtureContext
    : {};
  return {
    ...asStringRecord(contract.targetBoundary.fixtureContext),
    ...asStringRecord(directBoundaryFixtures),
    ...asStringRecord(boundaryFixtures),
    ...asStringRecord(entrypointFixtures),
  };
}

function httpTransport(
  entrypoint: SecurityEntrypoint,
): { type: "http"; method: string; path: string } | undefined {
  if ("transport" in entrypoint) {
    return entrypoint.transport.type === "http" ? entrypoint.transport : undefined;
  }
  return entrypoint.type === "http" && entrypoint.method && entrypoint.path
    ? { type: "http", method: entrypoint.method, path: entrypoint.path }
    : undefined;
}

function resolveString(value: string, context: Record<string, string>): string {
  return value.replace(/\$\{([^}]+)\}/g, (match, key: string) => {
    const fixtureKey = key.startsWith("fixture.") ? key.slice("fixture.".length) : key;
    const resolved = readRuntimeValue(key, context) ?? readRuntimeValue(fixtureKey, context);
    if (typeof resolved !== "string") throw new Error(`security_fixture_missing:${key}`);
    return resolved;
  });
}

function resolveUnknown(value: unknown, context: Record<string, string>): unknown {
  if (typeof value === "string") return resolveString(value, context);
  if (Array.isArray(value)) return value.map((child) => resolveUnknown(child, context));
  if (isRecord(value)) {
    const output: Record<string, unknown> = {};
    for (const [key, child] of Object.entries(value)) output[key] = resolveUnknown(child, context);
    return output;
  }
  return value;
}

function resolveCredential(
  profile: SecurityAuthenticationProfile,
  credentialContext: Record<string, string>,
): string | undefined {
  if (profile.kind === "anonymous") return undefined;
  if (typeof profile.credentialRef !== "string" || profile.credentialRef.trim().length === 0) {
    throw new Error(`security_credential_missing:${profile.id}`);
  }
  const credential = readRuntimeValue(profile.credentialRef, credentialContext);
  if (!credential) throw new Error(`security_credential_unresolved:${profile.id}`);
  return credential;
}

function applyAuthentication(args: {
  headers: StringRecord;
  profile: SecurityAuthenticationProfile;
  credentialContext: Record<string, string>;
}): StringRecord {
  const credential = resolveCredential(args.profile, args.credentialContext);
  if (!credential) return args.headers;
  const headers = { ...args.headers };
  const hasExplicitHeader = (name: string): boolean =>
    Object.keys(headers).some((key) => key.toLowerCase() === name.toLowerCase());
  if (args.profile.kind === "bearer") {
    if (!hasExplicitHeader("Authorization")) {
      headers.Authorization = /^Bearer\s+/i.test(credential) ? credential : `Bearer ${credential}`;
    }
  } else if (args.profile.kind === "basic") {
    if (!hasExplicitHeader("Authorization")) {
      headers.Authorization = /^Basic\s+/i.test(credential) ? credential : `Basic ${credential}`;
    }
  } else if (args.profile.kind === "api_key") {
    if (!hasExplicitHeader("X-API-Key")) headers["X-API-Key"] = credential;
  } else {
    if (!hasExplicitHeader("Authorization")) headers.Authorization = credential;
  }
  return headers;
}

function replacePathParameters(
  pathTemplate: string,
  pathParameters: Record<string, string>,
): string {
  return pathTemplate.replace(/\{([^}]+)\}/g, (_match, name: string) => {
    const value = pathParameters[name];
    if (typeof value !== "string") throw new Error(`security_fixture_missing:${name}`);
    return encodeURIComponent(value);
  });
}

export function buildBlackboxHttpRequest(args: {
  contract: SecurityPlanContract;
  entrypoint: SecurityEntrypoint;
  attackRequest: SecurityAttackRequest;
  authenticationProfile: SecurityAuthenticationProfile;
}): Record<string, unknown> {
  const transport = httpTransport(args.entrypoint);
  if (!transport) {
    throw new Error(`security_blackbox_entrypoint_unsupported:${args.entrypoint.id}`);
  }
  if (!args.contract.targetBoundary.baseUrl) throw new Error("security_blackbox_base_url_missing");
  const fixtureContext = collectFixtureContext(args.contract, args.entrypoint);
  const requestTransport = args.attackRequest.transport;
  const httpRequestTransport = requestTransport?.type === "http" ? requestTransport : undefined;
  const pathParameters = asStringRecord(
    resolveUnknown(
      httpRequestTransport?.pathParameters ?? args.attackRequest.pathParameters ?? {},
      fixtureContext,
    ),
  );
  const queryParameters = asStringRecord(
    resolveUnknown(
      httpRequestTransport?.query ??
        httpRequestTransport?.queryParameters ??
        args.attackRequest.query ??
        args.attackRequest.queryParameters ??
        {},
      fixtureContext,
    ),
  );
  const path = replacePathParameters(resolveString(transport.path, fixtureContext), pathParameters);
  const url = new URL(path, args.contract.targetBoundary.baseUrl);
  const allowedHosts = args.contract.targetBoundary.allowedHosts.map((host) => host.toLowerCase());
  let effectivePort = 80;
  if (url.protocol === "https:") effectivePort = 443;
  if (url.port) effectivePort = Number(url.port);
  if (
    !allowedHosts.includes(url.hostname.toLowerCase()) ||
    !args.contract.targetBoundary.allowedPorts.includes(effectivePort)
  ) {
    throw new Error(`security_target_boundary_violation:${args.entrypoint.id}`);
  }
  for (const [key, value] of Object.entries(queryParameters)) url.searchParams.set(key, value);
  const credentialContext = { ...fixtureContext };
  const resolvedHeaders = asStringRecord(
    resolveUnknown(
      httpRequestTransport?.headers ?? args.attackRequest.headers ?? {},
      credentialContext,
    ),
  );
  const headers = applyAuthentication({
    headers: resolvedHeaders,
    profile: args.authenticationProfile,
    credentialContext,
  });
  return {
    method: transport.method.toUpperCase(),
    url: url.toString(),
    headers,
    ...(typeof (httpRequestTransport?.body ?? args.attackRequest.body) !== "undefined"
      ? {
          body: resolveUnknown(
            httpRequestTransport?.body ?? args.attackRequest.body,
            fixtureContext,
          ),
        }
      : {}),
    timeoutMs: args.contract.safetyPolicy.maxDurationMs,
  };
}
