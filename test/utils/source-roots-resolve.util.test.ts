const assert = require("node:assert/strict");
const fs = require("node:fs/promises");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");

const {
  MAX_ADDITIONAL_SOURCE_ROOTS,
  resolveAdditionalSourceRoots,
} = require("@tools-feature-route-synthesis");

async function withTempDir(run: (dir: string) => Promise<void>) {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), "source-roots-resolve-"));
  try {
    await run(dir);
  } finally {
    await fs.rm(dir, { recursive: true, force: true });
  }
}

test("resolveAdditionalSourceRoots resolves workspace-relative roots and deduplicates normalized paths", async () => {
  await withTempDir(async (workspaceRootAbs: string) => {
    const moduleSourceRoot = path.join(workspaceRootAbs, "core-module", "src", "main", "java");
    await fs.mkdir(moduleSourceRoot, { recursive: true });

    const resolved = await resolveAdditionalSourceRoots({
      workspaceRootAbs,
      additionalSourceRoots: [
        "core-module/src/main/java",
        path.join(workspaceRootAbs, "core-module", "src", "main", "java"),
      ],
    });

    assert.equal(resolved.ok, true);
    if (!resolved.ok) return;
    assert.deepEqual(resolved.normalizedAdditionalSourceRoots, [moduleSourceRoot]);
  });
});

test("resolveAdditionalSourceRoots fails closed when a root path points to a file", async () => {
  await withTempDir(async (workspaceRootAbs: string) => {
    const fileAbs = path.join(workspaceRootAbs, "not-a-directory.txt");
    await fs.writeFile(fileAbs, "fixture", "utf8");

    const resolved = await resolveAdditionalSourceRoots({
      workspaceRootAbs,
      additionalSourceRoots: [fileAbs],
    });

    assert.equal(resolved.ok, false);
    if (resolved.ok) return;
    assert.equal(resolved.reasonCode, "additional_source_roots_invalid");
    assert.equal(resolved.failedStep, "input_validation");
  });
});

test("resolveAdditionalSourceRoots fails closed when additional root count exceeds max", async () => {
  await withTempDir(async (workspaceRootAbs: string) => {
    const overLimit = Array.from(
      { length: MAX_ADDITIONAL_SOURCE_ROOTS + 1 },
      (_unused, idx) => `module-${idx}/src/main/java`,
    );
    const resolved = await resolveAdditionalSourceRoots({
      workspaceRootAbs,
      additionalSourceRoots: overLimit,
    });

    assert.equal(resolved.ok, false);
    if (resolved.ok) return;
    assert.equal(resolved.reasonCode, "additional_source_roots_limit_exceeded");
    assert.equal(resolved.failedStep, "input_validation");
  });
});
