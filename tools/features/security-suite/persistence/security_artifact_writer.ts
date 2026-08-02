import { promises as fs } from "node:fs";
import path from "node:path";

import {
  ensureSecurityRunRootAbs,
  type SecurityEvidenceReference,
  type SecurityFinding,
  type SecurityFiniteAttackMatrix,
  type SecurityCoverage,
  type SecurityRunArtifact,
} from "@tools-security-execution-plan-spec";

const MAX_SECURITY_DIAGNOSTIC_LENGTH = 512;
const REDACTED_VALUE = "[REDACTED]";

function sanitizeSecurityDiagnostic(value: string): string {
  let sanitized = value;
  sanitized = sanitized.replace(/-----BEGIN [^-]+-----[\s\S]*?-----END [^-]+-----/gi, REDACTED_VALUE);
  sanitized = sanitized.replace(/\b(?:Bearer|Basic)\s+[^\s,;]+/gi, (scheme) => `${scheme.split(/\s+/)[0]} ${REDACTED_VALUE}`);
  sanitized = sanitized.replace(
    /\b(password|passwd|token|secret|api[_-]?key|credential|authorization|cookie|set-cookie)\b\s*[:=]\s*["']?[^,;\s"']+/gi,
    (match) => `${match.slice(0, match.search(/[:=]/))}${match.match(/[:=]/)?.[0] ?? "="}${REDACTED_VALUE}`,
  );
  sanitized = sanitized.replace(
    /\b[A-Z][A-Z0-9_]{2,}\s*=\s*[^\s,;]+/g,
    (match) => `${match.slice(0, match.indexOf("="))}=${REDACTED_VALUE}`,
  );
  sanitized = sanitized.replace(/([?&](?:token|password|secret|api[_-]?key|credential)=)[^&#\s]+/gi, `$1${REDACTED_VALUE}`);
  sanitized = sanitized.replace(/\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b/g, REDACTED_VALUE);
  sanitized = sanitized.replace(/\b[A-Za-z0-9_-]{32,}\b/g, REDACTED_VALUE);
  return sanitized.length > MAX_SECURITY_DIAGNOSTIC_LENGTH
    ? `${sanitized.slice(0, MAX_SECURITY_DIAGNOSTIC_LENGTH - 3)}...`
    : sanitized;
}

function sanitizeSecurityId(value: string): string {
  return sanitizeSecurityDiagnostic(value.trim()).slice(0, 160);
}

function sanitizeSecurityFinding(finding: SecurityFinding): SecurityFinding {
  return {
    ...finding,
    id: sanitizeSecurityId(finding.id),
    category: sanitizeSecurityDiagnostic(finding.category),
    title: sanitizeSecurityDiagnostic(finding.title),
    description: sanitizeSecurityDiagnostic(finding.description),
    evidenceRefIds: finding.evidenceRefIds.map(sanitizeSecurityId),
    ...(finding.entrypointRef ? { entrypointRef: sanitizeSecurityId(finding.entrypointRef) } : {}),
    ...(finding.attackProfileRef ? { attackProfileRef: sanitizeSecurityId(finding.attackProfileRef) } : {}),
  };
}

function sanitizeSecurityEvidence(evidence: SecurityEvidenceReference): SecurityEvidenceReference {
  return {
    ...evidence,
    id: sanitizeSecurityId(evidence.id),
    summary: sanitizeSecurityDiagnostic(evidence.summary),
    ...(evidence.artifactPath ? { artifactPath: sanitizeSecurityDiagnostic(evidence.artifactPath) } : {}),
    redacted: true,
  };
}

function sanitizeSecurityMatrix(matrix: SecurityFiniteAttackMatrix): SecurityFiniteAttackMatrix {
  return {
    ...matrix,
    plannedCaseIds: matrix.plannedCaseIds.map(sanitizeSecurityId),
  };
}

function sanitizeSecurityCoverage(coverage: SecurityCoverage): SecurityCoverage {
  return {
    ...coverage,
    cases: coverage.cases.map((securityCase) => ({
      ...securityCase,
      caseId: sanitizeSecurityId(securityCase.caseId),
      entrypointRef: sanitizeSecurityId(securityCase.entrypointRef),
      authenticationProfileRef: sanitizeSecurityId(securityCase.authenticationProfileRef),
      attackProfileRef: sanitizeSecurityId(securityCase.attackProfileRef),
      evidenceRefIds: securityCase.evidenceRefIds.map(sanitizeSecurityId),
      findingIds: securityCase.findingIds.map(sanitizeSecurityId),
      ...(securityCase.reasonCode ? { reasonCode: sanitizeSecurityDiagnostic(securityCase.reasonCode) } : {}),
    })),
  };
}

export async function writeSecurityRunArtifacts(args: {
  workspaceRootAbs: string;
  projectName: string;
  planName: string;
  runId: string;
  executionProfile: string;
  securityMode: SecurityRunArtifact["securityMode"];
  status: SecurityRunArtifact["status"];
  matrix: SecurityFiniteAttackMatrix;
  coverage: SecurityCoverage;
  findings: SecurityFinding[];
  evidence: SecurityEvidenceReference[];
  reasonCode?: string;
}): Promise<{ runDirAbs: string; artifact: SecurityRunArtifact }> {
  const runDirAbs = await ensureSecurityRunRootAbs(args);
  const matrix = sanitizeSecurityMatrix(args.matrix);
  const coverage = sanitizeSecurityCoverage(args.coverage);
  const findings = args.findings.map(sanitizeSecurityFinding);
  const evidence = args.evidence.map(sanitizeSecurityEvidence);
  const artifact: SecurityRunArtifact = {
    schemaVersion: "1.0.0",
    suiteType: "security",
    securityMode: args.securityMode,
    executionProfile: args.executionProfile,
    planName: args.planName,
    runId: args.runId,
    status: args.status,
    ...(args.reasonCode ? { reasonCode: sanitizeSecurityDiagnostic(args.reasonCode) } : {}),
    matrix,
    coverage,
    findings,
    evidence,
  };
  const files: Record<string, unknown> = {
    "matrix.json": matrix,
    "coverage.json": coverage,
    "findings.json": findings,
    "evidence.json": evidence,
    "execution.result.json": artifact,
  };
  await Promise.all(
    Object.entries(files).map(([name, value]) =>
      fs.writeFile(path.join(runDirAbs, name), `${JSON.stringify(value, null, 2)}\n`, "utf8"),
    ),
  );
  return { runDirAbs, artifact };
}
