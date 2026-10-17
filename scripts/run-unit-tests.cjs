const fs = require("node:fs");
const path = require("node:path");
const { spawn } = require("node:child_process");

const repoRoot = path.resolve(__dirname, "..");
const testRoots = [path.join(repoRoot, "test", "unit")];

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
      if (entry.isFile() && next.endsWith(".test.cjs")) {
        out.push(next);
      }
    }
  }
  return out;
}

function runNodeTests(files, concurrency) {
  if (files.length === 0) throw new Error("No dependency-free unit tests found");
  const args = [
    "--test",
    ...(typeof concurrency === "number" ? ["--test-concurrency", String(concurrency)] : []),
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

  // Run the surviving Skill and resource assertions without npm dependencies.
  const unitExit = await runNodeTests(allFiles, 1);

  if (unitExit !== 0) {
    process.exit(1);
  }
}

main().catch((error) => {
  console.error(error instanceof Error ? (error.stack ?? error.message) : String(error));
  process.exit(1);
});
