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

function startSecurityServer(args: {
  foreignStatus: number;
  catalogDefaultPairwise?: boolean;
}): Promise<IntegrationServer> {
  return new Promise((resolve, reject) => {
    const requests: IntegrationServer["requests"] = [];
    const server = http.createServer((request, response) => {
      requests.push({
        url: request.url ?? "",
        ...(typeof request.headers.authorization === "string"
          ? { authorization: request.headers.authorization }
          : {}),
      });
      const isAttack = args.catalogDefaultPairwise && requests.length % 2 === 0;
      if (isAttack) {
        if (!request.headers.authorization) {
          response.statusCode = 401;
          response.setHeader("content-type", "application/json");
          response.end(JSON.stringify({ status: response.statusCode }));
          return;
        }
        if (request.url?.includes("foreign-order")) {
          response.statusCode = args.foreignStatus;
          response.setHeader("content-type", "application/json");
          response.end(JSON.stringify({ status: response.statusCode }));
          return;
        }
        const requestHasBoundedInput =
          request.url?.includes("securityInput") ||
          request.url?.includes("safe%2Fchild") ||
          Number(request.headers["content-length"] ?? 0) > 0;
        response.statusCode = requestHasBoundedInput ? 400 : args.foreignStatus;
      } else {
        response.statusCode = request.url?.includes("foreign-order") ? args.foreignStatus : 200;
      }
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
    customCases: [
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
          variables: { contextBindings: { "security.integrationToken": "SECURITY_INTEGRATIONTOKEN" } },
        },
      ],
    }),
    "utf8",
  );
}

async function executeAgainstWrappedTransport(args: {
  root: string;
  contract: SecurityPlanContract;
  prepareWorkspace?: (args: { root: string; projectName: string }) => void;
}): Promise<Awaited<ReturnType<typeof executeSecurityRuntimeSuite>>> {
  writeSecurityFixture(args);
  args.prepareWorkspace?.({ root: args.root, projectName: "demo" });
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

test("[IT][security-blackbox] resolves a credentialRef through the selected project context binding", async () => {
  const runtime = await startSecurityServer({ foreignStatus: 403 });
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "mcp-jvm-security-project-credential-it-"));
  const envName = "MCP_JVM_SECURITY_IT_AUTH_BEARER";
  const previous = process.env[envName];
  delete process.env[envName];
  try {
    const contract = securityContract(`http://127.0.0.1:${runtime.port}`);
    contract.authenticationProfiles = [
      {
        id: "limited-user",
        kind: "bearer",
        role: "customer",
        credentialRef: "security.limitedUserToken",
      },
    ];
    const result = await executeAgainstWrappedTransport({
      root,
      contract,
      prepareWorkspace: ({ root: workspaceRootAbs, projectName }) => {
        const projectRootAbs = path.join(workspaceRootAbs, ".mcpjvm", projectName);
        const projectsPathAbs = path.join(projectRootAbs, "projects.json");
        const projectArtifact = JSON.parse(fs.readFileSync(projectsPathAbs, "utf8")) as {
          workspaces: Array<Record<string, unknown>>;
        };
        projectArtifact.workspaces[0] = {
          ...projectArtifact.workspaces[0],
          envFile: `.mcpjvm/${projectName}/.env`,
          variables: { contextBindings: { "security.limitedUserToken": envName } },
        };
        fs.writeFileSync(projectsPathAbs, JSON.stringify(projectArtifact), "utf8");
        fs.writeFileSync(
          path.join(projectRootAbs, ".env"),
          `${envName}=integration-bearer-token\n`,
          "utf8",
        );
      },
    });

    assert.equal(result.status, "pass");
    assert.deepEqual(
      runtime.requests.map((request) => request.authorization),
      ["Bearer integration-bearer-token", "Bearer integration-bearer-token"],
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
    assert.equal(JSON.stringify(artifact.artifact).includes("integration-bearer-token"), false);
  } finally {
    await stopServer(runtime.server);
    if (typeof previous === "string") process.env[envName] = previous;
    else delete process.env[envName];
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("[IT][security-blackbox] blocks missing credential context bindings before target or request execution", async () => {
  const runtime = await startSecurityServer({ foreignStatus: 403 });
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "mcp-jvm-security-missing-binding-it-"));
  try {
    const contract = securityContract(`http://127.0.0.1:${runtime.port}`);
    contract.authenticationProfiles = [
      {
        id: "limited-user",
        kind: "bearer",
        role: "customer",
        credentialRef: "security.limitedUserToken",
      },
    ];
    const result = await executeAgainstWrappedTransport({ root, contract });

    assert.equal(result.status, "blocked");
    assert.equal(runtime.requests.length, 0);
    const blockedPlanRun = result.planRuns[0];
    assert.ok(blockedPlanRun);
    assert.equal(blockedPlanRun.blockedReasonCode, "security_credential_context_binding_missing");
    const blockedReasonMeta = blockedPlanRun.blockedReasonMeta as
      | { requiredUserAction?: string[] }
      | undefined;
    assert.match(
      String(blockedReasonMeta?.requiredUserAction?.[0] ?? ""),
      /variables\.contextBindings\.security\.limitedUserToken/i,
    );
  } finally {
    await stopServer(runtime.server);
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("[IT][security-blackbox] executes prePlan refresh before resolving a credential context binding", async () => {
  const runtime = await startSecurityServer({ foreignStatus: 403 });
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "mcp-jvm-security-preplan-refresh-it-"));
  const envName = "MCP_JVM_SECURITY_REFRESHED_TOKEN";
  try {
    const contract = securityContract(`http://127.0.0.1:${runtime.port}`);
    contract.authenticationProfiles = [
      {
        id: "limited-user",
        kind: "bearer",
        role: "customer",
        credentialRef: "security.refreshedToken",
      },
    ];
    const result = await executeAgainstWrappedTransport({
      root,
      contract,
      prepareWorkspace: ({ root: workspaceRootAbs, projectName }) => {
        const projectRootAbs = path.join(workspaceRootAbs, ".mcpjvm", projectName);
        const projectsPathAbs = path.join(projectRootAbs, "projects.json");
        const envFileAbs = path.join(projectRootAbs, ".env");
        const refreshScriptAbs = path.join(projectRootAbs, "refresh-token.cjs");
        const projectArtifact = JSON.parse(fs.readFileSync(projectsPathAbs, "utf8")) as {
          workspaces: Array<Record<string, unknown>>;
        };
        projectArtifact.workspaces[0] = {
          ...projectArtifact.workspaces[0],
          envFile: `.mcpjvm/${projectName}/.env`,
          variables: { contextBindings: { "security.refreshedToken": envName } },
          scripts: [
            {
              name: "refresh-token",
              command: "node",
              args: [`.mcpjvm/${projectName}/refresh-token.cjs`],
              env: { MCP_JVM_REPRO_ENV_FILE: envFileAbs },
              phase: "prePlan",
            },
          ],
          executionProfiles: [
            {
              executionProfile: "security-ci",
              suiteType: "security",
              executionPolicy: "stop_on_fail",
              plans: [{ order: 1, planName: "authorization" }],
              scriptRefs: [{ name: "refresh-token", phase: "prePlan" }],
            },
          ],
        };
        fs.writeFileSync(
          refreshScriptAbs,
          "require('node:fs').writeFileSync(process.env.MCP_JVM_REPRO_ENV_FILE, 'MCP_JVM_SECURITY_REFRESHED_TOKEN=refreshed-token\\n');\n",
          "utf8",
        );
        fs.writeFileSync(projectsPathAbs, JSON.stringify(projectArtifact), "utf8");
      },
    });

    assert.equal(result.status, "pass");
    assert.deepEqual(
      runtime.requests.map((request) => request.authorization),
      ["Bearer refreshed-token", "Bearer refreshed-token"],
    );
  } finally {
    await stopServer(runtime.server);
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

test("[IT][security-blackbox] generates and executes the catalog-default matrix with fixture namespaces", async () => {
  const runtime = await startSecurityServer({ foreignStatus: 403, catalogDefaultPairwise: true });
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "mcp-jvm-security-it-catalog-default-"));
  const envName = "SECURITY_INTEGRATIONTOKEN";
  const previous = process.env[envName];
  process.env[envName] = "integration-token";
  try {
    const contract: SecurityPlanContract = {
      suiteType: "security",
      securityMode: "blackbox",
      targetBoundary: {
        environment: "local-ci",
        baseUrl: `http://127.0.0.1:${runtime.port}`,
        allowedHosts: ["127.0.0.1"],
        allowedPorts: [runtime.port],
        externalNetworkAccess: "forbidden",
        fixtureContext: {
          ownResourceId: "own-order",
          foreignResourceId: "foreign-order",
          tenantA: "tenant-a",
          tenantB: "tenant-b",
          anonymousRequest: "anonymous-request",
          boundedPathTraversalCase: "safe/child",
          safePath: "safe/child",
          localCallbackUrl: `http://127.0.0.1:${runtime.port}/callback`,
          allowListedHost: "127.0.0.1",
          safeUploadFilename: "safe.txt",
          safeContentType: "text/plain",
          boundedInput: "bounded-input",
          safeSerializedValue: "safe-serialized-value",
          safeInput: "safe-input",
        },
      },
      entrypoints: [
        {
          id: "read-order",
          transport: { type: "http", method: "POST", path: "/orders/{orderId}" },
          baseline: {
            pathParameters: { orderId: "${fixture.ownResourceId}" },
            query: { securityInput: "${fixture.safeInput}" },
            headers: { "Content-Type": "application/json" },
            body: { data: "${fixture.safeInput}" },
          },
        },
      ],
      authenticationProfiles: [
        {
          id: "limited-user",
          kind: "bearer",
          role: "customer",
          credentialRef: "security.integrationToken",
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
        failOnSeverity: ["critical", "high"],
        requireExhaustiveCompletion: true,
        blockedCountsAs: "fail",
      },
    };
    const result = await executeAgainstWrappedTransport({ root, contract });
    assert.equal(result.status, "pass");
    assert.ok(runtime.requests.length > 2);
    const planRun = result.planRuns[0];
    assert.ok(planRun?.runId);
    const artifact = await readSecurityRunArtifact({
      workspaceRootAbs: root,
      projectName: "demo",
      planName: "authorization",
      runId: planRun.runId,
    });
    assert.ok(artifact.ok);
    const knowledgeSnapshot = artifact.artifact.matrix.knowledgeSnapshot;
    assert.ok(knowledgeSnapshot);
    assert.equal(knowledgeSnapshot.selection, "catalog_default");
    assert.ok(knowledgeSnapshot.packs.length > 1);
    assert.equal(artifact.artifact.coverage.blockedCount, 0);
    assert.equal(
      artifact.artifact.coverage.cases.some(
        (securityCase) => securityCase.reasonCode === "security_blackbox_fixture_unresolved",
      ),
      false,
    );
    const resumed = await executeAgainstWrappedTransport({ root, contract });
    assert.equal(resumed.status, "pass");
    const resumedPlanRun = resumed.planRuns[0];
    assert.ok(resumedPlanRun?.runId);
    const resumedArtifact = await readSecurityRunArtifact({
      workspaceRootAbs: root,
      projectName: "demo",
      planName: "authorization",
      runId: resumedPlanRun.runId,
    });
    assert.ok(resumedArtifact.ok);
    assert.equal(resumedArtifact.artifact.matrix.knowledgeSnapshot?.selection, "catalog_default");
  } finally {
    await stopServer(runtime.server);
    if (typeof previous === "string") process.env[envName] = previous;
    else delete process.env[envName];
    fs.rmSync(root, { recursive: true, force: true });
  }
});
