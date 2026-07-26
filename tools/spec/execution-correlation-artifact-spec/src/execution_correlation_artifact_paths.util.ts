import path from "node:path";

export function resolveExecutionCorrelationArtifactPath(args: { runDirAbs: string }): string {
  return path.join(args.runDirAbs, "correlation", "correlation.json");
}
