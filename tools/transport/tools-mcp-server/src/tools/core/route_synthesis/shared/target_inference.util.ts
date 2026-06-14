import * as path from "node:path";
import { buildJavaIndex } from "@/utils/inference/java_index.util";

export type InferredTarget = {
  file: string;
  className?: string;
  methodName?: string;
  line?: number | null;
  declarationLine?: number;
  endLine?: number;
  firstExecutableLine?: number | null;
  lineSelectionStatus?: "validated" | "unresolved";
  lineSelectionSource?: "runtime_probe_validation";
  signature?: string;
  returnsBoolean?: boolean;
  fqcn?: string;
  key?: string;
  reasons: string[];
};

export type ClassMethodSpan = {
  methodName: string;
  signature: string;
  startLine: number;
  endLine: number;
  firstExecutableLine?: number | null;
  lineSelectionStatus?: "validated" | "unresolved";
  lineSelectionSource?: "runtime_probe_validation";
  probeKey?: string;
};

export type ClassDiscoveryCandidate = {
  file: string;
  className: string;
  fqcn?: string;
  methods: ClassMethodSpan[];
};

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function inferReturnsBoolean(signature: string, methodName: string): boolean {
  const rx = new RegExp(
    `\\b(?:boolean|Boolean|java\\.lang\\.Boolean)\\s+${escapeRegExp(methodName)}\\s*\\(`,
  );
  return rx.test(signature);
}

function normalize(s?: string): string {
  return (s ?? "").trim().toLowerCase();
}

function sortClassMethodsByStartLine(methods: ClassMethodSpan[]): ClassMethodSpan[] {
  return [...methods].sort((a, b) => {
    if (a.startLine !== b.startLine) return a.startLine - b.startLine;
    return a.methodName.localeCompare(b.methodName);
  });
}

function sortClassCandidates(classes: ClassDiscoveryCandidate[]): ClassDiscoveryCandidate[] {
  return [...classes].sort((a, b) => {
    const left = (a.fqcn ?? a.className).toLowerCase();
    const right = (b.fqcn ?? b.className).toLowerCase();
    if (left !== right) return left.localeCompare(right);
    return a.file.localeCompare(b.file);
  });
}

function scoreCandidate(args: {
  classHint?: string;
  methodHint?: string;
  lineHint?: number;
  filePath: string;
  fqcn?: string;
  className?: string;
  methodName?: string;
  declarationLine?: number;
  firstExecutableLine?: number | null;
}): { score: number; reasons: string[] } {
  const reasons: string[] = [];
  let score = 1;

  const classHint = normalize(args.classHint);
  const classHintIsFqcn = classHint.includes(".");
  const methodHint = normalize(args.methodHint);
  const fqcn = normalize(args.fqcn);
  const className = normalize(args.className);
  const methodName = normalize(args.methodName);

  if (classHint) {
    if (classHintIsFqcn) {
      if (fqcn === classHint) {
        score += 100;
        reasons.push("class fqcn exact match");
      } else {
        return { score: 0, reasons: [] };
      }
    } else if (className === classHint || path.basename(args.filePath, ".java").toLowerCase() === classHint) {
      score += 90;
      reasons.push("class exact match");
    } else {
      return { score: 0, reasons: [] };
    }
  }

  if (methodHint) {
    if (methodName === methodHint) {
      score += 50;
      reasons.push("method exact match");
    } else {
      return { score: 0, reasons: [] };
    }
  }

  if (typeof args.lineHint === "number") {
    const matchesDeclaration =
      typeof args.declarationLine === "number" && args.lineHint === args.declarationLine;
    const matchesExecutable =
      typeof args.firstExecutableLine === "number" && args.lineHint === args.firstExecutableLine;
    if (matchesDeclaration || matchesExecutable) {
      score += 10;
      reasons.push("line exact match");
    } else {
      return { score: 0, reasons: [] };
    }
  }

  return { score, reasons };
}

export async function inferTargets(args: {
  rootAbs: string;
  additionalRootsAbs?: string[];
  classHint?: string;
  methodHint?: string;
  lineHint?: number;
  maxFiles?: number;
  maxCandidates?: number;
}): Promise<{ scannedJavaFiles: number; candidates: InferredTarget[] }> {
  const indexArgs: Parameters<typeof buildJavaIndex>[0] = {
    rootAbs: args.rootAbs,
    maxFiles: args.maxFiles ?? 1500,
  };
  if (args.additionalRootsAbs?.length) indexArgs.additionalRootsAbs = args.additionalRootsAbs;
  if (args.classHint) indexArgs.classHint = args.classHint;
  const index = await buildJavaIndex(indexArgs);

  const out: Array<{ candidate: InferredTarget; score: number }> = [];
  for (const f of index) {
    if (f.methods.length === 0) continue;

    for (const m of f.methods) {
      const scoreArgs: Parameters<typeof scoreCandidate>[0] = {
        filePath: f.fileAbs,
        methodName: m.name,
        declarationLine: m.declarationLine,
        firstExecutableLine: m.firstExecutableLine,
      };
      if (args.classHint) scoreArgs.classHint = args.classHint;
      if (args.methodHint) scoreArgs.methodHint = args.methodHint;
      if (typeof args.lineHint === "number") scoreArgs.lineHint = args.lineHint;
      if (f.className) scoreArgs.className = f.className;
      if (f.packageName && f.className) scoreArgs.fqcn = `${f.packageName}.${f.className}`;
      const scored = scoreCandidate(scoreArgs);
      if (scored.score <= 0) continue;
      const fqcn = f.packageName && f.className ? `${f.packageName}.${f.className}` : undefined;
      const candidate: InferredTarget = {
        file: f.fileAbs,
        methodName: m.name,
        signature: m.signature,
        returnsBoolean: inferReturnsBoolean(m.signature, m.name),
        reasons: scored.reasons,
      };
      if (f.className) candidate.className = f.className;
      candidate.declarationLine = m.declarationLine;
      candidate.endLine = m.endLine;
      candidate.firstExecutableLine = m.firstExecutableLine;
      candidate.line = m.firstExecutableLine;
      if (fqcn) {
        candidate.fqcn = fqcn;
        candidate.key = `${fqcn}#${m.name}`;
      }
      out.push({ candidate, score: scored.score });
    }
  }

  out.sort((a, b) => {
    if (b.score !== a.score) return b.score - a.score;
    const af = (a.candidate.fqcn ?? a.candidate.className ?? "").toLowerCase();
    const bf = (b.candidate.fqcn ?? b.candidate.className ?? "").toLowerCase();
    if (af !== bf) return af.localeCompare(bf);
    const am = (a.candidate.methodName ?? "").toLowerCase();
    const bm = (b.candidate.methodName ?? "").toLowerCase();
    if (am !== bm) return am.localeCompare(bm);
    const al = typeof a.candidate.line === "number" ? a.candidate.line : Number.MAX_SAFE_INTEGER;
    const bl = typeof b.candidate.line === "number" ? b.candidate.line : Number.MAX_SAFE_INTEGER;
    if (al !== bl) return al - bl;
    return a.candidate.file.localeCompare(b.candidate.file);
  });

  return {
    scannedJavaFiles: index.length,
    candidates: out
      .slice(0, Math.max(1, Math.min(args.maxCandidates ?? 8, 20)))
      .map((entry) => entry.candidate),
  };
}

export async function discoverClassMethods(args: {
  rootAbs: string;
  additionalRootsAbs?: string[];
  classHint: string;
  maxFiles?: number;
}): Promise<{
  scannedJavaFiles: number;
  matchMode: "exact" | "none";
  classes: ClassDiscoveryCandidate[];
}> {
  const classNeedle = normalize(args.classHint);
  if (!classNeedle) {
    return { scannedJavaFiles: 0, matchMode: "none", classes: [] };
  }

  const index = await buildJavaIndex({
    rootAbs: args.rootAbs,
    ...(args.additionalRootsAbs?.length ? { additionalRootsAbs: args.additionalRootsAbs } : {}),
    maxFiles: args.maxFiles ?? 1500,
    classHint: args.classHint,
  });

  const exactMatches: ClassDiscoveryCandidate[] = [];
  for (const f of index) {
    if (!f.className) continue;

    const classNameLower = normalize(f.className);
    const fqcn = f.packageName ? `${f.packageName}.${f.className}` : undefined;
    const fqcnLower = normalize(fqcn);
    const isExact = classNameLower === classNeedle || fqcnLower === classNeedle;
    if (!isExact) continue;

    const methods = sortClassMethodsByStartLine(
      f.methods.map((m) => {
        const method: ClassMethodSpan = {
          methodName: m.name,
          signature: m.signature,
          startLine: m.declarationLine,
          endLine: m.endLine,
          firstExecutableLine: m.firstExecutableLine,
        };
        if (fqcn) method.probeKey = `${fqcn}#${m.name}`;
        return method;
      }),
    );

    const candidate: ClassDiscoveryCandidate = {
      file: f.fileAbs,
      className: f.className,
      methods,
    };
    if (fqcn) candidate.fqcn = fqcn;

    exactMatches.push(candidate);
  }

  if (exactMatches.length > 0) {
    return {
      scannedJavaFiles: index.length,
      matchMode: "exact",
      classes: sortClassCandidates(exactMatches),
    };
  }

  return {
    scannedJavaFiles: index.length,
    matchMode: "none",
    classes: [],
  };
}
