import { randomUUID } from "node:crypto";
import path from "node:path";

export function buildOneOffExportFolderName(now: Date): string {
  const yyyy = String(now.getFullYear()).padStart(4, "0");
  const mm = String(now.getMonth() + 1).padStart(2, "0");
  const dd = String(now.getDate()).padStart(2, "0");
  return `${yyyy}-${mm}-${dd}-${randomUUID()}`;
}

export function resolveOneOffExportDir(projectRootAbs: string, now: Date): string {
  return path.join(projectRootAbs, "exports", buildOneOffExportFolderName(now));
}
