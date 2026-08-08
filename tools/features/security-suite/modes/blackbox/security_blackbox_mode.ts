import type {
  SecurityAuthenticationProfile,
  SecurityAttackProfile,
  SecurityCaseCoverage,
  SecurityCoverage,
  SecurityEvidenceReference,
  SecurityFinding,
  SecurityFiniteAttackMatrix,
  SecurityHttpBaselineRequest,
  SecurityKnowledgeSnapshot,
  SecurityPlanContract,
  SecurityEntrypointType,
} from "@tools-security-execution-plan-spec";

import type {
  SecurityModeExecutionResult,
  SecurityMcpToolInvoker,
} from "../../models/security_suite.model";
import { writeSecurityRunArtifacts } from "../../persistence/security_artifact_writer";
import {
  findApplicableSecurityBlackboxRules,
  findApplicableSecurityBlackboxRule,
  loadSecurityBlackboxKnowledgePacks,
  type SecurityBlackboxKnowledgePack,
  type SecurityBlackboxKnowledgeRule,
} from "../../support/security_blackbox_knowledge";
import { buildBlackboxHttpRequest } from "../../support/security_blackbox_request";
import { readSecurityRunArtifact } from "../../persistence/security_artifact_reader";
import {
  buildCatalogAttackRequest,
  mutationRequiresAnonymousAuthentication,
} from "../../support/security_blackbox_mutation";

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

function entrypointType(entrypoint: BlackboxSecurityPlanContract["entrypoints"][number]): string {
  return "transport" in entrypoint ? entrypoint.transport.type : entrypoint.type;
}

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
  attackAuthenticationProfile?: SecurityAuthenticationProfile;
  runtimeCredentialContext?: Record<string, string>;
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
      ...(args.runtimeCredentialContext
        ? { runtimeCredentialContext: args.runtimeCredentialContext }
        : {}),
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
      authenticationProfile: args.attackAuthenticationProfile ?? args.authenticationProfile,
      ...(args.runtimeCredentialContext
        ? { runtimeCredentialContext: args.runtimeCredentialContext }
        : {}),
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

function fixtureKeysInTemplate(template: string): string[] {
  return [...template.matchAll(/\$\{fixture\.([A-Za-z][A-Za-z0-9_.-]*)\}/g)].map(
    (match) => match[1]!,
  );
}

function fixtureKeysInValue(value: unknown): string[] {
  if (typeof value === "string") return fixtureKeysInTemplate(value);
  if (Array.isArray(value)) return value.flatMap(fixtureKeysInValue);
  if (isRecord(value)) return Object.values(value).flatMap(fixtureKeysInValue);
  return [];
}

function generatedAttackCase(args: {
  entrypoint: BlackboxSecurityPlanContract["entrypoints"][number];
  authenticationProfile: SecurityAuthenticationProfile;
  rule: SecurityBlackboxKnowledgeRule;
  template: SecurityBlackboxKnowledgeRule["caseTemplates"][number];
  baseline: SecurityHttpBaselineRequest;
  payloadTemplate: string;
  payloadIndex: number;
  payloadCount: number;
}): SecurityAttackProfile {
  const baseline: SecurityAttackProfile["baseline"] = {
    ...(args.baseline.pathParameters ? { pathParameters: args.baseline.pathParameters } : {}),
    ...(args.baseline.query ? { query: args.baseline.query } : {}),
    ...(args.baseline.headers ? { headers: args.baseline.headers } : {}),
    ...(args.baseline.body !== undefined ? { body: args.baseline.body } : {}),
    expect: {
      outcome: args.template.baseline.expectedOutcome,
      statusCodes: args.template.baseline.statusCodes,
    },
  };
  const attack = buildCatalogAttackRequest({
    baseline: args.baseline,
    mutation: args.template.attack,
    payloadTemplate: args.payloadTemplate,
  });
  const category = args.rule.categories[0] === "*" ? "other" : args.rule.categories[0]!;
  const payloadSuffix = args.payloadCount > 1 ? `-${args.payloadIndex + 1}` : "";
  const id = `${args.rule.id}-${args.template.id}-${args.entrypoint.id}-${args.authenticationProfile.id}${payloadSuffix}`;
  return {
    id,
    category,
    entrypointRef: args.entrypoint.id,
    authenticationProfileRef: args.authenticationProfile.id,
    baseline,
    attack,
  };
}

function buildKnowledgeSnapshot(
  packs: SecurityBlackboxKnowledgePack[],
  selection: SecurityKnowledgeSnapshot["selection"],
): SecurityKnowledgeSnapshot {
  return {
    selection,
    packs: packs.map((pack) => ({
      id: pack.id,
      version: pack.version,
      ref: pack.ref,
      compatibility: pack.compatibility,
      contentDigest: pack.contentDigest,
    })),
  };
}

function missingRuleFixtureKeys(args: {
  rule: SecurityBlackboxKnowledgeRule;
  template: SecurityBlackboxKnowledgeRule["caseTemplates"][number];
  fixtureKeys: Set<string>;
  baseline?: SecurityHttpBaselineRequest;
}): string[] {
  const required = new Set([
    ...args.rule.applicability.requiredFixtureContextKeys,
    ...args.template.attack.payloadTemplates.flatMap(fixtureKeysInTemplate),
    ...fixtureKeysInValue(args.baseline),
  ]);
  return [...required].filter((key) => !args.fixtureKeys.has(key)).sort();
}

export async function executeBlackboxSecurityMode(args: {
  workspaceRootAbs?: string;
  projectName?: string;
  executionProfile?: string;
  planName: string;
  runId?: string;
  contract?: BlackboxSecurityPlanContract;
  mcpInvoke?: SecurityMcpToolInvoker;
  runtimeCredentialContext?: Record<string, string>;
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
  const runId = args.runId ?? `security-${Date.now()}`;
  let requestedPackRefs = contract.securityKnowledge?.packRefs;
  let snapshotSelection: SecurityKnowledgeSnapshot["selection"] = requestedPackRefs
    ? "explicit_override"
    : "catalog_default";
  const prior = await readSecurityRunArtifact({
    workspaceRootAbs: args.workspaceRootAbs,
    projectName: args.projectName,
    planName: args.planName,
    runId,
  }).catch(() => undefined);
  if (prior?.ok && prior.artifact.matrix.knowledgeSnapshot) {
    snapshotSelection = prior.artifact.matrix.knowledgeSnapshot.selection;
    requestedPackRefs = prior.artifact.matrix.knowledgeSnapshot.packs.map((pack) => pack.ref);
  } else if (prior?.ok && prior.artifact.matrix.knowledgePackRefs) {
    requestedPackRefs = prior.artifact.matrix.knowledgePackRefs;
  }
  const selected = await loadSecurityBlackboxKnowledgePacks({
    ...(requestedPackRefs ? { packRefs: requestedPackRefs } : {}),
  });
  if (!selected.ok) {
    return {
      status: "blocked",
      runStatus: "blocked",
      reasonCode: selected.reasonCode,
      requiredUserAction:
        selected.refs.length > 0
          ? [`Resolve knowledge-pack references: ${selected.refs.join(", ")}.`]
          : (selected.errors ?? ["Restore the reviewed local security knowledge-pack catalog."]),
      reasonMeta: { securityMode: "blackbox", planName: args.planName },
    };
  }
  if (prior?.ok && prior.artifact.matrix.knowledgeSnapshot) {
    const expected = prior.artifact.matrix.knowledgeSnapshot.packs;
    const mismatch = expected.some((snapshot) => {
      const pack = selected.packs.find((candidate) => candidate.ref === snapshot.ref);
      return (
        !pack ||
        pack.id !== snapshot.id ||
        pack.version !== snapshot.version ||
        pack.compatibility.contractVersionRange !== snapshot.compatibility.contractVersionRange ||
        (snapshot.contentDigest !== undefined && pack.contentDigest !== snapshot.contentDigest)
      );
    });
    if (mismatch) {
      return {
        status: "blocked",
        runStatus: "blocked",
        reasonCode: "security_knowledge_snapshot_mismatch",
        requiredUserAction: [
          "Restore the exact knowledge-pack catalog content used by the prior run before resuming.",
        ],
        reasonMeta: { securityMode: "blackbox", planName: args.planName },
      };
    }
  }
  const snapshot = buildKnowledgeSnapshot(selected.packs, snapshotSelection);
  const customCaseOverride = contract.customCases !== undefined;
  const customCases = contract.customCases ?? [];
  const generatedAttacks: Array<{
    attack: SecurityAttackProfile;
    rule: SecurityBlackboxKnowledgeRule;
    attackAuthenticationProfile?: SecurityAuthenticationProfile;
  }> = [];
  const precomputedCases: SecurityCaseCoverage[] = [];
  const fixtureKeys = new Set(Object.keys(contract.targetBoundary.fixtureContext ?? {}));
  for (const entrypoint of contract.entrypoints) {
    const details =
      "details" in entrypoint && isRecord(entrypoint.details) ? entrypoint.details : {};
    const entrypointFixtures = isRecord(details.fixtureContext) ? details.fixtureContext : {};
    for (const key of Object.keys(entrypointFixtures)) fixtureKeys.add(key);
  }
  if (!customCaseOverride) {
    for (const entrypoint of contract.entrypoints) {
      for (const authenticationProfile of contract.authenticationProfiles) {
        if (entrypointType(entrypoint) !== "http") {
          const unsupported: SecurityAttackProfile = {
            id: `unsupported-transport-${entrypoint.id}-${authenticationProfile.id}`,
            category: "other",
            entrypointRef: entrypoint.id,
            authenticationProfileRef: authenticationProfile.id,
            baseline: { expect: { outcome: "allow" } },
            attack: { expect: { outcome: "deny" } },
          };
          precomputedCases.push(
            blockedCase({
              attack: unsupported,
              reasonCode: "security_blackbox_unsupported_transport",
            }).coverage,
          );
          continue;
        }
        const entrypointRules = selected.packs
          .flatMap((pack) => pack.rules)
          .filter((rule) =>
            rule.entrypointTypes.includes(entrypointType(entrypoint) as SecurityEntrypointType),
          );
        const rules = findApplicableSecurityBlackboxRules({
          packs: selected.packs,
          entrypointType: entrypointType(entrypoint) as SecurityEntrypointType,
          authenticationKind: authenticationProfile.kind,
        });
        if (entrypointRules.length === 0) {
          const unsupported: SecurityAttackProfile = {
            id: `not-applicable-${entrypoint.id}-${authenticationProfile.id}`,
            category: "other",
            entrypointRef: entrypoint.id,
            authenticationProfileRef: authenticationProfile.id,
            baseline: { expect: { outcome: "allow" } },
            attack: { expect: { outcome: "deny" } },
          };
          precomputedCases.push(
            notApplicableCase(unsupported, "security_rule_not_applicable").coverage,
          );
          continue;
        }
        for (const rule of entrypointRules) {
          for (const template of rule.caseTemplates) {
            const payloadTemplates = template.attack.payloadTemplates;
            for (const [payloadIndex, payloadTemplate] of payloadTemplates.entries()) {
              const baseline = entrypoint.baseline;
              const attackId = `${rule.id}-${template.id}-${entrypoint.id}-${authenticationProfile.id}${payloadTemplates.length > 1 ? `-${payloadIndex + 1}` : ""}`;
              if (!rules.includes(rule)) {
                precomputedCases.push(
                  notApplicableCase(
                    {
                      id: attackId,
                      category: rule.categories[0] === "*" ? "other" : rule.categories[0]!,
                      entrypointRef: entrypoint.id,
                      authenticationProfileRef: authenticationProfile.id,
                      baseline: { expect: { outcome: "allow" } },
                      attack: { expect: { outcome: "deny" } },
                    },
                    rule.reasonCodes.notApplicable,
                  ).coverage,
                );
                continue;
              }
              let attack: SecurityAttackProfile;
              try {
                attack = baseline
                  ? generatedAttackCase({
                      entrypoint,
                      authenticationProfile,
                      rule,
                      template,
                      baseline,
                      payloadTemplate,
                      payloadIndex,
                      payloadCount: payloadTemplates.length,
                    })
                  : {
                      id: attackId,
                      category: rule.categories[0] === "*" ? "other" : rule.categories[0]!,
                      entrypointRef: entrypoint.id,
                      authenticationProfileRef: authenticationProfile.id,
                      baseline: { expect: { outcome: "allow" } },
                      attack: { expect: { outcome: "deny" } },
                    };
              } catch (error) {
                const reasonCode =
                  error instanceof Error
                    ? (error.message.split(":")[0] ?? "security_blackbox_mutation_failed")
                    : "security_blackbox_mutation_failed";
                if (reasonCode === "security_blackbox_mutation_target_missing") {
                  precomputedCases.push(
                    notApplicableCase(
                      {
                        id: attackId,
                        category: rule.categories[0] === "*" ? "other" : rule.categories[0]!,
                        entrypointRef: entrypoint.id,
                        authenticationProfileRef: authenticationProfile.id,
                        baseline: { expect: { outcome: "allow" } },
                        attack: { expect: { outcome: "deny" } },
                      },
                      rule.reasonCodes.notApplicable,
                    ).coverage,
                  );
                  continue;
                }
                precomputedCases.push(
                  blockedCase({
                    attack: {
                      id: attackId,
                      category: rule.categories[0] === "*" ? "other" : rule.categories[0]!,
                      entrypointRef: entrypoint.id,
                      authenticationProfileRef: authenticationProfile.id,
                      baseline: { expect: { outcome: "allow" } },
                      attack: { expect: { outcome: "deny" } },
                    },
                    reasonCode,
                  }).coverage,
                );
                continue;
              }
              if (!baseline) {
                precomputedCases.push(
                  blockedCase({
                    attack,
                    reasonCode: "security_blackbox_baseline_missing",
                  }).coverage,
                );
                continue;
              }
              const missing = missingRuleFixtureKeys({ rule, template, fixtureKeys, baseline });
              if (missing.length > 0) {
                precomputedCases.push(
                  blockedCase({
                    attack,
                    reasonCode: "security_blackbox_fixture_unresolved",
                  }).coverage,
                );
                continue;
              }
              generatedAttacks.push({
                attack,
                rule,
                ...(mutationRequiresAnonymousAuthentication(template.attack.mutation)
                  ? {
                      attackAuthenticationProfile: {
                        id: authenticationProfile.id,
                        kind: "anonymous",
                        ...(authenticationProfile.role ? { role: authenticationProfile.role } : {}),
                      },
                    }
                  : {}),
              });
            }
          }
        }
      }
    }
  }
  for (const attack of customCases) {
    const entrypoint = contract.entrypoints.find(
      (candidate) => candidate.id === attack.entrypointRef,
    );
    const authenticationProfile = contract.authenticationProfiles.find(
      (candidate) => candidate.id === attack.authenticationProfileRef,
    );
    if (!entrypoint || !authenticationProfile) {
      precomputedCases.push(
        blockedCase({ attack, reasonCode: "security_blackbox_reference_missing" }).coverage,
      );
      continue;
    }
    if (entrypointType(entrypoint) !== "http") {
      precomputedCases.push(
        blockedCase({
          attack,
          reasonCode: "security_blackbox_unsupported_transport",
        }).coverage,
      );
      continue;
    }
    const rule = findApplicableSecurityBlackboxRule({
      packs: selected.packs,
      category: attack.category,
      entrypointType: entrypointType(entrypoint) as SecurityEntrypointType,
      authenticationKind: authenticationProfile.kind,
      fixtureContextKeys: [...fixtureKeys],
    });
    if (rule) generatedAttacks.push({ attack, rule });
    else precomputedCases.push(notApplicableCase(attack, "security_rule_not_applicable").coverage);
  }
  const matrix: SecurityFiniteAttackMatrix = {
    mode: "finite_matrix",
    plannedCaseIds: [
      ...precomputedCases.map((entry) => entry.caseId),
      ...generatedAttacks.map((entry) => entry.attack.id),
    ],
    plannedCount: precomputedCases.length + generatedAttacks.length,
    knowledgePackRefs: selected.packs.map((pack) => pack.ref),
    knowledgeSnapshot: snapshot,
  };
  const cases: SecurityCaseCoverage[] = [...precomputedCases];
  const findings: SecurityFinding[] = [];
  const evidence: SecurityEvidenceReference[] = [];
  const startedAt = Date.now();
  const requestGate = createSecurityRequestGate(contract.safetyPolicy.maxRequestsPerSecond);
  for (const generated of generatedAttacks) {
    const attack = generated.attack;
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
    if (entrypointType(entrypoint) !== "http") {
      cases.push(notApplicableCase(attack, "security_blackbox_entrypoint_not_supported").coverage);
      continue;
    }
    const result = await executeCase({
      contract,
      attack,
      entrypoint,
      authenticationProfile,
      ...(generated.attackAuthenticationProfile
        ? { attackAuthenticationProfile: generated.attackAuthenticationProfile }
        : {}),
      rule: generated.rule,
      mcpInvoke: args.mcpInvoke,
      requestGate,
      ...(args.runtimeCredentialContext
        ? { runtimeCredentialContext: args.runtimeCredentialContext }
        : {}),
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
