import * as fsSync from "node:fs";
import * as fs from "node:fs/promises";
import * as path from "node:path";
import { spawn, type ChildProcess } from "node:child_process";
import * as net from "node:net";
import { setTimeout as delay } from "node:timers/promises";

import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";

function resolveRepoRoot(startDirAbs: string): string {
  let currentDirAbs = path.resolve(startDirAbs);

  while (true) {
    const packageJsonAbs = path.join(currentDirAbs, "package.json");
    if (fsSync.existsSync(packageJsonAbs)) {
      try {
        const packageJson = JSON.parse(fsSync.readFileSync(packageJsonAbs, "utf8")) as {
          name?: string;
        };
        if (packageJson.name === "mcp-java-dev-tools") {
          return currentDirAbs;
        }
      } catch {
        // Ignore invalid JSON and continue walking upward.
      }
    }

    const parentDirAbs = path.dirname(currentDirAbs);
    if (parentDirAbs === currentDirAbs) {
      break;
    }
    currentDirAbs = parentDirAbs;
  }

  throw new Error(`Unable to resolve repository root from ${startDirAbs}`);
}

export const repoRootAbs = resolveRepoRoot(__dirname);
const repoPackageJson = JSON.parse(
  fsSync.readFileSync(path.join(repoRootAbs, "package.json"), "utf8"),
) as {
  version?: string;
};
const repoVersion = typeof repoPackageJson.version === "string" ? repoPackageJson.version.trim() : "";
export const socialPlatformRootAbs = path.join(
  repoRootAbs,
  "test",
  "fixtures",
  "spring-apps",
  "social-platform",
);
export const postAppProjectRootAbs = path.join(
  socialPlatformRootAbs,
  "post-service",
  "post-app",
);
export const postAppTargetDirAbs = path.join(postAppProjectRootAbs, "target");
export const eventAppProjectRootAbs = path.join(
  socialPlatformRootAbs,
  "event-service",
  "event-app",
);
export const eventAppTargetDirAbs = path.join(eventAppProjectRootAbs, "target");
export const eventProducerAppProjectRootAbs = path.join(
  socialPlatformRootAbs,
  "event-service",
  "event-producer-app",
);
export const eventProducerAppTargetDirAbs = path.join(eventProducerAppProjectRootAbs, "target");
export const eventConsumerAppProjectRootAbs = path.join(
  socialPlatformRootAbs,
  "event-service",
  "event-consumer-app",
);
export const eventConsumerAppTargetDirAbs = path.join(eventConsumerAppProjectRootAbs, "target");
export const agentTargetDirAbs = path.join(
  repoRootAbs,
  "java-agent",
  "core",
  "core-probe",
  "target",
);
export const coreEntrypointMapperTargetDirAbs = path.join(
  repoRootAbs,
  "java-agent",
  "core",
  "core-entrypoint-mapper",
  "target",
);
export const mcpServerEntryAbs = path.join(repoRootAbs, "dist", "server.js");
export const postControllerFqcn = "com.example.social.post.app.controller.PostController";
export const postControllerSourceFileAbs = path.join(
  postAppProjectRootAbs,
  "src",
  "main",
  "java",
  "com",
  "example",
  "social",
  "post",
  "app",
  "controller",
  "PostController.java",
);
export const postServiceFqcn = "com.example.social.post.app.service.PostService";
export const postServiceSourceFileAbs = path.join(
  postAppProjectRootAbs,
  "src",
  "main",
  "java",
  "com",
  "example",
  "social",
  "post",
  "app",
  "service",
  "PostService.java",
);
export const eventControllerFqcn = "com.example.social.event.app.controller.ExampleEventController";
export const eventControllerSourceFileAbs = path.join(
  eventAppProjectRootAbs,
  "src",
  "main",
  "java",
  "com",
  "example",
  "social",
  "event",
  "app",
  "controller",
  "ExampleEventController.java",
);
export const eventListenerFqcn = "com.example.social.event.app.listener.ExampleQueueListener";
export const eventListenerSourceFileAbs = path.join(
  eventAppProjectRootAbs,
  "src",
  "main",
  "java",
  "com",
  "example",
  "social",
  "event",
  "app",
  "listener",
  "ExampleQueueListener.java",
);
export const eventProducerControllerFqcn =
  "com.example.social.event.producer.app.controller.ExampleEventController";
export const eventProducerControllerSourceFileAbs = path.join(
  eventProducerAppProjectRootAbs,
  "src",
  "main",
  "java",
  "com",
  "example",
  "social",
  "event",
  "producer",
  "app",
  "controller",
  "ExampleEventController.java",
);
export const eventConsumerListenerFqcn =
  "com.example.social.event.consumer.app.listener.ExampleQueueListener";
export const eventConsumerListenerSourceFileAbs = path.join(
  eventConsumerAppProjectRootAbs,
  "src",
  "main",
  "java",
  "com",
  "example",
  "social",
  "event",
  "consumer",
  "app",
  "listener",
  "ExampleQueueListener.java",
);

const LOG_TAIL_LIMIT = 200;

type RunningApp = {
  apiBaseUrl: string;
  probeBaseUrl: string;
  stop: () => Promise<void>;
  logs: () => string;
};

type RunningMcpClient = {
  client: InstanceType<typeof Client>;
  close: () => Promise<void>;
  logs: () => string;
};

function appendLog(buffer: string[], chunk: string | Buffer) {
  const text = String(chunk);
  if (text.length === 0) return;
  buffer.push(text);
  if (buffer.length > LOG_TAIL_LIMIT) {
    buffer.splice(0, buffer.length - LOG_TAIL_LIMIT);
  }
}

async function assertFileExists(fileAbs: string, label: string): Promise<void> {
  try {
    await fs.access(fileAbs);
  } catch {
    throw new Error(`${label} not found: ${fileAbs}`);
  }
}

async function resolveJarByPattern(args: {
  dirAbs: string;
  include: RegExp;
  exclude?: RegExp;
  label: string;
  preferredVersion?: string;
}): Promise<string> {
  let entries: string[];
  try {
    entries = await fs.readdir(args.dirAbs);
  } catch {
    throw new Error(`${args.label} directory not found: ${args.dirAbs}`);
  }

  const matches = entries
    .filter((entry) => args.include.test(entry))
    .filter((entry) => !(args.exclude?.test(entry) ?? false))
    .sort();

  if (matches.length === 0) {
    throw new Error(`${args.label} not found in ${args.dirAbs}`);
  }

  if (matches.length > 1) {
    const preferredVersion = args.preferredVersion?.trim();
    if (preferredVersion) {
      const preferredMatches = matches.filter((entry) =>
        entry.includes(`-${preferredVersion}-`) || entry.includes(`-${preferredVersion}.jar`),
      );
      if (preferredMatches.length === 1) {
        return path.join(args.dirAbs, preferredMatches[0]!);
      }
      if (preferredMatches.length > 1) {
        throw new Error(
          `${args.label} preferred version is ambiguous in ${args.dirAbs}: ${preferredMatches.join(", ")}`,
        );
      }
    }

    type VersionedEntry = { entry: string; version: string };
    const parsed = matches
      .map((entry) => {
        const versions = entry.match(/\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?/g);
        const version = versions ? versions[versions.length - 1] : undefined;
        if (!version) return undefined;
        return { entry, version } as VersionedEntry;
      })
      .filter((candidate): candidate is VersionedEntry => typeof candidate !== "undefined");

    if (parsed.length === matches.length) {
      const sorted = [...parsed].sort((left, right) =>
        compareSemanticVersions(right.version, left.version),
      );
      const best = sorted[0]!;
      const second = sorted[1];
      if (!second || compareSemanticVersions(best.version, second.version) !== 0) {
        return path.join(args.dirAbs, best.entry);
      }
      throw new Error(
        `${args.label} has multiple top-version candidates in ${args.dirAbs}: ${sorted
          .filter((item) => compareSemanticVersions(item.version, best.version) === 0)
          .map((item) => item.entry)
          .join(", ")}`,
      );
    }

    throw new Error(`${args.label} is ambiguous in ${args.dirAbs}: ${matches.join(", ")}`);
  }

  return path.join(args.dirAbs, matches[0]!);
}

function compareSemanticVersions(left: string, right: string): number {
  const leftSplit = left.split("-", 2);
  const rightSplit = right.split("-", 2);
  const leftCore = leftSplit[0] ?? "";
  const rightCore = rightSplit[0] ?? "";
  const leftPre = leftSplit[1] ?? "";
  const rightPre = rightSplit[1] ?? "";

  const leftParts = leftCore.split(".").map((value) => Number.parseInt(value, 10));
  const rightParts = rightCore.split(".").map((value) => Number.parseInt(value, 10));
  for (let index = 0; index < 3; index += 1) {
    const leftValue = leftParts[index] ?? 0;
    const rightValue = rightParts[index] ?? 0;
    if (leftValue !== rightValue) {
      return leftValue - rightValue;
    }
  }

  if (leftPre === rightPre) return 0;
  if (leftPre.length === 0) return 1;
  if (rightPre.length === 0) return -1;
  return leftPre.localeCompare(rightPre);
}

async function waitFor(
  check: () => Promise<boolean>,
  args: { timeoutMs: number; intervalMs?: number; failureMessage: string },
): Promise<void> {
  const timeoutAt = Date.now() + args.timeoutMs;
  const intervalMs = args.intervalMs ?? 500;
  while (Date.now() < timeoutAt) {
    if (await check()) return;
    await delay(intervalMs);
  }
  throw new Error(args.failureMessage);
}

async function isHttpOk(url: string): Promise<boolean> {
  try {
    const response = await fetch(url, { method: "GET" });
    return response.ok;
  } catch {
    return false;
  }
}

async function allocateFreePort(): Promise<number> {
  return await new Promise<number>((resolve, reject) => {
    const server = net.createServer();
    server.unref();
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const address = server.address();
      if (!address || typeof address === "string") {
        server.close(() => reject(new Error("Failed to allocate an ephemeral port.")));
        return;
      }
      const { port } = address;
      server.close((closeError) => {
        if (closeError) {
          reject(closeError);
          return;
        }
        resolve(port);
      });
    });
  });
}

async function forceStop(child: ChildProcess): Promise<void> {
  if (!child.pid || child.exitCode !== null) return;

  if (process.platform === "win32") {
    await new Promise<void>((resolve) => {
      const killer = spawn("taskkill", ["/pid", String(child.pid), "/t", "/f"], {
        stdio: "ignore",
        windowsHide: true,
      });
      killer.once("close", () => resolve());
      killer.once("error", () => resolve());
    });
    return;
  }

  try {
    child.kill("SIGTERM");
  } catch {
    return;
  }
  await delay(1_000);
  if (child.exitCode === null) {
    try {
      child.kill("SIGKILL");
    } catch {
      // Ignore forced kill errors during cleanup.
    }
  }
}

async function createTempProbeRegistryConfig(args: {
  workspaceRootAbs: string;
  probeBaseUrl: string;
}): Promise<{ dirAbs: string; fileAbs: string }> {
  const parentAbs = path.join(repoRootAbs, "test", ".tmp", "probe-registry");
  await fs.mkdir(parentAbs, { recursive: true });
  const dirAbs = await fs.mkdtemp(path.join(parentAbs, "mcp-"));
  const fileAbs = path.join(dirAbs, "probe-config.json");
  const registry = {
    defaultProfile: "dev",
    profiles: {
      dev: {
        probes: {
          "post-app": {
            baseUrl: args.probeBaseUrl,
            include: ["com.example.social.**"],
            exclude: ["**.config.**"],
          },
        },
      },
    },
    workspaces: [
      {
        root: args.workspaceRootAbs,
        profile: "dev",
      },
    ],
  };
  await fs.writeFile(fileAbs, `${JSON.stringify(registry, null, 2)}\n`, "utf8");
  return { dirAbs, fileAbs };
}

export async function findLineNumberBySnippet(
  fileAbs: string,
  snippet: string,
): Promise<number> {
  const source = await fs.readFile(fileAbs, "utf8");
  const lines = source.split(/\r?\n/);
  const index = lines.findIndex((line) => line.includes(snippet));
  if (index < 0) {
    throw new Error(`Snippet not found in ${fileAbs}: ${snippet}`);
  }
  return index + 1;
}

export function buildLineKey(args: { fqcn: string; methodName: string; line: number }): string {
  return `${args.fqcn}#${args.methodName}:${args.line}`;
}

async function startSpringBootAppWithAgent(args: {
  appLabel: string;
  appProjectRootAbs: string;
  appTargetDirAbs: string;
  appJarPattern: RegExp;
  appJarLabel: string;
  defaultAgentInclude?: string;
  defaultAgentExclude?: string;
  appPort?: number;
  probePort?: number;
  actuateAuthToken?: string;
  agentInclude?: string;
  agentExclude?: string;
  extraJavaArgs?: string[];
}): Promise<RunningApp> {
  const agentJarAbs = await resolveJarByPattern({
    dirAbs: agentTargetDirAbs,
    include: /^mcp-java-dev-tools-agent-.*-all\.jar$/,
    label: "java agent jar",
    preferredVersion: repoVersion,
  });
  const appJarAbs = await resolveJarByPattern({
    dirAbs: args.appTargetDirAbs,
    include: args.appJarPattern,
    exclude: /\.jar\.original$/,
    label: args.appJarLabel,
  });
  await assertFileExists(agentJarAbs, "java agent jar");
  await assertFileExists(appJarAbs, args.appJarLabel);

  const appPort = args.appPort ?? (await allocateFreePort());
  const probePort = args.probePort ?? (await allocateFreePort());
  const apiBaseUrl = `http://127.0.0.1:${appPort}`;
  const probeBaseUrl = `http://127.0.0.1:${probePort}`;
  const logBuffer: string[] = [];

  const agentInclude = args.agentInclude?.trim() ?? args.defaultAgentInclude ?? "com.example.social.**";
  const agentExclude = args.agentExclude?.trim() ?? args.defaultAgentExclude ?? "**.config.**";
  const agentOptions = [`host=127.0.0.1`, `port=${probePort}`];
  if (agentInclude.length > 0) agentOptions.push(`include=${agentInclude}`);
  if (agentExclude.length > 0) agentOptions.push(`exclude=${agentExclude}`);
  agentOptions.push("allowJava21=true");
  const javaAgentArg = `-javaagent:${agentJarAbs}=` + agentOptions.join(";");

  const javaArgs = [javaAgentArg];
  if (typeof args.actuateAuthToken === "string" && args.actuateAuthToken.trim().length > 0) {
    javaArgs.push(`-Dmcp.probe.auth.actuate.token=${args.actuateAuthToken.trim()}`);
  }
  if (Array.isArray(args.extraJavaArgs) && args.extraJavaArgs.length > 0) {
    javaArgs.push(...args.extraJavaArgs);
  }
  javaArgs.push("-jar", appJarAbs, `--server.port=${appPort}`);

  const child = spawn("java", javaArgs, {
    cwd: args.appProjectRootAbs,
    env: { ...process.env },
    stdio: ["ignore", "pipe", "pipe"],
    windowsHide: true,
  });

  child.stdout?.on("data", (chunk) => appendLog(logBuffer, chunk));
  child.stderr?.on("data", (chunk) => appendLog(logBuffer, chunk));

  try {
    await waitFor(
      async () => {
        if (child.exitCode !== null) return false;
        const appReady = await isHttpOk(`${apiBaseUrl}/actuator/health`);
        if (!appReady) return false;
        return await isHttpOk(`${probeBaseUrl}/__probe/status?key=fixture.health.Check#noop:1`);
      },
      {
        timeoutMs: 60_000,
        intervalMs: 750,
        failureMessage:
          `${args.appLabel} failed to become ready. apiBaseUrl=${apiBaseUrl} probeBaseUrl=${probeBaseUrl}\n` +
          logBuffer.join(""),
      },
    );
  } catch (error) {
    await forceStop(child);
    throw error;
  }

  return {
    apiBaseUrl,
    probeBaseUrl,
    stop: async () => {
      await forceStop(child);
    },
    logs: () => logBuffer.join(""),
  };
}

export async function startPostAppWithAgent(args?: {
  appPort?: number;
  probePort?: number;
  actuateAuthToken?: string;
  agentInclude?: string;
  agentExclude?: string;
}): Promise<RunningApp> {
  return await startSpringBootAppWithAgent({
    appLabel: "post-app",
    appProjectRootAbs: postAppProjectRootAbs,
    appTargetDirAbs: postAppTargetDirAbs,
    appJarPattern: /^post-app-.*\.jar$/,
    appJarLabel: "post-app jar",
    ...(typeof args?.appPort === "number" ? { appPort: args.appPort } : {}),
    ...(typeof args?.probePort === "number" ? { probePort: args.probePort } : {}),
    ...(typeof args?.actuateAuthToken === "string" ? { actuateAuthToken: args.actuateAuthToken } : {}),
    ...(typeof args?.agentInclude === "string" ? { agentInclude: args.agentInclude } : {}),
    ...(typeof args?.agentExclude === "string" ? { agentExclude: args.agentExclude } : {}),
  });
}

export async function startEventAppWithAgent(args?: {
  appPort?: number;
  probePort?: number;
  actuateAuthToken?: string;
  agentInclude?: string;
  agentExclude?: string;
}): Promise<RunningApp> {
  return await startSpringBootAppWithAgent({
    appLabel: "event-app",
    appProjectRootAbs: eventAppProjectRootAbs,
    appTargetDirAbs: eventAppTargetDirAbs,
    appJarPattern: /^event-app-.*\.jar$/,
    appJarLabel: "event-app jar",
    ...(typeof args?.appPort === "number" ? { appPort: args.appPort } : {}),
    ...(typeof args?.probePort === "number" ? { probePort: args.probePort } : {}),
    ...(typeof args?.actuateAuthToken === "string" ? { actuateAuthToken: args.actuateAuthToken } : {}),
    ...(typeof args?.agentInclude === "string" ? { agentInclude: args.agentInclude } : {}),
    ...(typeof args?.agentExclude === "string" ? { agentExclude: args.agentExclude } : {}),
  });
}

export async function startEventProducerAppWithAgent(args?: {
  appPort?: number;
  probePort?: number;
  actuateAuthToken?: string;
  agentInclude?: string;
  agentExclude?: string;
  consumerBaseUrl?: string;
}): Promise<RunningApp> {
  return await startSpringBootAppWithAgent({
    appLabel: "event-producer-app",
    appProjectRootAbs: eventProducerAppProjectRootAbs,
    appTargetDirAbs: eventProducerAppTargetDirAbs,
    appJarPattern: /^event-producer-app-.*\.jar$/,
    appJarLabel: "event-producer-app jar",
    ...(typeof args?.appPort === "number" ? { appPort: args.appPort } : {}),
    ...(typeof args?.probePort === "number" ? { probePort: args.probePort } : {}),
    ...(typeof args?.actuateAuthToken === "string" ? { actuateAuthToken: args.actuateAuthToken } : {}),
    ...(typeof args?.agentInclude === "string" ? { agentInclude: args.agentInclude } : {}),
    ...(typeof args?.agentExclude === "string" ? { agentExclude: args.agentExclude } : {}),
    ...(typeof args?.consumerBaseUrl === "string"
      ? { extraJavaArgs: [`-Dfixture.consumer.base-url=${args.consumerBaseUrl}`] }
      : {}),
  });
}

export async function startEventConsumerAppWithAgent(args?: {
  appPort?: number;
  probePort?: number;
  actuateAuthToken?: string;
  agentInclude?: string;
  agentExclude?: string;
  sqliteFileAbs?: string;
}): Promise<RunningApp> {
  const extraJavaArgs: string[] = [];
  if (typeof args?.sqliteFileAbs === "string" && args.sqliteFileAbs.trim().length > 0) {
    extraJavaArgs.push(`-Dfixture.sqlite.file=${args.sqliteFileAbs}`);
  }
  return await startSpringBootAppWithAgent({
    appLabel: "event-consumer-app",
    appProjectRootAbs: eventConsumerAppProjectRootAbs,
    appTargetDirAbs: eventConsumerAppTargetDirAbs,
    appJarPattern: /^event-consumer-app-.*\.jar$/,
    appJarLabel: "event-consumer-app jar",
    ...(typeof args?.appPort === "number" ? { appPort: args.appPort } : {}),
    ...(typeof args?.probePort === "number" ? { probePort: args.probePort } : {}),
    ...(typeof args?.actuateAuthToken === "string" ? { actuateAuthToken: args.actuateAuthToken } : {}),
    ...(typeof args?.agentInclude === "string" ? { agentInclude: args.agentInclude } : {}),
    ...(typeof args?.agentExclude === "string" ? { agentExclude: args.agentExclude } : {}),
    ...(extraJavaArgs.length > 0 ? { extraJavaArgs } : {}),
  });
}

export async function startMcpClient(args: {
  workspaceRootAbs: string;
  probeBaseUrl: string;
  extraEnv?: Record<string, string>;
}): Promise<RunningMcpClient> {
  await assertFileExists(mcpServerEntryAbs, "mcp server dist entry");

  const logBuffer: string[] = [];
  const explicitProbeConfigFile = args.extraEnv?.MCP_PROBE_CONFIG_FILE;
  const tempProbeConfig =
    typeof explicitProbeConfigFile === "string" && explicitProbeConfigFile.trim().length > 0
      ? undefined
      : await createTempProbeRegistryConfig({
          workspaceRootAbs: args.workspaceRootAbs,
          probeBaseUrl: args.probeBaseUrl,
        });
  const transport = new StdioClientTransport({
    command: process.execPath,
    args: [mcpServerEntryAbs],
    cwd: repoRootAbs,
    env: {
      MCP_WORKSPACE_ROOT: args.workspaceRootAbs,
      ...(tempProbeConfig ? { MCP_PROBE_CONFIG_FILE: tempProbeConfig.fileAbs } : {}),
      ...(args.extraEnv ?? {}),
    },
    stderr: "pipe",
  });

  transport.stderr?.on("data", (chunk) => appendLog(logBuffer, chunk));

  const client = new Client({
    name: "mcp-java-dev-tools-it",
    version: "it",
  });

  try {
    await client.connect(transport);
  } catch (error) {
    await transport.close().catch(() => undefined);
    if (tempProbeConfig) await fs.rm(tempProbeConfig.dirAbs, { recursive: true, force: true }).catch(() => undefined);
    throw new Error(`Failed to start MCP client.\n${logBuffer.join("\n")}\n${String(error)}`);
  }

  return {
    client,
    close: async () => {
      await transport.close();
      if (tempProbeConfig) await fs.rm(tempProbeConfig.dirAbs, { recursive: true, force: true });
    },
    logs: () => logBuffer.join(""),
  };
}

export async function resolveCoreEntrypointMapperJar(): Promise<string> {
  const jarAbs = await resolveJarByPattern({
    dirAbs: coreEntrypointMapperTargetDirAbs,
    include: /^mcp-java-dev-tools-core-entrypoint-mapper-.*-all\.jar$/,
    label: "core-entrypoint-mapper all jar",
    preferredVersion: repoVersion,
  });
  await assertFileExists(jarAbs, "core-entrypoint-mapper all jar");
  return jarAbs;
}
