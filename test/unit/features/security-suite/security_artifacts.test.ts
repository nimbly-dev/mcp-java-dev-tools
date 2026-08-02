import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import {
  dispatchSecuritySuiteAction,
  executeSecurityRuntimeSuite,
  readSecurityRunArtifact,
  writeSecurityRunArtifacts,
} from "@tools-feature-security-suite";
import { dispatchArtifactManagementAction } from "@tools-feature-artifact-management";
import type { SecurityPlanContract } from "@tools-security-execution-plan-spec";

function securityContract(): SecurityPlanContract {
  return {
    suiteType: "security",
    securityMode: "blackbox",
    targetBoundary: {
      environment: "local-ci",
      baseUrl: "http://127.0.0.1:8080",
      allowedHosts: ["127.0.0.1"],
      allowedPorts: [8080],
      externalNetworkAccess: "forbidden",
    },
    securityKnowledge: { packRefs: ["web-api-core@1.0.0"] },
    entrypoints: [{ id: "health", type: "http", method: "GET", path: "/health" }],
    authenticationProfiles: [{ id: "anonymous", kind: "anonymous" }],
    attackProfiles: [
      {
        id: "health-check",
        category: "other",
        entrypointRef: "health",
        authenticationProfileRef: "anonymous",
        baseline: { expect: { outcome: "allow", statusCodes: [200] } },
        attack: { expect: { outcome: "deny", statusCodes: [403, 404] } },
      },
    ],
    exhaustiveness: { mode: "finite_matrix", requireAllCases: true, onIncomplete: "blocked" },
    safetyPolicy: {
      maxConcurrency: 1,
      maxRequestsPerSecond: 1,
      maxDurationMs: 1000,
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

test("[UT][security-suite] writes and reads canonical Security run Artifacts", async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "mcp-jvm-security-artifact-"));
  try {
    fs.mkdirSync(path.join(root, ".mcpjvm", "demo"), { recursive: true });
    fs.writeFileSync(path.join(root, ".mcpjvm", "demo", "projects.json"), "{}\n", "utf8");
    const written = await writeSecurityRunArtifacts({
      workspaceRootAbs: root,
      projectName: "demo",
      planName: "authorization",
      runId: "run-1",
      executionProfile: "security-ci",
      securityMode: "blackbox",
      status: "pass",
      matrix: { mode: "finite_matrix", plannedCaseIds: ["case-1"], plannedCount: 1 },
      coverage: {
        plannedCount: 1,
        executedCount: 1,
        passedCount: 1,
        confirmedCount: 0,
        notApplicableCount: 0,
        blockedCount: 0,
        complete: true,
        cases: [],
      },
      findings: [],
      evidence: [],
    });
    assert.match(written.runDirAbs, /plans[\\/]security[\\/]authorization[\\/]runs[\\/]run-1$/);
    const read = await readSecurityRunArtifact({
      workspaceRootAbs: root,
      projectName: "demo",
      planName: "authorization",
      runId: "run-1",
    });
    assert.equal(read.ok, true);
    assert.equal(read.artifact.coverage.complete, true);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("[UT][security-plan-artifact] rejects traversal before resolving the project Artifact", async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "mcp-jvm-security-path-"));
  try {
    const result = await dispatchArtifactManagementAction({
      workspaceRootAbs: root,
      request: {
        artifactType: "security_plan",
        action: "read",
        input: { projectName: "demo", planName: "../escape" },
      },
    });
    assert.equal(result.structuredContent.reasonCode, "security_plan_name_invalid");
    assert.equal(fs.existsSync(path.join(root, ".mcpjvm", "escape")), false);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("[UT][security-plan-artifact] rejects resolved credentials before writing the contract Artifact", async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "mcp-jvm-security-secret-"));
  try {
    const contract = securityContract();
    contract.authenticationProfiles = [
      { id: "anonymous", kind: "bearer", credentialRef: "Bearer resolved-token" },
    ];
    const result = await dispatchArtifactManagementAction({
      workspaceRootAbs: root,
      request: {
        artifactType: "security_plan",
        action: "upsert",
        input: {
          projectName: "demo",
          planName: "authorization",
          payload: { contract },
        },
      },
    });
    assert.equal(result.structuredContent.reasonCode, "security_contract_secret_persisted");
    assert.equal(
      fs.existsSync(path.join(root, ".mcpjvm", "demo", "plans", "security", "authorization", "contract.json")),
      false,
    );
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("[UT][security-suite] fails closed before mode execution when project context is missing", async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "mcp-jvm-security-foundation-"));
  try {
    const result = await dispatchSecuritySuiteAction({
      action: "execute",
      input: {
        workspaceRootAbs: root,
        projectName: "missing",
        executionProfile: "security-ci",
        mcpInvoke: async () => ({ structuredContent: {} }),
      },
    });
    assert.equal(result.status, "blocked");
    assert.equal(result.reasonCode, "project_artifact_missing");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("[UT][security-suite] processes ordered plan slices and resumes with prior runs", async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "mcp-jvm-security-progress-"));
  try {
    const projectRoot = path.join(root, ".mcpjvm", "demo");
    for (const planName of ["plan-a", "plan-b"]) {
      const planRoot = path.join(projectRoot, "plans", "security", planName);
      fs.mkdirSync(planRoot, { recursive: true });
      fs.writeFileSync(path.join(planRoot, "metadata.json"), "{}\n", "utf8");
      fs.writeFileSync(path.join(planRoot, "contract.json"), JSON.stringify(securityContract()), "utf8");
    }
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
                executionPolicy: "continue_on_fail",
                plans: [
                  { order: 1, planName: "plan-a" },
                  { order: 2, planName: "plan-b" },
                ],
              },
            ],
          },
        ],
      }),
      "utf8",
    );

    const first = await executeSecurityRuntimeSuite({
      workspaceRootAbs: root,
      projectName: "demo",
      executionProfile: "security-ci",
      maxPlansPerCall: 1,
      mcpInvoke: async () => ({ structuredContent: {} }),
    });
    assert.equal(first.status, "in_progress");
    assert.equal(first.nextPlanOrder, 2);
    assert.equal(first.planRuns.length, 1);
    if (typeof first.suiteRunId !== "string") throw new Error("security suite run id missing");

    const second = await executeSecurityRuntimeSuite({
      workspaceRootAbs: root,
      projectName: "demo",
      executionProfile: "security-ci",
      suiteRunId: first.suiteRunId,
      startPlanOrder: first.nextPlanOrder,
      priorPlanRuns: first.planRuns,
      maxPlansPerCall: 1,
      mcpInvoke: async () => ({ structuredContent: {} }),
    });
    assert.equal(second.status, "partial_fail");
    assert.equal(second.planRuns.length, 2);
    assert.equal(second.planRuns[1]?.planName, "plan-b");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("[UT][security-suite] sanitizes sensitive finding and evidence diagnostics", async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "mcp-jvm-security-redaction-"));
  try {
    fs.mkdirSync(path.join(root, ".mcpjvm", "demo"), { recursive: true });
    fs.writeFileSync(path.join(root, ".mcpjvm", "demo", "projects.json"), "{}\n", "utf8");
    await writeSecurityRunArtifacts({
      workspaceRootAbs: root,
      projectName: "demo",
      planName: "authorization",
      runId: "run-1",
      executionProfile: "security-ci",
      securityMode: "blackbox",
      status: "blocked",
      matrix: { mode: "finite_matrix", plannedCaseIds: ["case-1"], plannedCount: 1 },
      coverage: {
        plannedCount: 1,
        executedCount: 0,
        passedCount: 0,
        confirmedCount: 0,
        notApplicableCount: 0,
        blockedCount: 1,
        complete: false,
        cases: [],
      },
      findings: [
        {
          id: "finding-1",
          severity: "high",
          category: "authorization",
          title: "Authorization token=super-secret",
          description: "PASSWORD=super-secret Bearer abc.def.ghi",
          outcome: "confirmed",
          proofClassification: "external",
          evidenceRefIds: ["evidence-1"],
        },
      ],
      evidence: [
        {
          id: "evidence-1",
          kind: "diagnostic",
          summary: "Authorization: Bearer super-secret",
          artifactPath: "C:/tmp/token=super-secret",
          redacted: true,
        },
      ],
    });
    const persisted = fs.readFileSync(
      path.join(root, ".mcpjvm", "demo", "plans", "security", "authorization", "runs", "run-1", "execution.result.json"),
      "utf8",
    );
    assert.equal(persisted.includes("super-secret"), false);
    assert.equal(persisted.includes("abc.def.ghi"), false);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});
