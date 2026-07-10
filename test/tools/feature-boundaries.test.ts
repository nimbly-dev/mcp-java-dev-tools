import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";

const checker = path.resolve("scripts/check-feature-boundaries.cjs");

function runFixture(files: Record<string, string>): { status: number; output: string } {
  const root = mkdtempSync(path.join(os.tmpdir(), "mcpjvm-feature-boundaries-"));
  try {
    for (const [relative, content] of Object.entries(files)) {
      const target = path.join(root, relative);
      mkdirSync(path.dirname(target), { recursive: true });
      writeFileSync(target, content, "utf8");
    }
    try {
      return { status: 0, output: execFileSync(process.execPath, [checker], { env: { ...process.env, FEATURE_BOUNDARY_ROOT: root }, encoding: "utf8" }) };
    } catch (error) {
      const failure = error as { status?: number; stdout?: string; stderr?: string };
      return { status: failure.status ?? 1, output: `${failure.stdout ?? ""}${failure.stderr ?? ""}` };
    }
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
}

const base = {
  "tsconfig.json": '{"compilerOptions":{"paths":{}}}',
  "tools/features/probe/index.ts": "export {};",
  "tools/features/probe/actions/index.ts": "export {};",
  "tools/features/artifact/index.ts": "export {};",
  "tools/features/artifact/actions/index.ts": "export {};",
};

test("boundaries reject wildcard Feature aliases", () => {
  const result = runFixture({
    ...base,
    "tsconfig.json": '{"compilerOptions":{"paths":{"@tools-feature-probe/*":["tools/features/probe/*"]}}}',
  });
  assert.notEqual(result.status, 0);
  assert.match(result.output, /wildcard Feature alias/);
});

test("boundaries reject foreign actions and shared imports", () => {
  for (const privatePath of ["actions/hidden", "shared/hidden"]) {
    const result = runFixture({
      ...base,
      [`tools/features/artifact/${privatePath}.ts`]: "export default {};",
      "tools/features/probe/check.ts": `import value from "@tools-feature-artifact/${privatePath}"; export { value };`,
    });
    assert.notEqual(result.status, 0);
    assert.match(result.output, /foreign Feature-private code/);
  }
});

test("boundaries reject Feature to Transport Adapter imports", () => {
  const result = runFixture({
    ...base,
    "tools/transport/tools-mcp-server/src/tools/adapter.ts": "export {};",
    "tools/features/probe/check.ts": 'import value from "@/tools/adapter"; export { value };',
  });
  assert.notEqual(result.status, 0);
  assert.match(result.output, /Transport Adapter code/);
});

test("boundaries reject Artifact Spec runtime imports", () => {
  const result = runFixture({
    ...base,
    "tools/spec/project-artifact-spec/src/runtime.ts": 'import value from "../../../features/probe"; export { value };',
  });
  assert.notEqual(result.status, 0);
  assert.match(result.output, /runtime Feature Module code/);
});

test("boundaries accept public Feature imports", () => {
  const result = runFixture({
    ...base,
    "tools/features/probe/check.ts": 'import "@tools-feature-artifact";',
  });
  assert.equal(result.status, 0, result.output);
  assert.match(result.output, /passed/);
});
