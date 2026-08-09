import type {
  SecurityAttackProfile,
  SecurityCaseCoverage,
  SecurityCoverage,
  SecurityEvidenceReference,
  SecurityFinding,
  SecurityFiniteAttackMatrix,
  SecurityInstrumentationTarget,
  SecurityPlanContract,
  SecurityRequestExpectation,
  SecurityRuntimeTarget,
  SecurityEntrypointType,
} from "@tools-security-execution-plan-spec";

import type {
  SecurityMcpToolInvoker,
  SecurityModeExecutionResult,
} from "../../models/security_suite.model";
import { writeSecurityRunArtifacts } from "../../persistence/security_artifact_writer";
import {
  findApplicableSecurityBlackboxRule,
  loadSecurityBlackboxKnowledgePacks,
  type SecurityBlackboxKnowledgePack,
  type SecurityBlackboxKnowledgeRule,
} from "../../support/security_blackbox_knowledge";
import { buildBlackboxHttpRequest } from "../../support/security_blackbox_request";
import { validateSidecarInstrumentationSelection } from "../../support/validate_sidecar_instrumentation_selection";

type SidecarSecurityPlanContract = Extract<
  SecurityPlanContract,
  { securityMode: "sidecar_assisted" }
>;

function entrypointType(
  entrypoint: SidecarSecurityPlanContract["entrypoints"][number],
): SecurityEntrypointType {
  return "transport" in entrypoint ? entrypoint.transport.type : entrypoint.type;
}

type ProbeSnapshot = {
  hitCount: number;
  lastHitEpoch: number;
  lineResolvable?: boolean;
  lineValidation?: string;
  runtimeInstanceId?: string;
  reasonCode?: string;
};

type RuntimePhaseEvaluation = {
  requiredHitIds: string[];
  forbiddenHitIds: string[];
  evidence: SecurityEvidenceReference[];
};

type SidecarCaseResult = {
  coverage: SecurityCaseCoverage;
  findings: SecurityFinding[];
  evidence: SecurityEvidenceReference[];
};

class SidecarBlockedError extends Error {
  constructor(
    readonly reasonCode: string,
    message: string,
  ) {
    super(message);
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function asProbeJson(output: {
  structuredContent: Record<string, unknown>;
}): Record<string, unknown> {
  const structured = output.structuredContent;
  const response = isRecord(structured.response) ? structured.response : undefined;
  return isRecord(response?.json) ? response.json : {};
}

function asProbeResult(output: {
  structuredContent: Record<string, unknown>;
}): Record<string, unknown> {
  return isRecord(output.structuredContent.result) ? output.structuredContent.result : {};
}

function asProbeSnapshot(output: { structuredContent: Record<string, unknown> }): ProbeSnapshot {
  const json = asProbeJson(output);
  const result = asProbeResult(output);
  const lastStatus = isRecord(result.lastStatus) ? result.lastStatus : {};
  const status = { ...json, ...lastStatus };
  const runtime = isRecord(status.runtime) ? status.runtime : {};
  const snapshot: ProbeSnapshot = {
    hitCount: typeof status.hitCount === "number" ? status.hitCount : 0,
    lastHitEpoch: typeof status.lastHitEpoch === "number" ? status.lastHitEpoch : 0,
  };
  if (typeof status.hitCount !== "number" && typeof result.hitCount === "number") {
    snapshot.hitCount = result.hitCount;
  }
  if (typeof status.lastHitEpoch !== "number" && typeof result.lastHitEpoch === "number") {
    snapshot.lastHitEpoch = result.lastHitEpoch;
  }
  if (typeof status.lineResolvable === "boolean") {
    snapshot.lineResolvable = status.lineResolvable;
  } else if (typeof result.lineResolvable === "boolean") {
    snapshot.lineResolvable = result.lineResolvable;
  }
  if (typeof status.lineValidation === "string") {
    snapshot.lineValidation = status.lineValidation;
  } else if (typeof result.lineValidation === "string") {
    snapshot.lineValidation = result.lineValidation;
  }
  if (typeof runtime.runtimeInstanceId === "string") {
    snapshot.runtimeInstanceId = runtime.runtimeInstanceId;
  } else if (typeof status.runtimeInstanceId === "string") {
    snapshot.runtimeInstanceId = status.runtimeInstanceId;
  } else if (typeof result.runtimeInstanceId === "string") {
    snapshot.runtimeInstanceId = result.runtimeInstanceId;
  }
  if (typeof result.reasonCode === "string") {
    snapshot.reasonCode = result.reasonCode;
  } else if (typeof json.reasonCode === "string") {
    snapshot.reasonCode = json.reasonCode;
  }
  return snapshot;
}

function responseStatus(output: {
  structuredContent: Record<string, unknown>;
}): number | undefined {
  const response = output.structuredContent.response;
  return isRecord(response) && typeof response.status === "number" ? response.status : undefined;
}

function ensureProbeSnapshot(args: {
  output: { structuredContent: Record<string, unknown> };
  target: SecurityRuntimeTarget;
  requireRuntimeIdentity: boolean;
}): ProbeSnapshot {
  const snapshot = asProbeSnapshot(args.output);
  const status = responseStatus(args.output);
  if (status !== undefined && (status < 200 || status >= 300)) {
    throw new SidecarBlockedError(
      "security_sidecar_probe_unavailable",
      `Probe '${args.target.probeId}' returned HTTP ${status} for '${args.target.strictLineKey}'.`,
    );
  }
  if (snapshot.reasonCode === "invalid_line_target" || snapshot.lineResolvable === false) {
    throw new SidecarBlockedError(
      "security_sidecar_runtime_target_unresolvable",
      `Strict Line Key '${args.target.strictLineKey}' is not resolvable in Probe '${args.target.probeId}'.`,
    );
  }
  if (args.requireRuntimeIdentity && !snapshot.runtimeInstanceId) {
    throw new SidecarBlockedError(
      "security_sidecar_runtime_identity_missing",
      `Probe '${args.target.probeId}' did not return a runtime instance identity.`,
    );
  }
  return snapshot;
}

function actualOutcome(response: Record<string, unknown>): "allow" | "deny" | "error" | "blocked" {
  const status = response.status;
  const statusCode = typeof response.statusCode === "number" ? response.statusCode : 0;
  if (status !== "pass" && status !== "fail_http") return "blocked";
  if (statusCode >= 200 && statusCode < 400) return "allow";
  if (statusCode >= 400 && statusCode < 500) return "deny";
  return "error";
}

function expectationMatches(
  response: Record<string, unknown>,
  expectation: SecurityRequestExpectation,
): boolean {
  const outcome = actualOutcome(response);
  if (outcome === "blocked" || outcome !== expectation.outcome) return false;
  return !expectation.statusCodes || expectation.statusCodes.includes(Number(response.statusCode));
}

function httpEvidence(args: {
  caseId: string;
  phase: "baseline" | "attack";
  response: Record<string, unknown>;
}): SecurityEvidenceReference {
  let status = "status=unknown";
  if (typeof args.response.statusCode === "number") {
    status = `status=${args.response.statusCode}`;
  } else if (typeof args.response.status === "string") {
    status = `status=${args.response.status}`;
  }
  return {
    id: `${args.caseId}-${args.phase}-http`,
    kind: "http_response",
    summary: `sidecar-assisted ${args.phase} response ${status}`,
    redacted: true,
  };
}

function probeEvidence(args: {
  caseId: string;
  phase: "baseline" | "attack";
  target: SecurityRuntimeTarget;
  snapshot: ProbeSnapshot;
  source: "status" | "wait_for_hit";
  hit: boolean;
}): SecurityEvidenceReference {
  const runtime = args.snapshot.runtimeInstanceId ?? "unknown";
  return {
    id: `${args.caseId}-${args.phase}-${args.target.id}-${args.source}`,
    kind: "probe",
    summary: `Probe ${args.source} ${args.hit ? "confirmed" : "did not confirm"} '${args.target.strictLineKey}' on runtime '${runtime}'`,
    redacted: true,
  };
}

function instrumentationEvidence(
  targets: SecurityInstrumentationTarget[],
): SecurityEvidenceReference[] {
  return targets.map((target) => ({
    id: `sidecar-instrumentation-${target.id}`,
    kind: "runtime",
    summary: `Selected ${target.scope} instrumentation target '${target.classFqcn}'${
      target.dependencyRef ? ` from dependency '${target.dependencyRef}'` : ""
    }`,
    redacted: true,
  }));
}

function blockedCase(args: {
  attack: SecurityAttackProfile;
  reasonCode: string;
  evidence?: SecurityEvidenceReference[];
}): SidecarCaseResult {
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

function linkedTargets(
  contract: SidecarSecurityPlanContract,
  attack: SecurityAttackProfile,
): SecurityRuntimeTarget[] {
  return contract.runtimeTargets.filter((target) => target.entrypointRef === attack.entrypointRef);
}

function checkRuntimeIdentity(args: {
  target: SecurityRuntimeTarget;
  snapshot: ProbeSnapshot;
  activeRuntimeByProbe: Map<string, string>;
}): void {
  const runtimeInstanceId = args.snapshot.runtimeInstanceId;
  if (!runtimeInstanceId) {
    throw new SidecarBlockedError(
      "security_sidecar_runtime_identity_missing",
      `Probe '${args.target.probeId}' did not return a runtime instance identity.`,
    );
  }
  const activeRuntime = args.activeRuntimeByProbe.get(args.target.probeId);
  if (activeRuntime && activeRuntime !== runtimeInstanceId) {
    throw new SidecarBlockedError(
      "security_sidecar_runtime_mismatch",
      `Probe '${args.target.probeId}' changed runtime identity from '${activeRuntime}' to '${runtimeInstanceId}'.`,
    );
  }
}

async function resetAndSnapshot(args: {
  targets: SecurityRuntimeTarget[];
  mcpInvoke: SecurityMcpToolInvoker;
  activeRuntimeByProbe: Map<string, string>;
}): Promise<Map<string, ProbeSnapshot>> {
  const snapshots = new Map<string, ProbeSnapshot>();
  for (const target of args.targets) {
    const reset = await args.mcpInvoke({
      toolName: "probe",
      input: {
        action: "reset",
        input: { key: target.strictLineKey, probeId: target.probeId },
      },
    });
    const resetSnapshot = ensureProbeSnapshot({
      output: reset,
      target,
      requireRuntimeIdentity: false,
    });
    if (
      resetSnapshot.lineResolvable === false ||
      resetSnapshot.reasonCode === "invalid_line_target"
    ) {
      throw new SidecarBlockedError(
        "security_sidecar_runtime_target_unresolvable",
        `Strict Line Key '${target.strictLineKey}' is not resolvable in Probe '${target.probeId}'.`,
      );
    }
    const status = await args.mcpInvoke({
      toolName: "probe",
      input: {
        action: "status",
        input: { key: target.strictLineKey, probeId: target.probeId },
      },
    });
    const snapshot = ensureProbeSnapshot({
      output: status,
      target,
      requireRuntimeIdentity: true,
    });
    checkRuntimeIdentity({ target, snapshot, activeRuntimeByProbe: args.activeRuntimeByProbe });
    snapshots.set(target.id, snapshot);
  }
  return snapshots;
}

async function evaluateRuntimeExpectations(args: {
  caseId: string;
  phase: "baseline" | "attack";
  expectation: SecurityRequestExpectation;
  targetsById: Map<string, SecurityRuntimeTarget>;
  baselineSnapshots: Map<string, ProbeSnapshot>;
  phaseStartedAt: number;
  mcpInvoke: SecurityMcpToolInvoker;
  activeRuntimeByProbe: Map<string, string>;
}): Promise<RuntimePhaseEvaluation> {
  const evidence: SecurityEvidenceReference[] = [];
  const requiredHitIds: string[] = [];
  const forbiddenHitIds: string[] = [];
  for (const targetId of args.expectation.mustHitRuntimeTargets ?? []) {
    const target = args.targetsById.get(targetId);
    if (!target)
      throw new SidecarBlockedError(
        "security_sidecar_runtime_target_ambiguous",
        `Runtime target '${targetId}' is not selected.`,
      );
    const wait = await args.mcpInvoke({
      toolName: "probe",
      input: {
        action: "wait_for_hit",
        input: { key: target.strictLineKey, probeId: target.probeId },
      },
    });
    const snapshot = ensureProbeSnapshot({ output: wait, target, requireRuntimeIdentity: false });
    const runtimeInstanceId =
      snapshot.runtimeInstanceId ?? args.activeRuntimeByProbe.get(target.probeId);
    checkRuntimeIdentity({
      target,
      snapshot: runtimeInstanceId ? { ...snapshot, runtimeInstanceId } : snapshot,
      activeRuntimeByProbe: args.activeRuntimeByProbe,
    });
    const hit = asProbeResult(wait).hit === true && snapshot.lastHitEpoch >= args.phaseStartedAt;
    evidence.push(
      probeEvidence({
        caseId: args.caseId,
        phase: args.phase,
        target,
        snapshot,
        source: "wait_for_hit",
        hit,
      }),
    );
    if (!hit) {
      throw new SidecarBlockedError(
        "security_sidecar_required_line_not_hit",
        `Required Strict Line Key '${target.strictLineKey}' was not hit during ${args.phase}.`,
      );
    }
    requiredHitIds.push(target.id);
  }
  for (const targetId of args.expectation.mustNotHitRuntimeTargets ?? []) {
    const target = args.targetsById.get(targetId);
    if (!target)
      throw new SidecarBlockedError(
        "security_sidecar_runtime_target_ambiguous",
        `Runtime target '${targetId}' is not selected.`,
      );
    const status = await args.mcpInvoke({
      toolName: "probe",
      input: {
        action: "status",
        input: { key: target.strictLineKey, probeId: target.probeId },
      },
    });
    const snapshot = ensureProbeSnapshot({ output: status, target, requireRuntimeIdentity: true });
    checkRuntimeIdentity({ target, snapshot, activeRuntimeByProbe: args.activeRuntimeByProbe });
    const baseline = args.baselineSnapshots.get(target.id);
    const hit = Boolean(
      baseline &&
      snapshot.hitCount > baseline.hitCount &&
      snapshot.lastHitEpoch >= args.phaseStartedAt,
    );
    evidence.push(
      probeEvidence({
        caseId: args.caseId,
        phase: args.phase,
        target,
        snapshot,
        source: "status",
        hit,
      }),
    );
    if (hit) forbiddenHitIds.push(target.id);
  }
  return { requiredHitIds, forbiddenHitIds, evidence };
}

async function executeCase(args: {
  contract: SidecarSecurityPlanContract;
  attack: SecurityAttackProfile;
  mcpInvoke: SecurityMcpToolInvoker;
  activeRuntimeByProbe: Map<string, string>;
  runtimeCredentialContext?: Record<string, string>;
  rule?: SecurityBlackboxKnowledgeRule;
}): Promise<SidecarCaseResult> {
  const targets = linkedTargets(args.contract, args.attack);
  const targetsById = new Map(targets.map((target) => [target.id, target]));
  const evidence: SecurityEvidenceReference[] = [];
  try {
    const entrypoint = args.contract.entrypoints.find(
      (candidate) => candidate.id === args.attack.entrypointRef,
    );
    const authenticationProfile = args.contract.authenticationProfiles.find(
      (candidate) => candidate.id === args.attack.authenticationProfileRef,
    );
    if (!entrypoint || !authenticationProfile) {
      return blockedCase({ attack: args.attack, reasonCode: "security_sidecar_reference_missing" });
    }
    if (entrypointType(entrypoint) !== "http") {
      return blockedCase({
        attack: args.attack,
        reasonCode: "security_sidecar_entrypoint_not_supported",
      });
    }
    const baselineSnapshots = await resetAndSnapshot({
      targets,
      mcpInvoke: args.mcpInvoke,
      activeRuntimeByProbe: args.activeRuntimeByProbe,
    });
    const baselineStartedAt = Date.now();
    const baselineRequest = buildBlackboxHttpRequest({
      contract: args.contract,
      entrypoint,
      attackRequest: args.attack.baseline,
      authenticationProfile,
      ...(args.runtimeCredentialContext
        ? { runtimeCredentialContext: args.runtimeCredentialContext }
        : {}),
    });
    const baselineOut = await args.mcpInvoke({
      toolName: "transport_execute",
      input: { protocol: "http", request: baselineRequest, options: { wrappedOnly: true } },
    });
    const baselineResponse = baselineOut.structuredContent;
    evidence.push(
      httpEvidence({ caseId: args.attack.id, phase: "baseline", response: baselineResponse }),
    );
    if (!expectationMatches(baselineResponse, args.attack.baseline.expect)) {
      return blockedCase({
        attack: args.attack,
        reasonCode: "security_sidecar_baseline_unexpected",
        evidence,
      });
    }
    const baselineRuntime = await evaluateRuntimeExpectations({
      caseId: args.attack.id,
      phase: "baseline",
      expectation: args.attack.baseline.expect,
      targetsById,
      baselineSnapshots,
      phaseStartedAt: baselineStartedAt,
      mcpInvoke: args.mcpInvoke,
      activeRuntimeByProbe: args.activeRuntimeByProbe,
    });
    evidence.push(...baselineRuntime.evidence);

    const attackSnapshots = await resetAndSnapshot({
      targets,
      mcpInvoke: args.mcpInvoke,
      activeRuntimeByProbe: args.activeRuntimeByProbe,
    });
    const attackStartedAt = Date.now();
    const attackRequest = buildBlackboxHttpRequest({
      contract: args.contract,
      entrypoint,
      attackRequest: args.attack.attack,
      authenticationProfile,
      ...(args.runtimeCredentialContext
        ? { runtimeCredentialContext: args.runtimeCredentialContext }
        : {}),
    });
    const attackOut = await args.mcpInvoke({
      toolName: "transport_execute",
      input: { protocol: "http", request: attackRequest, options: { wrappedOnly: true } },
    });
    const attackResponse = attackOut.structuredContent;
    evidence.push(
      httpEvidence({ caseId: args.attack.id, phase: "attack", response: attackResponse }),
    );
    if (actualOutcome(attackResponse) === "blocked") {
      return blockedCase({
        attack: args.attack,
        reasonCode: "security_sidecar_transport_blocked",
        evidence,
      });
    }
    const attackRuntime = await evaluateRuntimeExpectations({
      caseId: args.attack.id,
      phase: "attack",
      expectation: args.attack.attack.expect,
      targetsById,
      baselineSnapshots: attackSnapshots,
      phaseStartedAt: attackStartedAt,
      mcpInvoke: args.mcpInvoke,
      activeRuntimeByProbe: args.activeRuntimeByProbe,
    });
    evidence.push(...attackRuntime.evidence);

    const attackOutcome = actualOutcome(attackResponse);
    const externalVulnerability =
      args.attack.attack.expect.outcome === "deny" && attackOutcome === "allow";
    const internalWeakness =
      args.attack.attack.expect.outcome === "deny" &&
      attackOutcome === "deny" &&
      attackRuntime.forbiddenHitIds.length > 0;
    if (externalVulnerability || internalWeakness) {
      let proofClassification: "external" | "internal" | "corroborated_external" = "internal";
      if (externalVulnerability) {
        proofClassification =
          attackRuntime.requiredHitIds.length > 0 ? "corroborated_external" : "external";
      }
      const findingId = `finding-${args.attack.id}`;
      let title = "External security control bypass";
      if (internalWeakness) title = "Runtime authorization boundary weakness";
      if (args.rule?.title) title = args.rule.title;
      const severity = args.rule?.severity ?? "medium";
      return {
        coverage: {
          caseId: args.attack.id,
          entrypointRef: args.attack.entrypointRef,
          authenticationProfileRef: args.attack.authenticationProfileRef,
          attackProfileRef: args.attack.id,
          outcome: "confirmed",
          proofClassification,
          evidenceRefIds: evidence.map((entry) => entry.id),
          findingIds: [findingId],
        },
        findings: [
          {
            id: findingId,
            severity,
            category: args.attack.category,
            title,
            description: externalVulnerability
              ? `Attack case '${args.attack.id}' returned an allowed response where denial was expected.`
              : `Attack case '${args.attack.id}' denied externally but reached a forbidden runtime target.`,
            outcome: "confirmed",
            proofClassification,
            evidenceRefIds: evidence.map((entry) => entry.id),
            entrypointRef: args.attack.entrypointRef,
            attackProfileRef: args.attack.id,
          },
        ],
        evidence,
      };
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
    return blockedCase({
      attack: args.attack,
      reasonCode: "security_sidecar_attack_unexpected",
      evidence,
    });
  } catch (error) {
    if (error instanceof SidecarBlockedError) {
      return blockedCase({ attack: args.attack, reasonCode: error.reasonCode, evidence });
    }
    return blockedCase({
      attack: args.attack,
      reasonCode:
        error instanceof Error
          ? (error.message.split(":")[0] ?? "security_sidecar_case_failed")
          : "security_sidecar_case_failed",
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

function validateTargetSelection(contract: SidecarSecurityPlanContract): void {
  const strictLineKeyToProbe = new Map<string, string>();
  for (const target of contract.runtimeTargets) {
    const previousProbe = strictLineKeyToProbe.get(target.strictLineKey);
    if (previousProbe && previousProbe !== target.probeId) {
      throw new SidecarBlockedError(
        "security_sidecar_runtime_target_ambiguous",
        `Strict Line Key '${target.strictLineKey}' is assigned to multiple Probe IDs.`,
      );
    }
    strictLineKeyToProbe.set(target.strictLineKey, target.probeId);
  }
}

export async function executeSidecarAssistedSecurityMode(args: {
  workspaceRootAbs?: string;
  projectName?: string;
  executionProfile?: string;
  planName: string;
  runId?: string;
  contract?: SidecarSecurityPlanContract;
  mcpInvoke?: SecurityMcpToolInvoker;
  runtimeCredentialContext?: Record<string, string>;
}): Promise<SecurityModeExecutionResult> {
  if (
    !args.contract ||
    !args.mcpInvoke ||
    !args.workspaceRootAbs ||
    !args.projectName ||
    !args.executionProfile
  ) {
    return {
      status: "blocked",
      runStatus: "blocked",
      reasonCode: "security_sidecar_input_invalid",
      requiredUserAction: [
        "Provide a validated Sidecar-assisted contract, project context, execution profile, and MCP invoker.",
      ],
      reasonMeta: { securityMode: "sidecar_assisted", planName: args.planName },
    };
  }
  const validatedContract = args.contract;
  try {
    const instrumentationSelection = validateSidecarInstrumentationSelection({
      workspaceRootAbs: args.workspaceRootAbs,
      runtimeTargets: validatedContract.runtimeTargets,
      ...(validatedContract.instrumentationTargets
        ? { instrumentationTargets: validatedContract.instrumentationTargets }
        : {}),
    });
    if (!instrumentationSelection.ok) {
      throw new SidecarBlockedError(
        instrumentationSelection.reasonCode,
        instrumentationSelection.reason,
      );
    }
    validateTargetSelection(validatedContract);
    const activeRuntimeByProbe = new Map<string, string>();
    const seenTargetIds = new Set<string>();
    for (const target of validatedContract.runtimeTargets) {
      if (seenTargetIds.has(target.id)) {
        throw new SidecarBlockedError(
          "security_sidecar_runtime_target_ambiguous",
          `Runtime target '${target.id}' is duplicated.`,
        );
      }
      seenTargetIds.add(target.id);
      const status = await args.mcpInvoke({
        toolName: "probe",
        input: { action: "status", input: { key: target.strictLineKey, probeId: target.probeId } },
      });
      const snapshot = ensureProbeSnapshot({
        output: status,
        target,
        requireRuntimeIdentity: true,
      });
      checkRuntimeIdentity({ target, snapshot, activeRuntimeByProbe });
      if (!activeRuntimeByProbe.has(target.probeId)) {
        activeRuntimeByProbe.set(target.probeId, snapshot.runtimeInstanceId!);
      }
    }

    const matrix: SecurityFiniteAttackMatrix = {
      mode: "finite_matrix",
      plannedCaseIds: validatedContract.attackProfiles.map((attack) => attack.id),
      plannedCount: validatedContract.attackProfiles.length,
      ...(validatedContract.securityKnowledge?.packRefs
        ? { knowledgePackRefs: validatedContract.securityKnowledge.packRefs }
        : {}),
    };
    let selectedPacks: SecurityBlackboxKnowledgePack[] | undefined;
    if (validatedContract.securityKnowledge?.packRefs) {
      const packs = await loadSecurityBlackboxKnowledgePacks({
        packRefs: validatedContract.securityKnowledge.packRefs,
      });
      if (!packs.ok) {
        throw new SidecarBlockedError(
          packs.reasonCode,
          `Resolve knowledge-pack references: ${packs.refs.join(", ")}.`,
        );
      }
      selectedPacks = packs.packs;
    }
    const cases: SecurityCaseCoverage[] = [];
    const findings: SecurityFinding[] = [];
    const evidence: SecurityEvidenceReference[] = instrumentationEvidence(
      instrumentationSelection.selection.instrumentationTargets,
    );
    const startedAt = Date.now();
    for (const attack of validatedContract.attackProfiles) {
      if (Date.now() - startedAt > args.contract.safetyPolicy.maxDurationMs) {
        cases.push(
          blockedCase({ attack, reasonCode: "security_sidecar_duration_exceeded" }).coverage,
        );
        continue;
      }
      const executeArgs: Parameters<typeof executeCase>[0] = {
        contract: validatedContract,
        attack,
        mcpInvoke: args.mcpInvoke,
        activeRuntimeByProbe,
      };
      if (args.runtimeCredentialContext) {
        executeArgs.runtimeCredentialContext = args.runtimeCredentialContext;
      }
      const rule = selectedPacks
        ? findApplicableSecurityBlackboxRule({
            packs: selectedPacks,
            category: attack.category,
            entrypointType: (() => {
              const entrypoint = validatedContract.entrypoints.find(
                (candidate) => candidate.id === attack.entrypointRef,
              );
              return entrypoint ? entrypointType(entrypoint) : "http";
            })(),
            authenticationKind:
              validatedContract.authenticationProfiles.find(
                (profile) => profile.id === attack.authenticationProfileRef,
              )?.kind ?? "custom",
            fixtureContextKeys: Object.keys(validatedContract.targetBoundary.fixtureContext ?? {}),
          })
        : undefined;
      if (rule) executeArgs.rule = rule;
      const result = await executeCase(executeArgs);
      cases.push(result.coverage);
      findings.push(...result.findings);
      evidence.push(...result.evidence);
    }
    const coverage = buildCoverage(cases, matrix.plannedCount);
    const hasBlocked = !coverage.complete || coverage.blockedCount > 0;
    const hasFailingFinding = findings.some((finding) =>
      validatedContract.verdictPolicy.failOnSeverity.includes(finding.severity),
    );
    let status: "pass" | "fail" | "blocked" = "pass";
    if (hasBlocked) status = "blocked";
    else if (hasFailingFinding) status = "fail";
    const runId = args.runId ?? `security-${Date.now()}`;
    const written = await writeSecurityRunArtifacts({
      workspaceRootAbs: args.workspaceRootAbs,
      projectName: args.projectName,
      planName: args.planName,
      runId,
      executionProfile: args.executionProfile,
      securityMode: "sidecar_assisted",
      status,
      matrix,
      coverage,
      findings,
      evidence,
      ...(hasBlocked ? { reasonCode: "security_sidecar_coverage_incomplete" } : {}),
    });
    return {
      status: status === "blocked" ? "blocked" : "executed",
      runStatus: status,
      runId,
      ...(hasBlocked ? { reasonCode: "security_sidecar_coverage_incomplete" } : {}),
      reasonMeta: {
        securityMode: "sidecar_assisted",
        planName: args.planName,
        runDirAbs: written.runDirAbs,
      },
    };
  } catch (error) {
    const reasonCode =
      error instanceof SidecarBlockedError ? error.reasonCode : "security_sidecar_preflight_failed";
    return {
      status: "blocked",
      runStatus: "blocked",
      reasonCode,
      requiredUserAction: [error instanceof Error ? error.message : String(error)],
      reasonMeta: { securityMode: "sidecar_assisted", planName: args.planName },
    };
  }
}
