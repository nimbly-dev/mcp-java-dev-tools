const assert = require("node:assert/strict");
const test = require("node:test");

const { buildPerformanceExecutionCorrelation } = require("@tools-feature-performance-suite");
const {
  validateExecutionCorrelationArtifactV1,
} = require("@tools-execution-correlation-artifact-spec");

function buildContract() {
  return {
    entrypoints: [
      {
        transport: { protocol: "http", baseUrl: "http://127.0.0.1:8080" },
        request: {
          method: "post",
          path: "events",
        },
      },
    ],
    workloadProvider: { type: "builtin" },
    observationTargets: {
      requiredLineHits: ["com.example.EventsController#publish:42"],
    },
    loadModel: {
      mode: "concurrency",
      concurrency: 25,
      rampUpSeconds: 2,
      durationSeconds: 15,
    },
    successCriteria: {
      maxErrorRatePct: 0,
      minThroughputPerSec: 1,
      p95LatencyMs: 100,
    },
  };
}

test("performance correlation derives a dynamic anchor and workload identity", () => {
  const artifact = buildPerformanceExecutionCorrelation({
    planId: "events-performance",
    runId: "run-1",
    contract: buildContract(),
    requiredLineHitResults: [{ key: "com.example.EventsController#publish:42", hit: true }],
    msta: {
      status: "available",
      unit: "ms",
      jfrPath: "execution-timing.jfr",
      sourceEventTypes: ["profiler.WallClockSample"],
      durationMs: 15000,
      provider: { name: "async-profiler", event: "wall", outputFormat: "jfr" },
      mode: "target_plus_path",
      methods: [
        {
          methodRef: "com.example.EventsController#publish",
          estimatedTimeMs: 10,
          estimatedTimePct: 66.667,
          samples: 10,
          pathSteps: [
            {
              stepOrder: 1,
              methodRef: "com.example.EventsController#publish",
              target: true,
              samples: 10,
              estimatedTimePct: 66.667,
              estimatedTimeMs: 10,
            },
          ],
          strictLineKey: "com.example.EventsController#publish:42",
        },
      ],
      targets: [
        {
          strictLineKey: "com.example.EventsController#publish:42",
          anchorMethod: "com.example.EventsController#publish",
          anchoredSampleCount: 10,
          dominantPathSampleCount: 10,
          dominantPathSamplePct: 100,
          dominantPathApproxTimeMs: 10,
          steps: [
            {
              stepOrder: 1,
              methodRef: "com.example.EventsController#publish",
              target: true,
              samples: 10,
              estimatedTimePct: 66.667,
              estimatedTimeMs: 10,
            },
          ],
        },
      ],
    },
  });

  assert.equal(artifact.status, "available");
  assert.deepEqual(artifact.anchors[0], {
    source: "verified_required_line_hit",
    strictLineKey: "com.example.EventsController#publish:42",
    resolvedMethodRef: "com.example.EventsController#publish",
    lineHit: "verified_line_hit",
  });
  assert.equal(validateExecutionCorrelationArtifactV1(artifact).ok, true);
});

test("performance correlation persists a valid unavailable state for a missed Line Hit", () => {
  const artifact = buildPerformanceExecutionCorrelation({
    planId: "events-performance",
    runId: "run-2",
    contract: buildContract(),
    requiredLineHitResults: [
      {
        key: "com.example.EventsController#publish:42",
        hit: false,
        reasonCode: "timeout_no_inline_hit",
      },
    ],
    msta: { status: "not_configured" },
  });

  assert.equal(artifact.status, "unavailable");
  assert.equal(artifact.reasonCode, "no_verified_line_hit");
  assert.deepEqual(artifact.anchors, []);
  assert.deepEqual(artifact.attributions, []);
  assert.equal(validateExecutionCorrelationArtifactV1(artifact).ok, true);
});

test("performance correlation preserves the persisted no-MSTA state with empty attributions", () => {
  const artifact = buildPerformanceExecutionCorrelation({
    planId: "events-performance",
    runId: "run-3",
    contract: buildContract(),
    requiredLineHitResults: [{ key: "com.example.EventsController#publish:42", hit: true }],
    msta: { status: "not_configured" },
  });

  assert.equal(artifact.status, "unavailable");
  assert.equal(artifact.reasonCode, "msta_not_configured");
  assert.equal(artifact.anchors.length, 1);
  assert.deepEqual(artifact.attributions, []);
  assert.deepEqual(artifact.evidence.msta, { status: "not_configured" });
  assert.equal(validateExecutionCorrelationArtifactV1(artifact).ok, true);
});

test("execution correlation validator fails closed on missing required collections", () => {
  const result = validateExecutionCorrelationArtifactV1({
    schemaVersion: 1,
    suite: "performance",
    kind: "sampled_attribution",
    status: "available",
    reasonCode: "sampled_attribution_available",
  });

  assert.deepEqual(result, {
    ok: false,
    reasonCode: "correlation_artifact_invalid",
    reason: "run.planId and run.runId are required.",
    nextAction: "regenerate_correlation_artifact",
  });
});

test("execution correlation validator rejects contradictory status, reason, and evidence", () => {
  const result = validateExecutionCorrelationArtifactV1({
    schemaVersion: 1,
    suite: "performance",
    kind: "sampled_attribution",
    status: "available",
    reasonCode: "msta_disabled",
    run: { planId: "plan", runId: "run" },
    workloadIdentity: {
      provider: { type: "builtin", mode: "http" },
      entrypoint: { protocol: "http", method: "POST", pathTemplate: "/events" },
      loadModel: { mode: "concurrency", concurrency: 1, rampUpSeconds: 0, durationSeconds: 1 },
    },
    anchors: [],
    evidence: {
      lineHits: [{ strictLineKey: "Events#publish:42", status: "verified_line_hit" }],
      msta: { status: "disabled" },
    },
    attributions: [],
  });

  assert.equal(result.ok, false);
  assert.equal(result.reasonCode, "correlation_artifact_invalid");
});

test("requireLineHit=false accepts a verified-subset correlation Artifact", () => {
  const contract = buildContract();
  contract.observationTargets.requiredLineHits = [
    "com.example.EventsController#publish:42",
    "com.example.EventsController#persist:55",
  ];
  const artifact = buildPerformanceExecutionCorrelation({
    planId: "events-performance",
    runId: "run-subset",
    contract,
    requiredLineHitResults: [
      { key: "com.example.EventsController#publish:42", hit: true },
      { key: "com.example.EventsController#persist:55", hit: false, reasonCode: "not_hit" },
    ],
    correlationPolicy: { requireLineHit: false, requireMsta: false },
    msta: {
      status: "available",
      unit: "ms",
      jfrPath: "execution-timing.jfr",
      sourceEventTypes: ["profiler.WallClockSample"],
      durationMs: 15000,
      targets: [
        {
          strictLineKey: "com.example.EventsController#publish:42",
          anchorMethod: "com.example.EventsController#publish",
          anchoredSampleCount: 1,
          steps: [
            {
              stepOrder: 1,
              methodRef: "com.example.EventsController#publish",
              target: true,
              samples: 1,
              estimatedTimePct: 100,
              estimatedTimeMs: 1,
            },
          ],
        },
      ],
    },
  });

  assert.equal(artifact.status, "available");
  assert.equal(artifact.anchors.length, 1);
  assert.equal(validateExecutionCorrelationArtifactV1(artifact).ok, true);
});
