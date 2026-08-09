import assert from "node:assert/strict";
import http from "node:http";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import { dispatchTransportExecutionAction } from "@tools-feature-transport-execution";
import {
  executeSecurityRuntimeSuite,
  readSecurityRunArtifact,
} from "@tools-feature-security-suite";
import type { SecurityPlanContract } from "@tools-security-execution-plan-spec";

function startServer(): Promise<{
  server: http.Server;
  port: number;
  requests: Array<{ authorization?: string }>;
}> {
  return new Promise((resolve, reject) => {
    const requests: Array<{ authorization?: string }> = [];
    const server = http.createServer((request, response) => {
      const authorization = request.headers.authorization;
      requests.push({ ...(typeof authorization === "string" ? { authorization } : {}) });
      response.statusCode = 403;
      response.setHeader("content-type", "application/json");
      response.end(JSON.stringify({ status: 403 }));
    });
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const address = server.address();
      if (!address || typeof address === "string") {
        reject(new Error("security sidecar integration server did not expose a TCP address"));
        return;
      }
      resolve({ server, port: address.port, requests });
    });
  });
}

function stopServer(server: http.Server): Promise<void> {
  return new Promise((resolve, reject) =>
    server.close((error) => (error ? reject(error) : resolve())),
  );
}

function sidecarContract(baseUrl: string): SecurityPlanContract {
  const port = Number(new URL(baseUrl).port);
  return {
    suiteType: "security",
    securityMode: "sidecar_assisted",
    targetBoundary: {
      environment: "local-ci",
      baseUrl,
      allowedHosts: ["127.0.0.1"],
      allowedPorts: [port],
      externalNetworkAccess: "forbidden",
    },
    entrypoints: [{ id: "read-order", type: "http", method: "GET", path: "/orders/{id}" }],
    authenticationProfiles: [{ id: "anonymous", kind: "anonymous" }],
    runtimeTargets: [
      {
        id: "order-entry",
        entrypointRef: "read-order",
        probeId: "orders",
        strictLineKey: "com.example.OrderController#get:10",
        purpose: "business-entrypoint",
        instrumentationTargetRef: "order-controller",
      },
    ],
    instrumentationTargets: [
      {
        id: "order-controller",
        scope: "application",
        classFqcn: "com.example.OrderController",
      },
    ],
    attackProfiles: [
      {
        id: "authorization-boundary",
        category: "authorization",
        entrypointRef: "read-order",
        authenticationProfileRef: "anonymous",
        baseline: {
          pathParameters: { id: "own" },
          expect: { outcome: "deny", statusCodes: [403], mustHitRuntimeTargets: ["order-entry"] },
        },
        attack: {
          pathParameters: { id: "foreign" },
          expect: { outcome: "deny", statusCodes: [403], mustHitRuntimeTargets: ["order-entry"] },
        },
      },
    ],
    exhaustiveness: { mode: "finite_matrix", requireAllCases: true, onIncomplete: "blocked" },
    safetyPolicy: {
      maxConcurrency: 1,
      maxRequestsPerSecond: 100,
      maxDurationMs: 5_000,
      destructivePayloads: "forbidden",
      stateMutation: "test-tenant-only",
      cleanupRequired: true,
    },
    verdictPolicy: {
      failOnSeverity: ["critical", "high"],
      requireExhaustiveCompletion: true,
      blockedCountsAs: "fail",
    },
  };
}

function writeFixture(root: string, contract: SecurityPlanContract): void {
  const probeConfigRoot = path.join(root, ".mcpjvm");
  const projectRoot = path.join(root, ".mcpjvm", "demo");
  const planRoot = path.join(projectRoot, "plans", "security", "authorization");
  fs.mkdirSync(planRoot, { recursive: true });
  fs.writeFileSync(
    path.join(probeConfigRoot, "probe-config.json"),
    JSON.stringify({
      defaultProfile: "security",
      profiles: {
        security: {
          probes: {
            orders: {
              baseUrl: "http://127.0.0.1:9193",
              include: ["com.example.**"],
              exclude: [],
            },
          },
        },
      },
    }),
    "utf8",
  );
  fs.writeFileSync(path.join(planRoot, "metadata.json"), "{}\n", "utf8");
  fs.writeFileSync(path.join(planRoot, "contract.json"), JSON.stringify(contract), "utf8");
  fs.writeFileSync(
    path.join(projectRoot, "projects.json"),
    JSON.stringify({
      workspaces: [
        {
          projectRoot: root,
          defaults: {
            orchestrator: { resumePollMax: 1, resumePollIntervalMs: 1, resumePollTimeoutMs: 1000 },
          },
          executionProfiles: [
            {
              executionProfile: "security-ci",
              suiteType: "security",
              executionPolicy: "stop_on_fail",
              plans: [{ order: 1, planName: "authorization" }],
            },
          ],
        },
      ],
    }),
    "utf8",
  );
}

test("[IT][security-sidecar] routes a validated plan through real HTTP transport and Probe actions", async () => {
  const runtime = await startServer();
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "mcp-jvm-security-sidecar-it-"));
  let transportCalls = 0;
  try {
    const contract = sidecarContract(`http://127.0.0.1:${runtime.port}`);
    writeFixture(root, contract);
    const result = await executeSecurityRuntimeSuite({
      workspaceRootAbs: root,
      projectName: "demo",
      executionProfile: "security-ci",
      mcpInvoke: async ({ toolName, input }) => {
        if (toolName === "transport_execute") {
          transportCalls += 1;
          assert.deepEqual(input.options, { wrappedOnly: true });
          const request = input.request;
          assert.equal(typeof request, "object");
          const transport = await dispatchTransportExecutionAction({
            protocol: "http",
            request: request as Record<string, unknown>,
            wrappedOnly: true,
            allowNonWrappedExecutable: false,
          });
          return { structuredContent: transport.structuredContent };
        }
        assert.equal(toolName, "probe");
        if (input.action === "wait_for_hit") {
          return {
            structuredContent: {
              result: { hit: true },
              response: {
                status: 200,
                json: { lastHitEpoch: Date.now(), runtime: { runtimeInstanceId: "runtime-1" } },
              },
            },
          };
        }
        return {
          structuredContent: {
            response: {
              status: 200,
              json: {
                hitCount: 0,
                lastHitEpoch: 0,
                lineResolvable: true,
                lineValidation: "resolvable",
                runtime: { runtimeInstanceId: "runtime-1" },
              },
            },
          },
        };
      },
    });
    assert.equal(result.status, "pass");
    assert.equal(transportCalls, 2);
    const planRun = result.planRuns[0];
    assert.ok(planRun?.runId);
    const artifact = await readSecurityRunArtifact({
      workspaceRootAbs: root,
      projectName: "demo",
      planName: "authorization",
      runId: planRun.runId,
    });
    assert.equal(artifact.ok, true);
    assert.equal(artifact.artifact.securityMode, "sidecar_assisted");
    assert.equal(artifact.artifact.coverage.complete, true);
    assert.ok(artifact.artifact.evidence.some((entry) => entry.kind === "probe"));
    assert.ok(artifact.artifact.evidence.some((entry) => entry.kind === "runtime"));
  } finally {
    await stopServer(runtime.server);
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("[IT][security-sidecar] resolves project-bound credentials for Sidecar HTTP requests", async () => {
  const runtime = await startServer();
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "mcp-jvm-security-sidecar-credential-it-"));
  try {
    const contract = sidecarContract(`http://127.0.0.1:${runtime.port}`) as Extract<
      SecurityPlanContract,
      { securityMode: "sidecar_assisted" }
    >;
    contract.authenticationProfiles = [
      {
        id: "limited-user",
        kind: "bearer",
        role: "customer",
        credentialRef: "security.limitedUserToken",
      },
    ];
    contract.attackProfiles[0]!.authenticationProfileRef = "limited-user";
    writeFixture(root, contract);

    const projectRootAbs = path.join(root, ".mcpjvm", "demo");
    const projectsPathAbs = path.join(projectRootAbs, "projects.json");
    const projectArtifact = JSON.parse(fs.readFileSync(projectsPathAbs, "utf8")) as {
      workspaces: Array<Record<string, unknown>>;
    };
    projectArtifact.workspaces[0] = {
      ...projectArtifact.workspaces[0],
      envFile: ".mcpjvm/demo/.env",
      variables: { contextBindings: { "security.limitedUserToken": "MCP_JVM_SIDECAR_AUTH" } },
    };
    fs.writeFileSync(projectsPathAbs, JSON.stringify(projectArtifact), "utf8");
    fs.writeFileSync(
      path.join(projectRootAbs, ".env"),
      "MCP_JVM_SIDECAR_AUTH=sidecar-integration-token\n",
      "utf8",
    );

    const result = await executeSecurityRuntimeSuite({
      workspaceRootAbs: root,
      projectName: "demo",
      executionProfile: "security-ci",
      mcpInvoke: async ({ toolName, input }) => {
        if (toolName === "transport_execute") {
          const transport = await dispatchTransportExecutionAction({
            protocol: "http",
            request: input.request as Record<string, unknown>,
            wrappedOnly: true,
            allowNonWrappedExecutable: false,
          });
          return { structuredContent: transport.structuredContent };
        }
        if (input.action === "wait_for_hit") {
          return {
            structuredContent: {
              result: { hit: true },
              response: {
                status: 200,
                json: { lastHitEpoch: Date.now(), runtime: { runtimeInstanceId: "runtime-1" } },
              },
            },
          };
        }
        return {
          structuredContent: {
            response: {
              status: 200,
              json: {
                hitCount: 0,
                lastHitEpoch: 0,
                lineResolvable: true,
                lineValidation: "resolvable",
                runtime: { runtimeInstanceId: "runtime-1" },
              },
            },
          },
        };
      },
    });

    assert.equal(result.status, "pass");
    assert.deepEqual(runtime.requests, [
      { authorization: "Bearer sidecar-integration-token" },
      { authorization: "Bearer sidecar-integration-token" },
    ]);
  } finally {
    await stopServer(runtime.server);
    fs.rmSync(root, { recursive: true, force: true });
  }
});
