import type {
  SecurityMode,
  SecurityOutcome,
  SecurityProofClassification,
  SecuritySeverity,
} from "./security_contract.model";

export type SecurityEvidenceKind =
  | "http_request"
  | "http_response"
  | "probe"
  | "source"
  | "jar"
  | "runtime"
  | "diagnostic"
  | "artifact";

export type SecurityEvidenceReference = {
  id: string;
  kind: SecurityEvidenceKind;
  summary: string;
  artifactPath?: string;
  redacted: true;
};

export type SecurityFinding = {
  id: string;
  severity: SecuritySeverity;
  category: string;
  title: string;
  description: string;
  outcome: "confirmed";
  proofClassification: SecurityProofClassification;
  evidenceRefIds: string[];
  entrypointRef?: string;
  attackProfileRef?: string;
};

export type SecurityCaseCoverage = {
  caseId: string;
  entrypointRef: string;
  authenticationProfileRef: string;
  attackProfileRef: string;
  outcome: SecurityOutcome;
  proofClassification?: SecurityProofClassification;
  evidenceRefIds: string[];
  findingIds: string[];
  reasonCode?: string;
};

export type SecurityFiniteAttackMatrix = {
  mode: "finite_matrix";
  plannedCaseIds: string[];
  plannedCount: number;
  knowledgePackRefs?: string[];
};

export type SecurityCoverage = {
  plannedCount: number;
  executedCount: number;
  passedCount: number;
  confirmedCount: number;
  notApplicableCount: number;
  blockedCount: number;
  complete: boolean;
  cases: SecurityCaseCoverage[];
};

export type SecurityRunStatus = "pass" | "fail" | "blocked" | "partial_fail" | "in_progress";

export type SecurityRunArtifact = {
  schemaVersion: "1.0.0";
  suiteType: "security";
  securityMode: SecurityMode;
  executionProfile: string;
  planName: string;
  runId: string;
  status: SecurityRunStatus;
  reasonCode?: string;
  matrix: SecurityFiniteAttackMatrix;
  coverage: SecurityCoverage;
  findings: SecurityFinding[];
  evidence: SecurityEvidenceReference[];
};

export type SecurityRunArtifactFile =
  | "matrix.json"
  | "coverage.json"
  | "findings.json"
  | "evidence.json"
  | "execution.result.json";
