import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import {
  dispatchSecuritySuiteAction,
  executeSecurityRuntimeSuite,
  loadSecurityBlackboxKnowledgePacks,
  readSecurityRunArtifact,
  writeSecurityRunArtifacts,
} from "@tools-feature-security-suite";
import { dispatchArtifactManagementAction } from "@tools-feature-artifact-management";
import {
  validateSecurityPlanContract,
  type SecurityPlanContract,
} from "@tools-security-execution-plan-spec";

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

function blackboxContract(): SecurityPlanContract {
  const contract = securityContract();
  contract.targetBoundary = {
    ...contract.targetBoundary,
    baseUrl: "http://127.0.0.1:8080",
  };
  contract.securityKnowledge = { packRefs: ["authorization-idor@1.0.0"] };
  contract.verdictPolicy = {
    ...contract.verdictPolicy,
    failOnSeverity: ["high"],
  };
  contract.entrypoints = [
    { id: "read-order", type: "http", method: "GET", path: "/orders/{orderId}" },
  ];
  contract.attackProfiles = [
    {
      ...contract.attackProfiles[0]!,
      id: "cross-tenant-order-access",
      category: "authorization",
      entrypointRef: "read-order",
      baseline: {
        pathParameters: { orderId: "own-order" },
        expect: { outcome: "allow", statusCodes: [200] },
      },
      attack: {
        pathParameters: { orderId: "foreign-order" },
        expect: { outcome: "deny", statusCodes: [403, 404] },
      },
    },
  ];
  return contract;
}

test("[UT][security-contract] rejects Sidecar-only runtime fields in Black-box contracts", () => {
  for (const field of [
    "mustHitRuntimeTargets",
    "mustNotHitRuntimeTargets",
    "instrumentationTargets",
  ]) {
    const contract = JSON.parse(JSON.stringify(securityContract())) as Record<string, unknown>;
    if (field === "instrumentationTargets") {
      contract.instrumentationTargets = [
        {
          id: "sidecar-target",
          scope: "dependency",
          classFqcn: "org.example.Policy",
          dependencyRef: "policy-lib",
        },
      ];
    } else {
      const attacks = contract.attackProfiles as Array<Record<string, unknown>>;
      const baseline = attacks[0]?.baseline as Record<string, unknown>;
      const expect = baseline.expect as Record<string, unknown>;
      expect[field] = ["sidecar-target"];
    }
    const result = validateSecurityPlanContract(contract);
    assert.equal(result.ok, false);
    assert.equal(result.reasonCode, "security_contract_blackbox_forbidden_field");
    assert.match(result.errors[0] ?? "", new RegExp(field));
  }
});

function writeSecurityPlanFixture(args: {
  root: string;
  contract: SecurityPlanContract;
  planName?: string;
  projectName?: string;
}): void {
  const projectName = args.projectName ?? "demo";
  const planName = args.planName ?? "authorization";
  const projectRoot = path.join(args.root, ".mcpjvm", projectName);
  const planRoot = path.join(projectRoot, "plans", "security", planName);
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
              plans: [{ order: 1, planName }],
            },
          ],
        },
      ],
    }),
    "utf8",
  );
}

async function executeBlackboxFixture(args: {
  root: string;
  contract: SecurityPlanContract;
  attackStatusCode: number;
  calls?: Array<Record<string, unknown>>;
}): Promise<Awaited<ReturnType<typeof executeSecurityRuntimeSuite>>> {
  writeSecurityPlanFixture({ root: args.root, contract: args.contract });
  return executeSecurityRuntimeSuite({
    workspaceRootAbs: args.root,
    projectName: "demo",
    executionProfile: "security-ci",
    mcpInvoke: async ({ toolName, input }) => {
      assert.equal(toolName, "transport_execute");
      assert.deepEqual(input.options, { wrappedOnly: true });
      args.calls?.push(input);
      const request = input.request;
      const url =
        typeof request === "object" && request !== null && "url" in request
          ? String((request as Record<string, unknown>).url)
          : "";
      return {
        structuredContent: {
          status: "pass",
          durationMs: 1,
          statusCode: url.includes("foreign-order") ? args.attackStatusCode : 200,
        },
      };
    },
  });
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

test("[UT][security-blackbox] loads file-backed packs and enforces manifest compatibility ranges", async () => {
  const loaded = await loadSecurityBlackboxKnowledgePacks({
    packRefs: ["authorization-idor@1.0.0"],
    contractVersion: "1.4.0",
  });
  assert.ok(loaded.ok);
  assert.equal(loaded.packs[0]?.rules[0]?.id, "authorization-idor-cross-tenant");

  const incompatible = await loadSecurityBlackboxKnowledgePacks({
    packRefs: ["authorization-idor@1.0.0"],
    contractVersion: "2.0.0",
  });
  assert.ok(!incompatible.ok);
  assert.equal(incompatible.reasonCode, "security_knowledge_pack_incompatible");
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
      fs.existsSync(
        path.join(root, ".mcpjvm", "demo", "plans", "security", "authorization", "contract.json"),
      ),
      false,
    );
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("[UT][security-plan-artifact] validates selected dependency instrumentation against Probe include/exclude rules", async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "mcp-jvm-security-sidecar-craft-"));
  try {
    fs.mkdirSync(path.join(root, ".mcpjvm"), { recursive: true });
    fs.writeFileSync(
      path.join(root, ".mcpjvm", "probe-config.json"),
      JSON.stringify({
        defaultProfile: "security",
        profiles: {
          security: {
            probes: {
              orders: {
                baseUrl: "http://127.0.0.1:9193",
                include: ["com.example.**", "org.acme.policy.**"],
                exclude: ["org.acme.policy.internal.**"],
              },
            },
          },
        },
      }),
      "utf8",
    );
    const contract = {
      ...securityContract(),
      securityMode: "sidecar_assisted" as const,
      runtimeTargets: [
        {
          id: "policy-check",
          entrypointRef: "health",
          probeId: "orders",
          strictLineKey: "org.acme.policy.PolicyService#check:42",
          purpose: "sensitive-sink" as const,
          instrumentationTargetRef: "policy-dependency",
        },
      ],
      instrumentationTargets: [
        {
          id: "policy-dependency",
          scope: "dependency" as const,
          classFqcn: "org.acme.policy.PolicyService",
          dependencyRef: "acme-policy",
        },
      ],
    } as Extract<SecurityPlanContract, { securityMode: "sidecar_assisted" }>;
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
    assert.equal(result.structuredContent.status, "ok");

    const rejected = {
      ...contract,
      instrumentationTargets: [
        {
          id: "policy-dependency",
          scope: "dependency" as const,
          classFqcn: "org.acme.policy.internal.PolicyService",
          dependencyRef: "acme-policy",
        },
      ],
      runtimeTargets: [
        {
          ...contract.runtimeTargets[0]!,
          strictLineKey: "org.acme.policy.internal.PolicyService#check:42",
        },
      ],
    } as Extract<SecurityPlanContract, { securityMode: "sidecar_assisted" }>;
    const rejectedResult = await dispatchArtifactManagementAction({
      workspaceRootAbs: root,
      request: {
        artifactType: "security_plan",
        action: "upsert",
        input: {
          projectName: "demo",
          planName: "authorization-rejected",
          payload: { contract: rejected },
        },
      },
    });
    assert.equal(
      rejectedResult.structuredContent.reasonCode,
      "security_sidecar_instrumentation_excluded",
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
      fs.writeFileSync(
        path.join(planRoot, "contract.json"),
        JSON.stringify(securityContract()),
        "utf8",
      );
    }
    fs.writeFileSync(
      path.join(projectRoot, "projects.json"),
      JSON.stringify({
        workspaces: [
          {
            projectRoot: root,
            defaults: {
              orchestrator: {
                resumePollMax: 1,
                resumePollIntervalMs: 1,
                resumePollTimeoutMs: 1000,
              },
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
      path.join(
        root,
        ".mcpjvm",
        "demo",
        "plans",
        "security",
        "authorization",
        "runs",
        "run-1",
        "execution.result.json",
      ),
      "utf8",
    );
    assert.equal(persisted.includes("super-secret"), false);
    assert.equal(persisted.includes("abc.def.ghi"), false);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("[UT][security-blackbox] executes the finite baseline and attack case through wrapped transport", async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "mcp-jvm-security-blackbox-pass-"));
  try {
    const calls: Array<Record<string, unknown>> = [];
    const result = await executeBlackboxFixture({
      root,
      contract: blackboxContract(),
      attackStatusCode: 403,
      calls,
    });
    assert.equal(result.status, "pass");
    const planRun = result.planRuns[0];
    assert.ok(planRun);
    assert.equal(planRun.status, "executed");
    assert.equal(planRun.runStatus, "pass");
    assert.equal(calls.length, 2);
    const artifact = await readSecurityRunArtifact({
      workspaceRootAbs: root,
      projectName: "demo",
      planName: "authorization",
      runId: planRun.runId ?? "",
    });
    assert.ok(artifact.ok);
    assert.deepEqual(artifact.artifact.matrix.plannedCaseIds, ["cross-tenant-order-access"]);
    assert.equal(artifact.artifact.coverage.passedCount, 1);
    assert.equal(artifact.artifact.coverage.complete, true);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("[UT][security-blackbox] classifies an allowed foreign response as an external confirmed finding", async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "mcp-jvm-security-blackbox-finding-"));
  try {
    const result = await executeBlackboxFixture({
      root,
      contract: blackboxContract(),
      attackStatusCode: 200,
    });
    assert.equal(result.status, "fail");
    const artifact = await readSecurityRunArtifact({
      workspaceRootAbs: root,
      projectName: "demo",
      planName: "authorization",
      runId: result.planRuns[0]?.runId ?? "",
    });
    assert.ok(artifact.ok);
    assert.equal(artifact.artifact.coverage.confirmedCount, 1);
    const finding = artifact.artifact.findings[0];
    assert.ok(finding);
    assert.equal(finding.proofClassification, "external");
    assert.equal(finding.evidenceRefIds.length, 2);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("[UT][security-blackbox] isolates symbolic credentials per case and never persists the resolved value", async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "mcp-jvm-security-blackbox-auth-"));
  const envName = "SECURITY_LIMITEDUSERTOKEN";
  const previous = process.env[envName];
  process.env[envName] = "resolved-token-value";
  try {
    const contract = blackboxContract();
    contract.authenticationProfiles = [
      {
        id: "limited-user",
        kind: "bearer",
        role: "customer",
        credentialRef: "security.limitedUserToken",
      },
    ];
    contract.attackProfiles[0]!.authenticationProfileRef = "limited-user";
    const calls: Array<Record<string, unknown>> = [];
    const result = await executeBlackboxFixture({ root, contract, attackStatusCode: 403, calls });
    assert.equal(result.status, "pass");
    for (const call of calls) {
      const headers = (call.request as Record<string, unknown>).headers as Record<string, unknown>;
      assert.equal(headers.Authorization, "Bearer resolved-token-value");
    }
    const artifact = await readSecurityRunArtifact({
      workspaceRootAbs: root,
      projectName: "demo",
      planName: "authorization",
      runId: result.planRuns[0]?.runId ?? "",
    });
    assert.ok(artifact.ok);
    const executionResult = fs.readFileSync(
      path.join(
        root,
        ".mcpjvm",
        "demo",
        "plans",
        "security",
        "authorization",
        "runs",
        result.planRuns[0]?.runId ?? "",
        "execution.result.json",
      ),
      "utf8",
    );
    assert.equal(executionResult.includes("resolved-token-value"), false);
  } finally {
    if (typeof previous === "string") process.env[envName] = previous;
    else delete process.env[envName];
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("[UT][security-blackbox] blocks incomplete execution when a credential reference cannot be resolved", async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "mcp-jvm-security-blackbox-blocked-"));
  const envName = "SECURITY_LIMITEDUSERTOKEN";
  const previous = process.env[envName];
  delete process.env[envName];
  try {
    const contract = blackboxContract();
    contract.authenticationProfiles = [
      {
        id: "limited-user",
        kind: "bearer",
        credentialRef: "security.limitedUserToken",
      },
    ];
    contract.attackProfiles[0]!.authenticationProfileRef = "limited-user";
    const result = await executeBlackboxFixture({ root, contract, attackStatusCode: 403 });
    assert.equal(result.status, "blocked");
    assert.equal(result.reasonCode, "security_blackbox_coverage_incomplete");
    assert.equal(result.planRuns[0]?.runStatus, "blocked");
  } finally {
    if (typeof previous === "string") process.env[envName] = previous;
    else delete process.env[envName];
    fs.rmSync(root, { recursive: true, force: true });
  }
});
