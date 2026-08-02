import assert from "node:assert/strict";
import test from "node:test";

import {
  validateSecurityPlanContract,
  type SecurityPlanContract,
} from "@tools-security-execution-plan-spec";
import { validateProjectArtifact } from "@tools-project-artifact-spec/project_artifact.util";

function baseContract(): SecurityPlanContract {
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
    entrypoints: [{ id: "read-order", type: "http", method: "GET", path: "/orders/{id}" }],
    authenticationProfiles: [{ id: "anonymous", kind: "anonymous", role: "anonymous" }],
    attackProfiles: [
      {
        id: "authorization-boundary",
        category: "authorization",
        entrypointRef: "read-order",
        authenticationProfileRef: "anonymous",
        baseline: {
          pathParameters: { id: "own" },
          expect: { outcome: "allow", statusCodes: [200] },
        },
        attack: {
          pathParameters: { id: "foreign" },
          expect: { outcome: "deny", statusCodes: [403, 404] },
        },
      },
    ],
    exhaustiveness: { mode: "finite_matrix", requireAllCases: true, onIncomplete: "blocked" },
    safetyPolicy: {
      maxConcurrency: 1,
      maxRequestsPerSecond: 5,
      maxDurationMs: 30_000,
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

test("[UT][security-artifact-spec] validates the shared blackbox contract", () => {
  const result = validateSecurityPlanContract(baseContract());
  assert.equal(result.ok, true);
});

test("[UT][security-artifact-spec] rejects runtime targets in blackbox mode", () => {
  const result = validateSecurityPlanContract({
    ...baseContract(),
    runtimeTargets: [
      {
        id: "entry",
        entrypointRef: "read-order",
        probeId: "orders",
        strictLineKey: "com.example.OrderController#get:10",
        purpose: "business-entrypoint",
      },
    ],
  });
  assert.equal(result.ok, false);
  assert.equal(result.reasonCode, "security_contract_blackbox_forbidden_field");
});

test("[UT][security-artifact-spec] requires sidecar runtime targets", () => {
  const result = validateSecurityPlanContract({ ...baseContract(), securityMode: "sidecar_assisted" });
  assert.equal(result.ok, false);
  assert.equal(result.reasonCode, "security_contract_sidecar_runtime_target_required");
});

test("[UT][security-artifact-spec] accepts valid sidecar runtime targets", () => {
  const result = validateSecurityPlanContract({
    ...baseContract(),
    securityMode: "sidecar_assisted",
    runtimeTargets: [
      {
        id: "entry",
        entrypointRef: "read-order",
        probeId: "orders",
        strictLineKey: "com.example.OrderController#get:10",
        purpose: "business-entrypoint",
      },
    ],
  });
  assert.equal(result.ok, true);
});

test("[UT][security-artifact-spec] rejects unresolved attack references", () => {
  const result = validateSecurityPlanContract({
    ...baseContract(),
    attackProfiles: [{ ...baseContract().attackProfiles[0], entrypointRef: "missing" }],
  });
  assert.equal(result.ok, false);
  assert.equal(result.reasonCode, "security_contract_reference_invalid");
});

test("[UT][security-artifact-spec] rejects resolved credentials and secret headers", () => {
  const credentialResult = validateSecurityPlanContract({
    ...baseContract(),
    authenticationProfiles: [
      { id: "anonymous", kind: "bearer", credentialRef: "Bearer resolved-token" },
    ],
  });
  assert.equal(credentialResult.ok, false);
  assert.equal(credentialResult.reasonCode, "security_contract_secret_persisted");

  const headerResult = validateSecurityPlanContract({
    ...baseContract(),
    attackProfiles: [
      {
        ...baseContract().attackProfiles[0]!,
        attack: {
          ...baseContract().attackProfiles[0]!.attack,
          headers: { Authorization: "Bearer resolved-token" },
        },
      },
    ],
  });
  assert.equal(headerResult.ok, false);
  assert.equal(headerResult.reasonCode, "security_contract_secret_persisted");
});

test("[UT][security-artifact-spec] allows symbolic credential and header references", () => {
  const result = validateSecurityPlanContract({
    ...baseContract(),
    authenticationProfiles: [
      { id: "bearer", kind: "bearer", credentialRef: "security.limitedUserToken" },
    ],
    attackProfiles: [
      {
        ...baseContract().attackProfiles[0]!,
        authenticationProfileRef: "bearer",
        baseline: {
          ...baseContract().attackProfiles[0]!.baseline,
          headers: { Authorization: "Bearer ${security.limitedUserToken}" },
        },
      },
    ],
  });
  assert.equal(result.ok, true);
});

test("[UT][security-artifact-spec] rejects nested passwords and raw environment values", () => {
  const result = validateSecurityPlanContract({
    ...baseContract(),
    attackProfiles: [
      {
        ...baseContract().attackProfiles[0]!,
        attack: {
          ...baseContract().attackProfiles[0]!.attack,
          body: { nested: { password: "resolved-password" }, environment: { API_TOKEN: "resolved-token" } },
        },
      },
    ],
  });
  assert.equal(result.ok, false);
  assert.equal(result.reasonCode, "security_contract_secret_persisted");
});

test("[UT][security-artifact-spec] rejects nested blackbox runtime evidence", () => {
  const result = validateSecurityPlanContract({
    ...baseContract(),
    entrypoints: [
      {
        ...baseContract().entrypoints[0],
        details: { probeId: "orders" },
      },
    ],
  });
  assert.equal(result.ok, false);
  assert.equal(result.reasonCode, "security_contract_blackbox_forbidden_field");
});

test("[UT][security-artifact-spec] rejects blackbox internal_runtime entrypoints", () => {
  const result = validateSecurityPlanContract({
    ...baseContract(),
    entrypoints: [{ ...baseContract().entrypoints[0], type: "internal_runtime" }],
  });
  assert.equal(result.ok, false);
  assert.equal(result.reasonCode, "security_contract_blackbox_forbidden_field");
});

test("[UT][project-artifact-spec] accepts security execution profiles", () => {
  const result = validateProjectArtifact({
    workspaces: [
      {
        projectRoot: "C:/workspace",
        defaults: {
          orchestrator: {
            resumePollMax: 1,
            resumePollIntervalMs: 10,
            resumePollTimeoutMs: 1000,
          },
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
  });
  assert.equal(result.ok, true);
});
