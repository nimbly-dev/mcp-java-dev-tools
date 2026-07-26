const assert = require("node:assert/strict");
const test = require("node:test");

const { buildSearchRoots } = require("@tools-core/synthesis_search_roots.util");

test("[UT][core][synthesis_search_roots][ok] buildSearchRoots includes workspace root when it differs from project root", () => {
  const roots = buildSearchRoots("C:\\repo\\service", "C:\\repo");
  assert.deepEqual(roots, ["C:\\repo\\service", "C:\\repo"]);
});

test("[UT][core][synthesis_search_roots][ok] buildSearchRoots keeps unique roots when project and workspace roots match", () => {
  const roots = buildSearchRoots("C:\\repo", "C:\\repo");
  assert.deepEqual(roots, ["C:\\repo"]);
});
