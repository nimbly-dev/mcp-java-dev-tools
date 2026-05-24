import path from "node:path";

export function toWorkspaceShellPath(input: { workspaceRootAbs: string; rawPath: string }): string {
  const rawPath = input.rawPath.trim();
  if (rawPath.length === 0) {
    return rawPath;
  }

  const rootAbs = path.resolve(input.workspaceRootAbs);
  const pathAbs = path.isAbsolute(rawPath) ? path.normalize(rawPath) : path.resolve(rootAbs, rawPath);
  const relativePath = path.relative(rootAbs, pathAbs);
  if (relativePath.length === 0) {
    return "${__MCPJVM_WORKSPACE_ROOT}";
  }
  if (!relativePath.startsWith("..") && !path.isAbsolute(relativePath)) {
    return `\${__MCPJVM_WORKSPACE_ROOT}/${relativePath.replaceAll("\\", "/")}`;
  }

  return pathAbs.replaceAll("\\", "/");
}

export function shellDoubleQuote(value: string): string {
  return `"${value.replace(/\\/g, "\\\\").replace(/"/g, '\\"')}"`;
}
