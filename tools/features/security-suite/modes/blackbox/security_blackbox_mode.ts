import type {
  SecurityAuthenticationProfile,
  SecurityAttackProfile,
  SecurityCaseCoverage,
  SecurityCoverage,
  SecurityEvidenceReference,
  SecurityFinding,
  SecurityFiniteAttackMatrix,
  SecurityPlanContract,
} from "@tools-security-execution-plan-spec";

import type {
  SecurityModeExecutionResult,
  SecurityMcpToolInvoker,
} from "../../models/security_suite.model";
import { writeSecurityRunArtifacts } from "../../persistence/security_artifact_writer";
import {
  findApplicableSecurityBlackboxRule,
  loadSecurityBlackboxKnowledgePacks,
  type SecurityBlackboxKnowledgeRule,
} from "../../support/security_blackbox_knowledge";
import { buildBlackboxHttpRequest } from "../../support/security_blackbox_request";

type BlackboxResponse = {
  status?: string;
  statusCode?: number;
  durationMs?: number;
  reasonCode?: string;
  bodyPreview?: string;
};

type CaseResult = {
  coverage: SecurityCaseCoverage;
  findings: SecurityFinding[];
  evidence: SecurityEvidenceReference[];
};

type SecurityRequestGate = {
  wait: () => Promise<void>;
};

type BlackboxSecurityPlanContract = Extract<SecurityPlanContract, { securityMode: "blackbox" }>;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function asResponse(value: unknown): BlackboxResponse {
  return isRecord(value)
    ? {
        ...(typeof value.status === "string" ? { status: value.status } : {}),
        ...(typeof value.statusCode === "number" ? { statusCode: value.statusCode } : {}),
        ...(typeof value.durationMs === "number" ? { durationMs: value.durationMs } : {}),
        ...(typeof value.reasonCode === "string" ? { reasonCode: value.reasonCode } : {}),
        ...(typeof value.bodyPreview === "string" ? { bodyPreview: value.bodyPreview } : {}),
      }
    : {};
}

function actualOutcome(response: BlackboxResponse): "allow" | "deny" | "error" | "blocked" {
  if (response.status !== "pass" && response.status !== "fail_http") return "blocked";
  const statusCode = response.statusCode ?? 0;
  if (statusCode >= 200 && statusCode < 400) return "allow";
  if (statusCode >= 400 && statusCode < 500) return "deny";
  return "error";
}

function expectationMatches(
  response: BlackboxResponse,
  expectation: SecurityAttackProfile["baseline"]["expect"],
): boolean {
  const outcome = actualOutcome(response);
  if (outcome === "blocked" || outcome !== expectation.outcome) return false;
  return !expectation.statusCodes || expectation.statusCodes.includes(response.statusCode ?? 0);
}

function makeEvidence(args: {
  caseId: string;
  phase: "baseline" | "attack";
  response: BlackboxResponse;
}): SecurityEvidenceReference {
  const status = args.response.statusCode
    ? `status=${args.response.statusCode}`
    : `status=${args.response.status ?? "unknown"}`;
  return {
    id: `${args.caseId}-${args.phase}`,
    kind: args.phase === "baseline" ? "http_response" : "http_response",
    summary: `blackbox ${args.phase} response ${status}; durationMs=${Math.max(1, Math.round(args.response.durationMs ?? 1))}`,
    redacted: true,
  };
}

function createSecurityRequestGate(maxRequestsPerSecond: number): SecurityRequestGate {
  const minimumIntervalMs = Math.max(0, Math.ceil(1000 / maxRequestsPerSecond));
  let lastRequestAt = 0;
  return {
    async wait(): Promise<void> {
      const remainingMs = minimumIntervalMs - (Date.now() - lastRequestAt);
      if (remainingMs > 0) await new Promise<void>((resolve) => setTimeout(resolve, remainingMs));
      lastRequestAt = Date.now();
    },
  };
}

function blockedCase(args: {
  attack: SecurityAttackProfile;
  reasonCode: string;
  evidence?: SecurityEvidenceReference[];
}): CaseResult {
  return {
    coverage: {
      caseId: args.attack.id,
      entrypointRef: args.attack.entrypointRef,
      authenticationProfileRef: args.attack.authenticationProfileRef,
      attackProfileRef: args.attack.id,
      outcome: "blocked",
      evidenceRefIds: (args.evidence ?? []).map((entry) => entry.id),
      findingIds: [],
      reasonCode: args.reasonCode,
    },
    findings: [],
    evidence: args.evidence ?? [],
  };
}

async function executeCase(args: {
  contract: SecurityPlanContract;
  attack: SecurityAttackProfile;
  entrypoint: SecurityPlanContract["entrypoints"][number];
  authenticationProfile: SecurityAuthenticationProfile;
  rule: SecurityBlackboxKnowledgeRule;
  mcpInvoke: SecurityMcpToolInvoker;
  requestGate: SecurityRequestGate;
}): Promise<CaseResult> {
  const evidence: SecurityEvidenceReference[] = [];
  try {
    const baselineRequest = buildBlackboxHttpRequest({
      contract: args.contract,
      entrypoint: args.entrypoint,
      attackRequest: args.attack.baseline,
      authenticationProfile: args.authenticationProfile,
    });
    await args.requestGate.wait();
    const baselineOut = await args.mcpInvoke({
      toolName: "transport_execute",
      input: { protocol: "http", request: baselineRequest, options: { wrappedOnly: true } },
    });
    const baseline = asResponse(baselineOut.structuredContent);
    evidence.push(makeEvidence({ caseId: args.attack.id, phase: "baseline", response: baseline }));
    if (!expectationMatches(baseline, args.attack.baseline.expect)) {
      return blockedCase({
        attack: args.attack,
        reasonCode: "security_blackbox_baseline_unexpected",
        evidence,
      });
    }

    const attackRequest = buildBlackboxHttpRequest({
      contract: args.contract,
      entrypoint: args.entrypoint,
      attackRequest: args.attack.attack,
      authenticationProfile: args.authenticationProfile,
    });
    await args.requestGate.wait();
    const attackOut = await args.mcpInvoke({
      toolName: "transport_execute",
      input: { protocol: "http", request: attackRequest, options: { wrappedOnly: true } },
    });
    const attackResponse = asResponse(attackOut.structuredContent);
    evidence.push(
      makeEvidence({ caseId: args.attack.id, phase: "attack", response: attackResponse }),
    );
    const attackOutcome = actualOutcome(attackResponse);
    if (attackOutcome === "blocked") {
      return blockedCase({
        attack: args.attack,
        reasonCode: attackResponse.reasonCode ?? "security_blackbox_transport_blocked",
        evidence,
      });
    }
    if (expectationMatches(attackResponse, args.attack.attack.expect)) {
      return {
        coverage: {
          caseId: args.attack.id,
          entrypointRef: args.attack.entrypointRef,
          authenticationProfileRef: args.attack.authenticationProfileRef,
          attackProfileRef: args.attack.id,
          outcome: "passed",
          evidenceRefIds: evidence.map((entry) => entry.id),
          findingIds: [],
        },
        findings: [],
        evidence,
      };
    }
    if (args.attack.attack.expect.outcome === "deny" && attackOutcome === "allow") {
      const findingId = `finding-${args.attack.id}`;
      return {
        coverage: {
          caseId: args.attack.id,
          entrypointRef: args.attack.entrypointRef,
          authenticationProfileRef: args.attack.authenticationProfileRef,
          attackProfileRef: args.attack.id,
          outcome: "confirmed",
          proofClassification: "external",
          evidenceRefIds: evidence.map((entry) => entry.id),
          findingIds: [findingId],
        },
        findings: [
          {
            id: findingId,
            severity: args.rule.severity,
            category: args.attack.category,
            title: args.rule.title,
            description: `Attack case '${args.attack.id}' returned an allowed response where denial was expected.`,
            outcome: "confirmed",
            proofClassification: "external",
            evidenceRefIds: evidence.map((entry) => entry.id),
            entrypointRef: args.attack.entrypointRef,
            attackProfileRef: args.attack.id,
          },
        ],
        evidence,
      };
    }
    return blockedCase({
      attack: args.attack,
      reasonCode: "security_blackbox_attack_unexpected",
      evidence,
    });
  } catch (error) {
    return blockedCase({
      attack: args.attack,
      reasonCode:
        error instanceof Error
          ? (error.message.split(":")[0] ?? "security_blackbox_case_failed")
          : "security_blackbox_case_failed",
      evidence,
    });
  }
}

function buildCoverage(cases: SecurityCaseCoverage[], plannedCount: number): SecurityCoverage {
  const blockedCount = cases.filter((entry) => entry.outcome === "blocked").length;
  return {
    plannedCount,
    executedCount: cases.length,
    passedCount: cases.filter((entry) => entry.outcome === "passed").length,
    confirmedCount: cases.filter((entry) => entry.outcome === "confirmed").length,
    notApplicableCount: cases.filter((entry) => entry.outcome === "not_applicable").length,
    blockedCount,
    complete: cases.length === plannedCount && blockedCount === 0,
    cases,
  };
}

function notApplicableCase(attack: SecurityAttackProfile, reasonCode: string): CaseResult {
  return {
    coverage: {
      caseId: attack.id,
      entrypointRef: attack.entrypointRef,
      authenticationProfileRef: attack.authenticationProfileRef,
      attackProfileRef: attack.id,
      outcome: "not_applicable",
      evidenceRefIds: [],
      findingIds: [],
      reasonCode,
    },
    findings: [],
    evidence: [],
  };
}

export async function executeBlackboxSecurityMode(args: {
  workspaceRootAbs?: string;
  projectName?: string;
  executionProfile?: string;
  planName: string;
  runId?: string;
  contract?: BlackboxSecurityPlanContract;
  mcpInvoke?: SecurityMcpToolInvoker;
}): Promise<SecurityModeExecutionResult> {
  const contract = args.contract;
  if (
    !contract ||
    !args.mcpInvoke ||
    !args.workspaceRootAbs ||
    !args.projectName ||
    !args.executionProfile
  ) {
    return {
      status: "blocked",
      runStatus: "blocked",
      reasonCode: "security_blackbox_input_invalid",
      requiredUserAction: [
        "Provide a validated black-box contract, project context, execution profile, and wrapped transport invoker.",
      ],
      reasonMeta: { securityMode: "blackbox", planName: args.planName },
    };
  }
  const selected = await loadSecurityBlackboxKnowledgePacks({
    packRefs: contract.securityKnowledge.packRefs,
  });
  if (!selected.ok) {
    return {
      status: "blocked",
      runStatus: "blocked",
      reasonCode: selected.reasonCode,
      requiredUserAction: [`Resolve knowledge-pack references: ${selected.refs.join(", ")}.`],
      reasonMeta: { securityMode: "blackbox", planName: args.planName },
    };
  }
  const runId = args.runId ?? `security-${Date.now()}`;
  const matrix: SecurityFiniteAttackMatrix = {
    mode: "finite_matrix",
    plannedCaseIds: contract.attackProfiles.map((attack) => attack.id),
    plannedCount: contract.attackProfiles.length,
    knowledgePackRefs: contract.securityKnowledge.packRefs,
  };
  const cases: SecurityCaseCoverage[] = [];
  const findings: SecurityFinding[] = [];
  const evidence: SecurityEvidenceReference[] = [];
  const startedAt = Date.now();
  const requestGate = createSecurityRequestGate(contract.safetyPolicy.maxRequestsPerSecond);
  for (const attack of contract.attackProfiles) {
    if (Date.now() - startedAt > contract.safetyPolicy.maxDurationMs) {
      cases.push(
        blockedCase({ attack, reasonCode: "security_blackbox_duration_exceeded" }).coverage,
      );
      continue;
    }
    const entrypoint = contract.entrypoints.find(
      (candidate) => candidate.id === attack.entrypointRef,
    );
    const authenticationProfile = contract.authenticationProfiles.find(
      (candidate) => candidate.id === attack.authenticationProfileRef,
    );
    if (!entrypoint || !authenticationProfile) {
      cases.push(
        blockedCase({ attack, reasonCode: "security_blackbox_reference_missing" }).coverage,
      );
      continue;
    }
    if (entrypoint.type !== "http") {
      cases.push(notApplicableCase(attack, "security_blackbox_entrypoint_not_supported").coverage);
      continue;
    }
    const rule = findApplicableSecurityBlackboxRule({
      packs: selected.packs,
      category: attack.category,
      entrypointType: entrypoint.type,
      authenticationKind: authenticationProfile.kind,
      fixtureContextKeys: Object.keys(contract.targetBoundary.fixtureContext ?? {}),
    });
    if (!rule) {
      cases.push(notApplicableCase(attack, "security_blackbox_knowledge_not_applicable").coverage);
      continue;
    }
    const result = await executeCase({
      contract,
      attack,
      entrypoint,
      authenticationProfile,
      rule,
      mcpInvoke: args.mcpInvoke,
      requestGate,
    });
    cases.push(result.coverage);
    findings.push(...result.findings);
    evidence.push(...result.evidence);
  }
  const coverage = buildCoverage(cases, matrix.plannedCount);
  const hasBlocked = coverage.blockedCount > 0 || !coverage.complete;
  const hasFailingFinding = findings.some((finding) =>
    contract.verdictPolicy.failOnSeverity.includes(finding.severity),
  );
  let status: "pass" | "fail" | "blocked" = "pass";
  if (hasBlocked) status = "blocked";
  else if (hasFailingFinding) status = "fail";
  try {
    const written = await writeSecurityRunArtifacts({
      workspaceRootAbs: args.workspaceRootAbs,
      projectName: args.projectName,
      planName: args.planName,
      runId,
      executionProfile: args.executionProfile,
      securityMode: "blackbox",
      status,
      matrix,
      coverage,
      findings,
      evidence,
      ...(hasBlocked ? { reasonCode: "security_blackbox_coverage_incomplete" } : {}),
    });
    return {
      status: status === "blocked" ? "blocked" : "executed",
      runStatus: status === "blocked" ? "blocked" : status,
      runId,
      ...(hasBlocked ? { reasonCode: "security_blackbox_coverage_incomplete" } : {}),
      reasonMeta: {
        securityMode: "blackbox",
        planName: args.planName,
        runDirAbs: written.runDirAbs,
      },
    };
  } catch (error) {
    return {
      status: "blocked",
      runStatus: "blocked",
      reasonCode: "security_artifact_write_failed",
      requiredUserAction: [error instanceof Error ? error.message : String(error)],
      reasonMeta: { securityMode: "blackbox", planName: args.planName },
    };
  }
}
