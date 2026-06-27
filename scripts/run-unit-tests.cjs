const fs = require("node:fs");
const path = require("node:path");
const { spawn } = require("node:child_process");

const repoRoot = path.resolve(__dirname, "..");
const testRoots = [
  path.join(repoRoot, "test", "config"),
  path.join(repoRoot, "test", "models"),
  path.join(repoRoot, "test", "skills"),
  path.join(repoRoot, "test", "tools"),
  path.join(repoRoot, "test", "utils"),
];

const serialFiles = new Set([
  path.join(repoRoot, "test", "tools", "spring", "performance-runtime-suite-executor.test.ts"),
]);

function collectTestFiles(root) {
  if (!fs.existsSync(root)) return [];
  const out = [];
  const stack = [root];
  while (stack.length > 0) {
    const current = stack.pop();
    const entries = fs.readdirSync(current, { withFileTypes: true });
    for (const entry of entries) {
      const next = path.join(current, entry.name);
      if (entry.isDirectory()) {
        stack.push(next);
        continue;
      }
      if (entry.isFile() && next.endsWith(".test.ts")) {
        out.push(next);
      }
    }
  }
  return out;
}

function runNodeTests(files, concurrency) {
  if (files.length === 0) return Promise.resolve(0);
  const args = [
    "--test",
    ...(typeof concurrency === "number" ? ["--test-concurrency", String(concurrency)] : []),
    "-r",
    "ts-node/register",
    "-r",
    "tsconfig-paths/register",
    ...files,
  ];
  return new Promise((resolve) => {
    const child = spawn(process.execPath, args, {
      cwd: repoRoot,
      stdio: "inherit",
      shell: false,
    });
    child.on("close", (code) => resolve(typeof code === "number" ? code : 1));
    child.on("error", () => resolve(1));
  });
}

async function main() {
  const allFiles = testRoots.flatMap(collectTestFiles).sort();
  const concurrentFiles = allFiles.filter((file) => !serialFiles.has(file));
  const serialList = allFiles.filter((file) => serialFiles.has(file));

  const concurrentExit = await runNodeTests(concurrentFiles);
  const serialExit = await runNodeTests(serialList, 1);

  if (concurrentExit !== 0 || serialExit !== 0) {
    process.exit(1);
  }
}

main().catch((error) => {
  console.error(error instanceof Error ? error.stack ?? error.message : String(error));
  process.exit(1);
});
