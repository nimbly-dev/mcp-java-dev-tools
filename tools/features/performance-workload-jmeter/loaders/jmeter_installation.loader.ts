import path from "node:path";
import { promises as fs } from "node:fs";

async function fileExists(absPath: string): Promise<boolean> {
  try {
    await fs.access(absPath);
    return true;
  } catch {
    return false;
  }
}

export async function resolveJmeterExecutable(args: {
  installationPath?: string;
}): Promise<string | null> {
  const candidates: string[] = [];
  const configuredHome =
    typeof args.installationPath === "string" && args.installationPath.trim().length > 0
      ? args.installationPath.trim()
      : typeof process.env.MCP_JAVA_DEV_TOOLS_JMETER_HOME === "string" &&
          process.env.MCP_JAVA_DEV_TOOLS_JMETER_HOME.trim().length > 0
        ? process.env.MCP_JAVA_DEV_TOOLS_JMETER_HOME.trim()
        : undefined;

  if (configuredHome) {
    if (process.platform === "win32") {
      candidates.push(path.join(configuredHome, "bin", "jmeter.bat"));
      candidates.push(path.join(configuredHome, "bin", "jmeter.cmd"));
    } else {
      candidates.push(path.join(configuredHome, "bin", "jmeter"));
    }
  }

  const pathEntries = (process.env.PATH ?? "")
    .split(path.delimiter)
    .map((entry) => entry.trim())
    .filter((entry) => entry.length > 0);
  for (const entry of pathEntries) {
    if (process.platform === "win32") {
      candidates.push(path.join(entry, "jmeter.bat"));
      candidates.push(path.join(entry, "jmeter.cmd"));
      candidates.push(path.join(entry, "jmeter.exe"));
    } else {
      candidates.push(path.join(entry, "jmeter"));
    }
  }

  for (const candidate of candidates) {
    if (await fileExists(candidate)) {
      return candidate;
    }
  }
  return null;
}

