const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");
const assert = require("node:assert/strict");

const {
  parsePerformanceContract,
  resolveProfilerProviderMetadata,
} = require("../../../../../tools/features/performance-suite/support/parse_performance_contract");
const { buildPerformanceMstaSummary } = require("@tools-feature-performance-suite");

function buildContract(provider: string): Record<string, unknown> {
  return {
    entrypoints: [
      {
        transport: { protocol: "http", baseUrl: "http://127.0.0.1:8080" },
        request: { method: "GET", path: "/health" },
      },
    ],
    workloadProvider: { type: "builtin" },
    observationTargets: { requiredLineHits: ["Example#run:10"] },
    loadModel: { mode: "concurrency", concurrency: 1, rampUpSeconds: 0, durationSeconds: 1 },
    successCriteria: { maxErrorRatePct: 0, minThroughputPerSec: 1, p95LatencyMs: 1000 },
    analysis: { executionTiming: { enabled: true, provider, outputFormat: "jfr" } },
  };
}

test("[UT][performance-suite] accepts auto, async-profiler, and jfr provider intent", () => {
  for (const provider of ["auto", "async-profiler", "jfr"]) {
    const parsed = parsePerformanceContract(buildContract(provider));
    assert.equal(parsed.ok, true, provider);
    assert.equal(parsed.contract.analysis.executionTiming.provider, provider);
  }
});

test("[UT][performance-suite] persists actual selected JFR provider metadata", () => {
  assert.deepEqual(
    resolveProfilerProviderMetadata({
      result: { provider: "jfr", event: "wall", outputFormat: "jfr" },
    }),
    { name: "jfr", event: "wall", outputFormat: "jfr" },
  );
});

test("[UT][performance-suite] MSTA accepts JFR ExecutionSample events", async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "mcp-jvm-jfr-provider-"));
  const fakeJfrPath = path.join(root, "execution-timing.jfr");
  const fakeBinDir = path.join(root, "fake-bin");
  fs.mkdirSync(fakeBinDir, { recursive: true });
  fs.writeFileSync(fakeJfrPath, "fixture", "utf8");
  fs.writeFileSync(
    path.join(fakeBinDir, "jfr.js"),
    "process.stdout.write(JSON.stringify({type:'jdk.ExecutionSample',samples:5,frames:['Example#run','Example#helper']})+'\\n');",
    "utf8",
  );
  fs.writeFileSync(
    path.join(fakeBinDir, "jfr.cmd"),
    [`@echo off`, `"${process.execPath}" "%~dp0jfr.js"`].join("\r\n"),
    "utf8",
  );

  const previousExtractor = process.env.MCP_JAVA_DEV_TOOLS_JFR_EXTRACTOR;
  process.env.MCP_JAVA_DEV_TOOLS_JFR_EXTRACTOR = path.join(fakeBinDir, "jfr.cmd");
  try {
    const summary = await buildPerformanceMstaSummary({
      requiredLineHits: ["Example#run:10"],
      methodTargets: ["Example#run"],
      mode: "method_targets",
      provider: { name: "jfr", event: "wall", outputFormat: "jfr" },
      durationMs: 1000,
      profilerStopResult: { result: { outputPath: fakeJfrPath } },
      runDirAbs: root,
    });

    assert.equal(summary.status, "available");
    assert.deepEqual(summary.sourceEventTypes, ["jdk.ExecutionSample"]);
    assert.equal(summary.provider.name, "jfr");
    assert.equal(summary.methods[0].samples, 5);
  } finally {
    if (typeof previousExtractor === "string") {
      process.env.MCP_JAVA_DEV_TOOLS_JFR_EXTRACTOR = previousExtractor;
    } else {
      delete process.env.MCP_JAVA_DEV_TOOLS_JFR_EXTRACTOR;
    }
    fs.rmSync(root, { recursive: true, force: true, maxRetries: 50, retryDelay: 100 });
  }
});
