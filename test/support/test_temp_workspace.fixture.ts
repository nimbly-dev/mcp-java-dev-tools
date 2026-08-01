import * as fs from "node:fs/promises";
import path from "node:path";

const repoOwnedTempRootAbs = path.join(process.cwd(), "test", ".tmp");

/** Creates an isolated, gitignored test workspace inside this repository. */
export async function createRepoOwnedTempWorkspace(prefix: string): Promise<string> {
  await fs.mkdir(repoOwnedTempRootAbs, { recursive: true });
  return await fs.mkdtemp(path.join(repoOwnedTempRootAbs, prefix));
}
