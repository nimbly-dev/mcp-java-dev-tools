const { spawn } = require("node:child_process");

function runNpmScript(name) {
  const command = process.platform === "win32" ? (process.env.ComSpec || "cmd.exe") : "npm";
  const args = process.platform === "win32" ? ["/d", "/s", "/c", "npm", "run", name] : ["run", name];
  return new Promise((resolve) => {
    const child = spawn(command, args, {
      stdio: ["inherit", "pipe", "pipe"],
      shell: false,
    });
    let combinedOutput = "";
    child.stdout.on("data", (chunk) => {
      const text = String(chunk ?? "");
      combinedOutput += text;
      process.stdout.write(text);
    });
    child.stderr.on("data", (chunk) => {
      const text = String(chunk ?? "");
      combinedOutput += text;
      process.stderr.write(text);
    });
    child.on("close", (code) => {
      resolve({
        code: typeof code === "number" ? code : 1,
        output: combinedOutput,
      });
    });
    child.on("error", () => {
      resolve({
        code: 1,
        output: "",
      });
    });
  });
}

function parseNodeTestFailures(output) {
  const failures = [];
  const lines = output.split(/\r?\n/);
  let inFailingSection = false;

  for (const rawLine of lines) {
    const line = rawLine.trim();
    if (line.endsWith("failing tests:")) {
      inFailingSection = true;
      continue;
    }
    if (!inFailingSection) continue;
    if (line.startsWith("test at ")) continue;
    if (line.startsWith("ℹ ") || line.startsWith("i ")) break;
    if (line.startsWith("✖ ")) {
      failures.push(line.slice(2).trim());
    }
  }

  return failures;
}

function printPhaseFailureSummary(failure) {
  console.error(`[run:all] ${failure.phase} reported failures:`);
  if (failure.failingTests.length === 0) {
    console.error("  - no parsed node test names; see phase output above");
    return;
  }
  for (const failingTest of failure.failingTests) {
    console.error(`  - ${failingTest}`);
  }
}

async function main() {
  const phases = (process.env.RUN_ALL_PHASES ?? "test,test:it")
    .split(",")
    .map((entry) => entry.trim())
    .filter((entry) => entry.length > 0);
  const failures = [];

  for (const phase of phases) {
    console.error(`\n[run:all] begin ${phase}`);
    const result = await runNpmScript(phase);
    console.error(`[run:all] end ${phase} exit=${result.code}`);
    if (result.code !== 0) {
      const failure = {
        phase,
        code: result.code,
        failingTests: parseNodeTestFailures(result.output),
      };
      failures.push(failure);
      printPhaseFailureSummary(failure);
    }
  }

  if (failures.length > 0) {
    console.error("\n[run:all] failing phases:");
    for (const failure of failures) {
      console.error(`- ${failure.phase} (exit ${failure.code})`);
      for (const failingTest of failure.failingTests) {
        console.error(`  - ${failingTest}`);
      }
    }
    process.exit(1);
  }
}

main().catch((error) => {
  console.error("[run:all] runner_failed");
  console.error(error instanceof Error ? error.stack ?? error.message : String(error));
  process.exit(1);
});
