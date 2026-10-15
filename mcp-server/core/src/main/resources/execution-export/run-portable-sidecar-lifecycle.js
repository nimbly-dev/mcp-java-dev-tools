#!/usr/bin/env node
// Owns only the child JVM and helper invocations created by this portable replay.
const fs = require("node:fs");
const path = require("node:path");
const cp = require("node:child_process");
const { setTimeout: pause } = require("node:timers/promises");

function argumentsOf(argv) {
  const values = {};
  for (let index = 0; index < argv.length; index += 2) values[argv[index].slice(2)] = argv[index + 1];
  return values;
}

function helper(java, config, root, environment, args) {
  const result = cp.spawnSync(java, ["-jar", path.resolve(root, config.helperJarRel), ...args], {
    encoding: "utf8", env: environment, windowsHide: true, timeout: 30000, maxBuffer: 1048576,
  });
  if (result.error || result.status !== 0) throw new Error("portable_helper_failed");
  return JSON.parse(result.stdout);
}

function mutation(java, config, root, environment, action, identity) {
  const args = [action, "--pid", String(identity.pid), "--expected-process-start-epoch-ms", String(identity.processStartEpochMs),
    "--agent-jar", path.resolve(root, config.agentJarRel), "--confirm", "true"];
  if (action === "attach") {
    const filters = (values) => (values ?? []).map(value => {
      if (/[;\r\n]/.test(value)) throw new Error("portable_filter_invalid");
      return value;
    }).join(",");
    args.push("--agent-args", `host=${config.probeHost};port=${config.probePort};include=${filters(config.include)};exclude=${filters(config.exclude)}`);
  }
  return helper(java, config, root, environment, args);
}

async function identityOf(java, config, root, environment, child) {
  for (let attempt = 0; attempt < 30; attempt++) {
    const listed = helper(java, config, root, environment, ["discover"]);
    const identity = (listed.candidates ?? []).find(candidate => String(candidate.pid) === String(child.pid));
    if (identity?.processStartEpochMs > 0) return identity;
    if (child.exitCode !== null || child.signalCode !== null) break;
    await pause(200);
  }
  throw new Error("portable_process_identity_unavailable");
}

async function stop(child) {
  if (!child?.pid || child.exitCode !== null || child.signalCode !== null) return;
  child.kill();
  const deadline = Date.now() + 5000;
  while (child.exitCode === null && child.signalCode === null && Date.now() < deadline) await pause(50);
  if (child.exitCode === null && child.signalCode === null) child.kill("SIGKILL");
  const forcedDeadline = Date.now() + 5000;
  while (child.exitCode === null && child.signalCode === null && Date.now() < forcedDeadline) await pause(50);
  if (child.exitCode === null && child.signalCode === null) throw new Error("portable_runtime_cleanup_failed");
}

async function run(values, config, root, java, environment) {
  const evidence = { status: "failed", cleanupSucceeded: false, probeId: config.probeId };
  let runtime;
  let workload;
  let identity;
  let attached = false;
  let cancelled = false;
  const cancel = () => {
    cancelled = true;
    workload?.kill();
  };
  process.on("SIGINT", cancel);
  process.on("SIGTERM", cancel);
  try {
    runtime = cp.spawn(java, config.startup.args, { cwd: root, env: environment, windowsHide: true, stdio: "ignore" });
    runtime.on("error", () => { cancelled = true; });
    identity = await identityOf(java, config, root, environment, runtime);
    evidence.pid = Number(identity.pid);
    evidence.processStartEpochMs = identity.processStartEpochMs;
    const activated = mutation(java, config, root, environment, "attach", identity);
    if (activated.outcome !== "active") throw new Error(activated.reasonCode || "portable_attach_failed");
    attached = true;
    evidence.attachOutcome = activated.outcome;
    const endpoint = new URL(`http://${config.probeHost}:${config.probePort}${config.statusPath}`);
    endpoint.searchParams.set("key", "mcp.jvm.diagnose#key");
    const response = await fetch(endpoint, {
      signal: AbortSignal.timeout(5000),
    });
    if (!response.ok) throw new Error("portable_probe_verification_failed");
    const status = await response.json();
    if ((status.probe ?? status).key !== "mcp.jvm.diagnose#key") throw new Error("portable_probe_verification_failed");
    evidence.probeVerification = "ok";
    if (cancelled) throw new Error("portable_replay_cancelled");
    const shellArgs = values.mode === "ps1" ? ["-NoProfile", "-NonInteractive", "-File", values.script] : [values.script.replace(/\\/g, "/")];
    const outcome = await new Promise(resolve => {
      workload = cp.spawn(values.shell, shellArgs, {
        cwd: root, env: { ...environment, MCPJVM_PORTABLE_RUNTIME_ACTIVE: "1" }, windowsHide: true, stdio: "inherit",
      });
      workload.on("error", () => resolve(1));
      workload.on("close", code => resolve(code ?? 1));
    });
    evidence.status = !cancelled && outcome === 0 ? "completed" : "failed";
    evidence.exitCode = cancelled ? 130 : outcome;
  } catch (error) {
    evidence.reasonCode = /^portable_[a-z_]+$/.test(error?.message) ? error.message : "portable_replay_failed";
    evidence.exitCode = cancelled ? 130 : 1;
  } finally {
    try {
      if (attached) {
        const deactivated = mutation(java, config, root, environment, "deactivate", identity);
        evidence.deactivateOutcome = deactivated.outcome;
        if (deactivated.outcome !== "deactivated" || deactivated.nonRestorableClasses?.length) {
          evidence.status = "failed";
          evidence.exitCode = 1;
        }
      }
    } catch {
      evidence.status = "failed";
      evidence.exitCode = 1;
      evidence.deactivateOutcome = "unverified";
    } finally {
      try {
        await stop(workload);
        await stop(runtime);
        evidence.cleanupSucceeded = true;
      } finally {
        fs.writeFileSync(path.join(root, "portable-runtime.evidence.json"), JSON.stringify(evidence, null, 2) + "\n");
        process.removeListener("SIGINT", cancel);
        process.removeListener("SIGTERM", cancel);
      }
    }
  }
  process.exitCode = evidence.exitCode ?? 1;
}

async function main() {
  const values = argumentsOf(process.argv.slice(2));
  const root = path.dirname(path.resolve(values.config));
  const config = JSON.parse(fs.readFileSync(values.config, "utf8"));
  const temporary = path.join(root, "tmp");
  fs.mkdirSync(temporary, { recursive: true });
  const environment = { ...process.env, JAVA_TOOL_OPTIONS: `-Djava.io.tmpdir="${temporary}"` };
  const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, "bin", process.platform === "win32" ? "java.exe" : "java") : "java";
  if (values.action === "run") return run(values, config, root, java, environment);
  if (!values.pid || !values["expected-process-start-epoch-ms"]) throw new Error("portable_process_identity_required");
  const result = mutation(java, config, root, environment, values.action || "attach", {
    pid: values.pid, processStartEpochMs: values["expected-process-start-epoch-ms"],
  });
  if (!["active", "deactivated"].includes(result.outcome)) throw new Error("portable_helper_failed");
  if (values.evidence) fs.writeFileSync(values.evidence, JSON.stringify({ status: "ok", action: values.action, probeId: config.probeId }) + "\n");
}

main().catch(() => {
  process.stderr.write('{"status":"blocked","reasonCode":"portable_lifecycle_failed"}\n');
  process.exitCode = 1;
});
