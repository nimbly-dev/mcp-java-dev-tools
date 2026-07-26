const assert = require("node:assert/strict");
const fs = require("node:fs/promises");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");

const { renderPerformanceResultFromArtifacts } = require("@tools-feature-performance-suite");

async function withRunDir(callback: (runDir: string) => Promise<void>): Promise<void> {
  const runDir = await fs.mkdtemp(path.join(os.tmpdir(), "mcp-performance-result-"));
  try {
    await callback(runDir);
  } finally {
    await fs.rm(runDir, { recursive: true, force: true });
  }
}

async function writeBaseArtifacts(
  runDir: string,
  executionResult: Record<string, unknown>,
): Promise<void> {
  await fs.writeFile(path.join(runDir, "execution.result.json"), JSON.stringify(executionResult));
  await fs.writeFile(
    path.join(runDir, "evidence.json"),
    JSON.stringify({ msta: { status: "available" } }),
  );
}

test("[UT][performance-suite][performance_result_renderer][blocked_invalid] result renderer blocks when an available correlation Artifact is missing", async () => {
  await withRunDir(async (runDir) => {
    await writeBaseArtifacts(runDir, {
      status: "pass",
      correlation: {
        enabled: true,
        status: "available",
        reasonCode: "sampled_attribution_available",
      },
    });

    const result = await renderPerformanceResultFromArtifacts({ runDirAbs: runDir });
    assert.deepEqual(result, {
      status: "blocked",
      reasonCode: "artifact_files_missing",
      missing: ["correlation/correlation.json"],
      nextAction: "Regenerate the performance run to persist the required correlation Artifact.",
    });
  });
});

test("[UT][performance-suite][performance_result_renderer][ok] result renderer emits an unavailable correlation placeholder without a method table", async () => {
  await withRunDir(async (runDir) => {
    await writeBaseArtifacts(runDir, {
      status: "fail",
      metrics: { durationMs: 15000, errorRatePct: 0, throughputPerSec: 10, p95LatencyMs: 20 },
      requiredLineHits: [],
      msta: { status: "not_configured" },
      correlation: {
        enabled: true,
        status: "unavailable",
        reasonCode: "msta_not_configured",
      },
    });

    const result = await renderPerformanceResultFromArtifacts({ runDirAbs: runDir });
    assert.equal(result.status, "rendered");
    assert.match(result.text, /Correlation: n\/a \(msta_not_configured\)/);
    assert.equal(result.text.includes("| Step | Anchor Method |"), false);
  });
});

test("[UT][performance-suite][performance_result_renderer][ok] result renderer renders the specified method table for valid correlation evidence", async () => {
  await withRunDir(async (runDir) => {
    await writeBaseArtifacts(runDir, {
      status: "pass",
      metrics: { durationMs: 15000, errorRatePct: 0, throughputPerSec: 10, p95LatencyMs: 20 },
      requiredLineHits: [{ key: "Events#publish:42", hit: true }],
      msta: { status: "available" },
      correlation: {
        enabled: true,
        status: "available",
        reasonCode: "sampled_attribution_available",
      },
    });
    await fs.mkdir(path.join(runDir, "correlation"));
    await fs.writeFile(
      path.join(runDir, "correlation", "correlation.json"),
      JSON.stringify({
        schemaVersion: 1,
        suite: "performance",
        kind: "sampled_attribution",
        status: "available",
        reasonCode: "sampled_attribution_available",
        run: { planId: "plan", runId: "run" },
        workloadIdentity: {
          provider: { type: "builtin", mode: "http" },
          entrypoint: { protocol: "http", method: "POST", pathTemplate: "/events" },
          loadModel: { mode: "concurrency", concurrency: 1, rampUpSeconds: 0, durationSeconds: 1 },
        },
        anchors: [
          {
            source: "verified_required_line_hit",
            strictLineKey: "Events#publish:42",
            resolvedMethodRef: "Events#publish",
            lineHit: "verified_line_hit",
          },
        ],
        evidence: {
          lineHits: [{ strictLineKey: "Events#publish:42", status: "verified_line_hit" }],
          msta: { status: "available", sampleCount: 1 },
        },
        attributions: [
          {
            step: 1,
            anchorMethod: "Events#publish",
            strictLineKey: "Events#publish:42",
            methodRef: "Events#publish",
            role: "anchor",
            samples: 1,
            estimatedPathTimeMs: 10,
            estimatedPathSharePct: 100,
            correlation: "correlated_sampled_path",
          },
        ],
      }),
    );

    const result = await renderPerformanceResultFromArtifacts({ runDirAbs: runDir });
    assert.equal(result.status, "rendered");
    assert.match(
      result.text,
      /\| Step \| Anchor Method \| Strict Line Key \| Method \| Role \| Samples \| Estimated Path Time \(ms\) \| Path Share \(%\) \| Correlation Evidence \|/,
    );
    assert.match(result.text, /\| 1 \| Events#publish \| Events#publish:42 \|/);
    assert.equal(result.text.includes("root cause"), false);
  });
});
