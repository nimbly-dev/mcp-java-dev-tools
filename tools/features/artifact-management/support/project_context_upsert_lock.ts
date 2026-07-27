import path from "node:path";
import { randomUUID } from "node:crypto";
import { promises as fs } from "node:fs";

export type ProjectContextUpsertLock = { pathAbs: string; owner: string };

export async function acquireProjectContextUpsertLock(
  projectsFileAbs: string,
): Promise<ProjectContextUpsertLock | undefined> {
  const pathAbs = `${projectsFileAbs}.upsert.lock`;
  await fs.mkdir(path.dirname(projectsFileAbs), { recursive: true });
  const owner = randomUUID();
  for (let attempt = 0; attempt < 2; attempt += 1) {
    try {
      const handle = await fs.open(pathAbs, "wx");
      try {
        await handle.writeFile(
          JSON.stringify({ owner, expiresAtEpochMs: Date.now() + 30 * 60 * 1000 }),
          "utf8",
        );
      } finally {
        await handle.close();
      }
      return { pathAbs, owner };
    } catch (error) {
      if (!(error instanceof Error) || !("code" in error) || error.code !== "EEXIST") {
        throw error;
      }
      const existing = await fs.readFile(pathAbs, "utf8").catch(() => undefined);
      let expiresAtEpochMs: number | undefined;
      try {
        const parsed = JSON.parse(existing ?? "") as { expiresAtEpochMs?: unknown };
        if (typeof parsed.expiresAtEpochMs === "number") expiresAtEpochMs = parsed.expiresAtEpochMs;
      } catch {
        return undefined;
      }
      if (expiresAtEpochMs === undefined || expiresAtEpochMs > Date.now()) return undefined;
      await fs.unlink(pathAbs).catch(() => undefined);
    }
  }
  return undefined;
}

export async function releaseProjectContextUpsertLock(
  lock: ProjectContextUpsertLock,
): Promise<void> {
  const existing = await fs.readFile(lock.pathAbs, "utf8").catch(() => undefined);
  if (!existing?.includes(`"owner":"${lock.owner}"`)) return;
  await fs.unlink(lock.pathAbs).catch(() => undefined);
}
