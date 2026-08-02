import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import type { SecurityPlanContract } from "@tools-security-execution-plan-spec";
import { executeSidecarAssistedSecurityMode } from "@tools-feature-security-suite";

type Call = { toolName: string; input: Record<string, unknown> };

function contract(
  expectation: {
    baseline?: Record<string, unknown>;
    attack?: Record<string, unknown>;
    runtimeTargets?: SecurityPlanContract extends infer T
      ? T extends { securityMode: "sidecar_assisted"; runtimeTargets: infer R }
        ? R
        : never
      : never;
  } = {},
): SecurityPlanContract {
  return {
    suiteType: "security",
    securityMode: "sidecar_assisted",
    targetBoundary: {
      environment: "local-ci",
      baseUrl: "http://127.0.0.1:8080",
      allowedHosts: ["127.0.0.1"],
      allowedPorts: [8080],
      externalNetworkAccess: "forbidden",
    },
    entrypoints: [{ id: "read-order", type: "http", method: "GET", path: "/orders/{id}" }],
    authenticationProfiles: [{ id: "anonymous", kind: "anonymous" }],
    attackProfiles: [
      {
        id: "authorization-boundary",
        category: "authorization",
        entrypointRef: "read-order",
        authenticationProfileRef: "anonymous",
        baseline: {
          pathParameters: { id: "own" },
          expect: { outcome: "allow", statusCodes: [200], ...(expectation.baseline ?? {}) },
        },
        attack: {
          pathParameters: { id: "foreign" },
          expect: { outcome: "deny", statusCodes: [403], ...(expectation.attack ?? {}) },
        },
      },
    ],
    runtimeTargets: expectation.runtimeTargets ?? [
      {
        id: "order-entry",
        entrypointRef: "read-order",
        probeId: "orders",
        strictLineKey: "com.example.OrderController#get:10",
        purpose: "business-entrypoint",
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
      failOnSeverity: ["critical", "high", "medium"],
      requireExhaustiveCompletion: true,
      blockedCountsAs: "fail",
    },
  };
}

function runtimeStatus(
  runtimeInstanceId: string,
  hitCount: number,
  lastHitEpoch = Date.now(),
): Record<string, unknown> {
  return {
    response: {
      status: 200,
      json: {
        hitCount,
        lastHitEpoch,
        lineResolvable: true,
        lineValidation: "resolvable",
        runtime: { runtimeInstanceId },
      },
    },
  };
}

function tempWorkspace(): string {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "mcp-jvm-security-sidecar-ut-"));
  const projectRoot = path.join(root, ".mcpjvm", "demo");
  fs.mkdirSync(projectRoot, { recursive: true });
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
  return root;
}

test("[UT][security-sidecar] resets targets and records corroborated external evidence", async () => {
  const calls: Call[] = [];
  let transportCount = 0;
  const root = tempWorkspace();
  try {
    const result = await executeSidecarAssistedSecurityMode({
      workspaceRootAbs: root,
      projectName: "demo",
      executionProfile: "security-ci",
      planName: "authorization",
      runId: "run-corroborated",
      contract: contract({
        baseline: { mustHitRuntimeTargets: ["order-entry"] },
        attack: { mustHitRuntimeTargets: ["order-entry"] },
      }) as Extract<SecurityPlanContract, { securityMode: "sidecar_assisted" }>,
      mcpInvoke: async ({ toolName, input }) => {
        calls.push({ toolName, input });
        if (toolName === "transport_execute") {
          transportCount += 1;
          return {
            structuredContent: { status: "pass", statusCode: transportCount === 1 ? 200 : 200 },
          };
        }
        const action = input.action;
        if (action === "wait_for_hit") {
          return { structuredContent: { result: { hit: true }, ...runtimeStatus("runtime-1", 1) } };
        }
        return { structuredContent: runtimeStatus("runtime-1", 0) };
      },
    });
    assert.equal(result.runStatus, "fail");
    assert.equal(result.status, "executed");
    assert.equal(
      calls.filter((call) => call.toolName === "probe" && call.input.action === "reset").length,
      2,
    );
    const artifact = JSON.parse(
      fs.readFileSync(
        path.join(
          root,
          ".mcpjvm",
          "demo",
          "plans",
          "security",
          "authorization",
          "runs",
          "run-corroborated",
          "execution.result.json",
        ),
        "utf8",
      ),
    ) as { findings: Array<{ proofClassification: string }>; evidence: Array<{ kind: string }> };
    assert.equal(artifact.findings[0]?.proofClassification, "corroborated_external");
    assert.ok(artifact.evidence.some((entry) => entry.kind === "probe"));
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("[UT][security-sidecar] rejects stale required hits after a case reset", async () => {
  const root = tempWorkspace();
  try {
    const result = await executeSidecarAssistedSecurityMode({
      workspaceRootAbs: root,
      projectName: "demo",
      executionProfile: "security-ci",
      planName: "authorization",
      contract: contract({
        baseline: { mustHitRuntimeTargets: ["order-entry"] },
        attack: { mustHitRuntimeTargets: ["order-entry"] },
      }) as Extract<SecurityPlanContract, { securityMode: "sidecar_assisted" }>,
      mcpInvoke: async ({ toolName, input }) => {
        if (toolName === "transport_execute") {
          return {
            structuredContent: {
              status: "pass",
              statusCode: 200,
            },
          };
        }
        if (input.action === "wait_for_hit") {
          return {
            structuredContent: {
              result: { hit: true },
              ...runtimeStatus("runtime-1", 1, 0),
            },
          };
        }
        return { structuredContent: runtimeStatus("runtime-1", 0) };
      },
    });
    assert.equal(result.runStatus, "blocked");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("[UT][security-sidecar] classifies a forbidden runtime reach as an internal finding", async () => {
  let transportCount = 0;
  const root = tempWorkspace();
  try {
    const result = await executeSidecarAssistedSecurityMode({
      workspaceRootAbs: root,
      projectName: "demo",
      executionProfile: "security-ci",
      planName: "authorization",
      runId: "run-internal",
      contract: contract({ attack: { mustNotHitRuntimeTargets: ["order-entry"] } }) as Extract<
        SecurityPlanContract,
        { securityMode: "sidecar_assisted" }
      >,
      mcpInvoke: async ({ toolName, input }) => {
        if (toolName === "transport_execute") {
          transportCount += 1;
          return {
            structuredContent: { status: "pass", statusCode: transportCount === 1 ? 200 : 403 },
          };
        }
        if (input.action === "status" && transportCount >= 2) {
          return { structuredContent: runtimeStatus("runtime-1", 1) };
        }
        return { structuredContent: runtimeStatus("runtime-1", 0) };
      },
    });
    assert.equal(result.runStatus, "fail");
    assert.equal(result.status, "executed");
    const artifact = JSON.parse(
      fs.readFileSync(
        path.join(
          root,
          ".mcpjvm",
          "demo",
          "plans",
          "security",
          "authorization",
          "runs",
          "run-internal",
          "execution.result.json",
        ),
        "utf8",
      ),
    ) as { findings: Array<{ proofClassification: string }> };
    assert.equal(artifact.findings[0]?.proofClassification, "internal");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("[UT][security-sidecar] fails closed on runtime identity mismatch", async () => {
  const root = tempWorkspace();
  let statusCalls = 0;
  try {
    const result = await executeSidecarAssistedSecurityMode({
      workspaceRootAbs: root,
      projectName: "demo",
      executionProfile: "security-ci",
      planName: "authorization",
      contract: contract({
        runtimeTargets: [
          {
            id: "first",
            entrypointRef: "read-order",
            probeId: "orders",
            strictLineKey: "com.example.OrderController#get:10",
            purpose: "business-entrypoint",
          },
          {
            id: "second",
            entrypointRef: "read-order",
            probeId: "orders",
            strictLineKey: "com.example.OrderService#load:20",
            purpose: "sensitive-sink",
          },
        ],
      }) as Extract<SecurityPlanContract, { securityMode: "sidecar_assisted" }>,
      mcpInvoke: async ({ toolName }) => {
        assert.equal(toolName, "probe");
        statusCalls += 1;
        return {
          structuredContent: runtimeStatus(statusCalls === 1 ? "runtime-1" : "runtime-2", 0),
        };
      },
    });
    assert.equal(result.runStatus, "blocked");
    assert.equal(result.reasonCode, "security_sidecar_runtime_mismatch");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("[UT][security-sidecar] fails closed when Probe is unavailable", async () => {
  const root = tempWorkspace();
  try {
    const result = await executeSidecarAssistedSecurityMode({
      workspaceRootAbs: root,
      projectName: "demo",
      executionProfile: "security-ci",
      planName: "authorization",
      contract: contract() as Extract<SecurityPlanContract, { securityMode: "sidecar_assisted" }>,
      mcpInvoke: async () => ({
        structuredContent: { response: { status: 503, json: {} } },
      }),
    });
    assert.equal(result.runStatus, "blocked");
    assert.equal(result.reasonCode, "security_sidecar_probe_unavailable");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("[UT][security-sidecar] fails closed on ambiguous Strict Line Key selection", async () => {
  const root = tempWorkspace();
  try {
    const result = await executeSidecarAssistedSecurityMode({
      workspaceRootAbs: root,
      projectName: "demo",
      executionProfile: "security-ci",
      planName: "authorization",
      contract: contract({
        runtimeTargets: [
          {
            id: "first",
            entrypointRef: "read-order",
            probeId: "orders-a",
            strictLineKey: "com.example.OrderController#get:10",
            purpose: "business-entrypoint",
          },
          {
            id: "second",
            entrypointRef: "read-order",
            probeId: "orders-b",
            strictLineKey: "com.example.OrderController#get:10",
            purpose: "business-entrypoint",
          },
        ],
      }) as Extract<SecurityPlanContract, { securityMode: "sidecar_assisted" }>,
      mcpInvoke: async () => ({ structuredContent: {} }),
    });
    assert.equal(result.runStatus, "blocked");
    assert.equal(result.reasonCode, "security_sidecar_runtime_target_ambiguous");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});
