import assert from "node:assert/strict";
import test from "node:test";

import { startEventCrossServiceRegressionSuiteFixture } from "@test/integrations/support/spring/social_platform/regression_suite.fixture";

type CaseOptions = {
  notes?: string;
  maxWindowMs?: number;
  correlationProbeIds?: string[];
  runtimeProbeIds?: string[];
  expectedReason?: string;
};

async function runCase(name: string, options: CaseOptions = {}) {
  const fixture = await startEventCrossServiceRegressionSuiteFixture({
    projectName: `event-async-flow-${name}-project`,
    tmpPrefix: `mcp-event-async-flow-${name}-it-`,
    keepTmpEnvVar: "KEEP_EVENT_ASYNC_FLOW_TMP",
    ...(name === "missing-consumer" ? { producerExtraJavaArgs: ["-Dfixture.consumer.forward-enabled=false"] } : {}),
  });
  try {
    const executionProfile = `event-async-flow-${name}-run`;
    const correlationSessionId = `event-async-flow-${name}`;
    await fixture.writeExecutionProfile({
      executionProfile,
      executionPolicy: "stop_on_fail",
      plans: [{ order: 1, planName: "producer-trigger-plan" }],
    });
    await fixture.writeTriggerPlan({
      planName: "producer-trigger-plan",
      target: "producer",
      probeId: fixture.producerProbeId,
      ...(options.notes !== undefined ? { notes: options.notes } : {}),
      ...(options.maxWindowMs !== undefined ? { maxWindowMs: options.maxWindowMs } : {}),
      correlation: {
        correlationSessionId,
        expectedFlow: [fixture.producerProbeId, fixture.consumerProbeId],
        crossPlan: false,
        keyMode: "response_body_id",
        responseJsonPath: "response.bodyJson.eventId",
        probeIds: options.correlationProbeIds ?? [fixture.producerProbeId, fixture.consumerProbeId],
        runtimeEvidence: {
          required: true,
          probeIds: options.runtimeProbeIds ?? [fixture.producerProbeId, fixture.consumerProbeId],
          eventKeyPath: "$.eventId",
          pageLimit: 64,
          maxEvents: 256,
          maxBytes: 262_144,
          maxDurationMs: 5_000,
        },
      },
    });

    const output = await fixture.callExecutionOrchestration(executionProfile);
    const execution = await fixture.readLatestExecutionResult("producer-trigger-plan");
    const evidence = await fixture.readLatestEvidence("producer-trigger-plan");
    const correlation = await fixture.readLatestCorrelation("producer-trigger-plan");
    const policy = (evidence.correlationPolicy ?? {}) as Record<string, unknown>;
    assert.equal(policy.correlationSessionId, correlationSessionId);
    assert.deepEqual(policy.expectedFlow, [fixture.producerProbeId, fixture.consumerProbeId]);
    assert.equal(typeof correlation.timeline, "object");
    if (options.expectedReason) {
      assert.equal(correlation.status, "fail_closed", JSON.stringify({ output, execution, correlation }, null, 2));
      assert.equal(correlation.reasonCode, options.expectedReason);
    } else {
      assert.equal(output.structuredContent?.status, "pass", JSON.stringify({ output, execution, correlation }, null, 2));
      assert.equal(execution.status, "pass");
      assert.equal(correlation.status, "ok");
      const timeline = correlation.timeline as Array<Record<string, unknown>>;
      assert.ok(timeline.some((event) => typeof event.keyFingerprint === "string"));
      assert.ok(timeline.some((event) => event.probeId === fixture.producerProbeId));
      assert.ok(timeline.some((event) => event.probeId === fixture.consumerProbeId));
      assert.ok(timeline.some((event) => event.eventType === "runtime_line_hit" && event.probeId === fixture.consumerProbeId));
    }
  } finally {
    await fixture.stop();
  }
}

test("mcp IT: generic JDK async handoff propagates the HTTP eventId through the consumer event object", async () => {
  await runCase("happy");
});

test("mcp IT: wrong consumer eventId fails closed as correlation_key_not_observed", async () => {
  await runCase("wrong-key", { notes: "fixture-consumer-event-id:evt-wrong" , expectedReason: "correlation_key_not_observed" });
});

test("mcp IT: missing consumer event fails closed as missing_expected_flow_event", async () => {
  await runCase("missing-consumer", { expectedReason: "missing_expected_flow_event" });
});

test("mcp IT: runtime evidence outside the configured Probe scope fails closed", async () => {
  await runCase("probe-scope", { correlationProbeIds: ["event-producer-app"], expectedReason: "correlation_probe_scope_mismatch" });
});

test("mcp IT: delayed async processing fails closed after the correlation window", async () => {
  await runCase("window", { notes: "fixture-listener-delay-ms:1000", maxWindowMs: 100, expectedReason: "window_exceeded" });
});
