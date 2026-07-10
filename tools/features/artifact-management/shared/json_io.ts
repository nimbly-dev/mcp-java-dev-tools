import { promises as fs } from "node:fs";
import path from "node:path";

export async function readJsonFile(abs: string): Promise<unknown> {
  const raw = await fs.readFile(abs, "utf8");
  return JSON.parse(raw.replace(/^\uFEFF/, ""));
}

export async function writeJsonFile(abs: string, payload: unknown): Promise<void> {
  await fs.mkdir(path.dirname(abs), { recursive: true });
  await fs.writeFile(abs, `${JSON.stringify(payload, null, 2)}\n`, "utf8");
}
