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

type IntegrationServer = {
  server: http.Server;
  port: number;
  requests: Array<{ url: string; authorization?: string }>;
};

function startSecurityServer(args: { foreignStatus: number }): Promise<IntegrationServer> {
  return new Promise((resolve, reject) => {
    const requests: IntegrationServer["requests"] = [];
    const server = http.createServer((request, response) => {
      requests.push({
        url: request.url ?? "",
        ...(typeof request.headers.authorization === "string"
          ? { authorization: request.headers.authorization }
          : {}),
      });
      response.statusCode = request.url?.includes("foreign-order") ? args.foreignStatus : 200;
      response.setHeader("content-type", "application/json");
      response.end(JSON.stringify({ status: response.statusCode }));
    });
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const address = server.address();
      if (!address || typeof address === "string") {
        reject(new Error("security integration server did not expose a TCP address"));
        return;
      }
      resolve({ server, port: address.port, requests });
    });
  });
}

function stopServer(server: http.Server): Promise<void> {
  return new Promise((resolve, reject) => {
    server.close((error) => (error ? reject(error) : resolve()));
  });
}

function securityContract(baseUrl: string): SecurityPlanContract {
  return {
    suiteType: "security",
    securityMode: "blackbox",
    targetBoundary: {
      environment: "local-ci",
      baseUrl,
      allowedHosts: ["127.0.0.1"],
      allowedPorts: [new URL(baseUrl).port ? Number(new URL(baseUrl).port) : 80],
      externalNetworkAccess: "forbidden",
      fixtureContext: {
        ownResourceId: "own-order",
        foreignResourceId: "foreign-order",
      },
    },
    securityKnowledge: { packRefs: ["authorization-idor@1.0.0"] },
    entrypoints: [{ id: "read-order", type: "http", method: "GET", path: "/orders/{orderId}" }],
    authenticationProfiles: [
      {
        id: "limited-user",
        kind: "bearer",
        role: "customer",
        credentialRef: "security.integrationToken",
      },
    ],
    attackProfiles: [
      {
        id: "cross-tenant-order-access",
        category: "authorization",
        entrypointRef: "read-order",
        authenticationProfileRef: "limited-user",
        baseline: {
          pathParameters: { orderId: "own-order" },
          expect: { outcome: "allow", statusCodes: [200] },
        },
        attack: {
          pathParameters: { orderId: "foreign-order" },
          expect: { outcome: "deny", statusCodes: [403, 404] },
        },
      },
    ],
    exhaustiveness: { mode: "finite_matrix", requireAllCases: true, onIncomplete: "blocked" },
    safetyPolicy: {
      maxConcurrency: 1,
      maxRequestsPerSecond: 1000,
      maxDurationMs: 5000,
      destructivePayloads: "forbidden",
      stateMutation: "test-tenant-only",
      cleanupRequired: true,
    },
    verdictPolicy: {
      failOnSeverity: ["high"],
      requireExhaustiveCompletion: true,
      blockedCountsAs: "fail",
    },
  };
}

function writeSecurityFixture(args: { root: string; contract: SecurityPlanContract }): void {
  const projectRoot = path.join(args.root, ".mcpjvm", "demo");
  const planRoot = path.join(projectRoot, "plans", "security", "authorization");
  fs.mkdirSync(planRoot, { recursive: true });
  fs.writeFileSync(path.join(planRoot, "metadata.json"), "{}\n", "utf8");
  fs.writeFileSync(path.join(planRoot, "contract.json"), JSON.stringify(args.contract), "utf8");
  fs.writeFileSync(
    path.join(projectRoot, "projects.json"),
    JSON.stringify({
      workspaces: [
        {
          projectRoot: args.root,
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

async function executeAgainstWrappedTransport(args: {
  root: string;
  contract: SecurityPlanContract;
}): Promise<Awaited<ReturnType<typeof executeSecurityRuntimeSuite>>> {
  writeSecurityFixture(args);
  return executeSecurityRuntimeSuite({
    workspaceRootAbs: args.root,
    projectName: "demo",
    executionProfile: "security-ci",
    mcpInvoke: async ({ toolName, input }) => {
      assert.equal(toolName, "transport_execute");
      assert.deepEqual(input.options, { wrappedOnly: true });
      const request = input.request;
      if (typeof request !== "object" || request === null || Array.isArray(request)) {
        throw new Error("security integration request was not an object");
      }
      const result = await dispatchTransportExecutionAction({
        protocol: "http",
        request: request as Record<string, unknown>,
        wrappedOnly: true,
        allowNonWrappedExecutable: false,
      });
      return { structuredContent: result.structuredContent };
    },
  });
}

test("[IT][security-blackbox] executes real HTTP baseline and attack requests through wrapped transport", async () => {
  const runtime = await startSecurityServer({ foreignStatus: 403 });
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "mcp-jvm-security-it-pass-"));
  const envName = "SECURITY_INTEGRATIONTOKEN";
  const previous = process.env[envName];
  process.env[envName] = "integration-token";
  try {
    const result = await executeAgainstWrappedTransport({
      root,
      contract: securityContract(`http://127.0.0.1:${runtime.port}`),
    });
    assert.equal(result.status, "pass");
    assert.equal(runtime.requests.length, 2);
    assert.deepEqual(
      runtime.requests.map((request) => request.authorization),
      ["Bearer integration-token", "Bearer integration-token"],
    );
    const planRun = result.planRuns[0];
    assert.ok(planRun?.runId);
    const artifact = await readSecurityRunArtifact({
      workspaceRootAbs: root,
      projectName: "demo",
      planName: "authorization",
      runId: planRun.runId,
    });
    assert.ok(artifact.ok);
    assert.equal(artifact.artifact.coverage.complete, true);
    assert.equal(artifact.artifact.coverage.passedCount, 1);
    assert.equal(JSON.stringify(artifact.artifact).includes("integration-token"), false);
  } finally {
    await stopServer(runtime.server);
    if (typeof previous === "string") process.env[envName] = previous;
    else delete process.env[envName];
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("[IT][security-blackbox] persists an external confirmed finding for an allowed foreign response", async () => {
  const runtime = await startSecurityServer({ foreignStatus: 200 });
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "mcp-jvm-security-it-finding-"));
  const envName = "SECURITY_INTEGRATIONTOKEN";
  const previous = process.env[envName];
  process.env[envName] = "integration-token";
  try {
    const result = await executeAgainstWrappedTransport({
      root,
      contract: securityContract(`http://127.0.0.1:${runtime.port}`),
    });
    assert.equal(result.status, "fail");
    const planRun = result.planRuns[0];
    assert.ok(planRun?.runId);
    const artifact = await readSecurityRunArtifact({
      workspaceRootAbs: root,
      projectName: "demo",
      planName: "authorization",
      runId: planRun.runId,
    });
    assert.ok(artifact.ok);
    assert.equal(artifact.artifact.coverage.confirmedCount, 1);
    assert.equal(artifact.artifact.findings[0]?.proofClassification, "external");
  } finally {
    await stopServer(runtime.server);
    if (typeof previous === "string") process.env[envName] = previous;
    else delete process.env[envName];
    fs.rmSync(root, { recursive: true, force: true });
  }
});
