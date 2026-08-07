import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import test from "node:test";
import { setTimeout as delay } from "node:timers/promises";

import {
  buildLineKey,
  findLineNumberBySnippet,
  postControllerFqcn,
  postControllerSourceFileAbs,
  resolveJavaAgentJar,
  resolveJvmAttachHelperJar,
  startPostAppWithoutAgent,
} from "@test/support/spring/social-platform/shared.fixture";

type LifecycleResult = {
  operation: string;
  outcome: string;
  reasonCode: string;
  pids: string[];
  candidates?: LifecycleCandidate[];
  nonRestorableClasses: string[];
};

type LifecycleCandidate = {
  pid: string;
  processStartEpochMs?: number;
};

type ProbeStatus = {
  probe?: {
    hitCount?: number;
    lineValidation?: string;
  };
};

test("[IT][java-agent][probe] dynamic attach instruments a live Spring application and deactivation stops Probe", async () => {
  const runtime = await startPostAppWithoutAgent();
  try {
    const preflight = await createFixturePost(runtime.apiBaseUrl, "Pre-attach fixture post.");
    assert.equal(preflight.status, 201, await preflight.text());

    const helperJar = await resolveJvmAttachHelperJar();
    const agentJar = await resolveJavaAgentJar();
    const discovered = await runLifecycleHelper(helperJar, ["discover"]);
    const target = discovered.candidates?.find((candidate) => candidate.pid === String(runtime.pid));
    assert.ok(
      target?.processStartEpochMs,
      `Dynamic-attach fixture JVM was not present in lifecycle discovery: ${JSON.stringify(discovered)}`,
    );
    const expectedProcessStartEpochMs = String(target.processStartEpochMs);
    const probePort = new URL(runtime.probeBaseUrl).port;
    const agentArgs = `host=127.0.0.1;port=${probePort};include=com.example.social.**`;

    const attached = await runLifecycleHelper(helperJar, [
      "attach",
      "--pid",
      String(runtime.pid),
      "--agent-jar",
      agentJar,
      "--expected-process-start-epoch-ms",
      expectedProcessStartEpochMs,
      "--confirm",
      "true",
      "--agent-args",
      agentArgs,
    ]);
    assert.equal(attached.outcome, "active");
    assert.equal(attached.reasonCode, "active");

    const repeated = await runLifecycleHelper(helperJar, [
      "attach",
      "--pid",
      String(runtime.pid),
      "--agent-jar",
      agentJar,
      "--expected-process-start-epoch-ms",
      expectedProcessStartEpochMs,
      "--confirm",
      "true",
      "--agent-args",
      agentArgs,
    ]);
    assert.equal(repeated.outcome, "active");
    assert.equal(repeated.reasonCode, "already_active");

    const line = await findLineNumberBySnippet(
      postControllerSourceFileAbs,
      "return postService.createPost(request, authentication.getName());",
    );
    const key = buildLineKey({
      fqcn: postControllerFqcn,
      methodName: "createPost",
      line,
    });
    await resetProbe(runtime.probeBaseUrl, key);

    const response = await createFixturePost(
      runtime.apiBaseUrl,
      "Dynamic attach integration fixture post.",
    );
    assert.equal(response.status, 201, `${await response.text()}\n${runtime.logs()}`);

    await waitForProbeHit(runtime.probeBaseUrl, key, runtime.logs());

    const deactivated = await runLifecycleHelper(helperJar, [
      "deactivate",
      "--pid",
      String(runtime.pid),
      "--agent-jar",
      agentJar,
      "--expected-process-start-epoch-ms",
      expectedProcessStartEpochMs,
      "--confirm",
      "true",
    ]);
    assert.equal(deactivated.outcome, "deactivated");
    assert.equal(deactivated.reasonCode, "deactivated");
    await assertProbeUnavailable(runtime.probeBaseUrl, key);
  } finally {
    await runtime.stop();
  }
});

async function createFixturePost(apiBaseUrl: string, content: string): Promise<Response> {
  return await fetch(`${apiBaseUrl}/api/v1/posts`, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      authorization: "Bearer alice-token",
      "x-run-as-tenant": "fixture-tenant",
      "x-run-as-user": "alice",
    },
    body: JSON.stringify({
      content,
      visibility: "PUBLIC",
      tags: ["fixture", "dynamic-attach"],
    }),
  });
}

async function runLifecycleHelper(helperJar: string, args: string[]): Promise<LifecycleResult> {
  const result = await new Promise<{ exitCode: number | null; stdout: string; stderr: string }>(
    (resolve, reject) => {
      const child = spawn("java", ["-jar", helperJar, ...args], {
        stdio: ["ignore", "pipe", "pipe"],
        windowsHide: true,
      });
      const stdout: string[] = [];
      const stderr: string[] = [];
      child.stdout.on("data", (chunk: Buffer) => stdout.push(String(chunk)));
      child.stderr.on("data", (chunk: Buffer) => stderr.push(String(chunk)));
      child.once("error", reject);
      child.once("close", (exitCode) => {
        resolve({ exitCode, stdout: stdout.join(""), stderr: stderr.join("") });
      });
    },
  );
  assert.equal(result.exitCode, 0, result.stderr);
  return parseLifecycleResult(result.stdout);
}

function parseLifecycleResult(stdout: string): LifecycleResult {
  const parsed = JSON.parse(stdout) as Partial<LifecycleResult>;
  if (
    typeof parsed.operation !== "string"
    || typeof parsed.outcome !== "string"
    || typeof parsed.reasonCode !== "string"
    || !Array.isArray(parsed.pids)
    || !Array.isArray(parsed.nonRestorableClasses)
  ) {
    throw new Error(`Invalid JVM lifecycle helper output: ${stdout}`);
  }
  return parsed as LifecycleResult;
}

async function resetProbe(probeBaseUrl: string, key: string): Promise<void> {
  const response = await fetch(`${probeBaseUrl}/__probe/reset`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ key }),
  });
  assert.equal(response.status, 200, await response.text());
}

async function waitForProbeHit(probeBaseUrl: string, key: string, runtimeLogs: string): Promise<void> {
  const deadline = Date.now() + 10_000;
  let lastStatus: ProbeStatus | undefined;
  while (Date.now() < deadline) {
    const response = await fetch(`${probeBaseUrl}/__probe/status?key=${encodeURIComponent(key)}`);
    if (response.ok) {
      const status = (await response.json()) as ProbeStatus;
      lastStatus = status;
      if (status.probe?.lineValidation === "resolvable" && (status.probe.hitCount ?? 0) > 0) {
        return;
      }
    }
    await delay(250);
  }
  throw new Error(
    `Probe did not record a Line Hit for ${key}. Last status: ${JSON.stringify(lastStatus)}.\n${runtimeLogs}`
  );
}

async function assertProbeUnavailable(probeBaseUrl: string, key: string): Promise<void> {
  await delay(250);
  try {
    await fetch(`${probeBaseUrl}/__probe/status?key=${encodeURIComponent(key)}`);
  } catch {
    return;
  }
  throw new Error("Probe control server remained reachable after deactivation.");
}
