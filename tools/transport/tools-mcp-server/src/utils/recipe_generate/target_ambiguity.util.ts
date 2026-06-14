import type { InferredTarget } from "@/tools/core/route_synthesis/shared/target_inference.util";

function normalizeTargetHint(value?: string): string {
  return (value ?? "").trim().toLowerCase();
}

function buildCandidateClassScope(candidate: InferredTarget, classHint: string): string {
  const normalizedHint = normalizeTargetHint(classHint);
  const hintLooksLikeFqcn = normalizedHint.includes(".");
  const fqcn = normalizeTargetHint(candidate.fqcn);
  const className = normalizeTargetHint(candidate.className);
  if (hintLooksLikeFqcn && fqcn) return `fqcn:${fqcn}`;
  if (className) return `class:${className}`;
  if (fqcn) return `fqcn:${fqcn}`;
  return `file:${candidate.file.toLowerCase()}`;
}

export function selectAmbiguousCandidates(args: {
  candidates: InferredTarget[];
  classHint: string;
  lineHint?: number;
}): InferredTarget[] {
  if (args.candidates.length < 2) return [];
  const top = args.candidates[0];
  if (!top?.methodName) return [];
  const topClassScope = buildCandidateClassScope(top, args.classHint);
  const topMethod = normalizeTargetHint(top.methodName);
  return args.candidates.filter((candidate) => {
    if (buildCandidateClassScope(candidate, args.classHint) !== topClassScope) {
      return false;
    }
    if (normalizeTargetHint(candidate.methodName) !== topMethod) {
      return false;
    }
    if (typeof args.lineHint === "number") {
      return candidate.declarationLine === args.lineHint || candidate.line === args.lineHint;
    }
    return true;
  });
}
