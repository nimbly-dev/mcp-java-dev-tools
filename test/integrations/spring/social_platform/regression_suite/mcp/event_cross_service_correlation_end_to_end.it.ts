import assert from "node:assert/strict";
import test from "node:test";

import { startEventCrossServiceRegressionSuiteFixture } from "@test/integrations/support/spring/social_platform/regression_suite.fixture";

test("mcp IT: cross-service event suite preserves probe verification and correlation artifacts end-to-end with independent Probe confirmation", async () => {
  const fixture = await startEventCrossServiceRegressionSuiteFixture({
    projectName: "event-cross-service-correlation-end-to-end-project",
    tmpPrefix: "mcp-event-cross-service-correlation-end-to-end-it-",
    keepTmpEnvVar: "KEEP_EVENT_CROSS_SERVICE_CORRELATION_END_TO_END_TMP",
  });

  try {
    const executionProfile = "cross-service-correlation-end-to-end-run";
    const correlationSessionId = "cross-service-correlation-e2e";
    const sharedCorrelationKey = "tenant-social-001::group-001::TriggerIndex";
    const expectedFlow = [fixture.producerProbeId, fixture.consumerProbeId];

    await fixture.writeExecutionProfile({
      executionProfile,
      executionPolicy: "stop_on_fail",
      plans: [
        { order: 1, planName: "producer-trigger-plan" },
        { order: 2, planName: "consumer-listener-plan" },
      ],
    });
    await fixture.writeTriggerPlan({
      planName: "producer-trigger-plan",
      target: "producer",
      probeId: fixture.producerProbeId,
      correlation: {
        correlationSessionId,
        expectedFlow,
        keyMode: "explicit_message_id",
        explicitKeyValue: sharedCorrelationKey,
      },
    });
    await fixture.writeTriggerPlan({
      planName: "consumer-listener-plan",
      target: "consumer",
      probeId: fixture.consumerProbeId,
      correlation: {
        correlationSessionId,
        expectedFlow,
        keyMode: "explicit_message_id",
        explicitKeyValue: sharedCorrelationKey,
      },
    });

    const out = await fixture.callExecutionOrchestration(executionProfile);
    assert.equal(out.structuredContent?.resultType, "execution_orchestration");
    assert.equal(out.structuredContent?.status, "pass");

    const producerExecution = await fixture.readLatestExecutionResult("producer-trigger-plan");
    const consumerExecution = await fixture.readLatestExecutionResult("consumer-listener-plan");
    const producerEvidence = await fixture.readLatestEvidence("producer-trigger-plan");
    const consumerEvidence = await fixture.readLatestEvidence("consumer-listener-plan");
    const producerCorrelation = await fixture.readLatestCorrelation("producer-trigger-plan");
    const consumerCorrelation = await fixture.readLatestCorrelation("consumer-listener-plan");
    const producerCorrelationPolicy = (producerEvidence.correlationPolicy ?? {}) as Record<string, unknown>;
    const consumerCorrelationPolicy = (consumerEvidence.correlationPolicy ?? {}) as Record<string, unknown>;

    assert.equal(producerExecution.status, "pass");
    assert.equal(consumerExecution.status, "pass");
    assert.equal(producerExecution.steps[0]?.assertions?.[2]?.actualPath, "probe.hit");
    assert.equal(producerExecution.steps[0]?.assertions?.[2]?.actual, true);
    assert.equal(consumerExecution.steps[0]?.assertions?.[2]?.actualPath, "probe.hit");
    assert.equal(consumerExecution.steps[0]?.assertions?.[2]?.actual, true);

    const producerEvents = (producerEvidence.correlationEvents ?? []) as Array<Record<string, unknown>>;
    const consumerEvents = (consumerEvidence.correlationEvents ?? []) as Array<Record<string, unknown>>;
    assert.equal(producerCorrelationPolicy.correlationSessionId, correlationSessionId);
    assert.equal(consumerCorrelationPolicy.correlationSessionId, correlationSessionId);
    assert.equal(typeof producerCorrelationPolicy.keySourceType, "undefined");
    assert.equal(typeof consumerCorrelationPolicy.keySourceType, "undefined");
    assert.equal(producerEvents.length, 1);
    assert.equal(consumerEvents.length, 1);
    assert.equal(producerEvents[0]?.probeId, fixture.producerProbeId);
    assert.equal(consumerEvents[0]?.probeId, fixture.consumerProbeId);
    assert.equal(producerEvents[0]?.keyValue, sharedCorrelationKey);
    assert.equal(consumerEvents[0]?.keyValue, sharedCorrelationKey);

    assert.equal(producerCorrelation.correlationSessionId, correlationSessionId);
    assert.equal(consumerCorrelation.correlationSessionId, correlationSessionId);
    assert.equal(producerCorrelation.status, "ok");
    assert.equal(consumerCorrelation.status, "ok");

    const producerStatus = await fixture.callProbe("status", {
      key: fixture.producerStrictProbeKey,
      probeId: fixture.producerProbeId,
    });
    const consumerStatus = await fixture.callProbe("status", {
      key: fixture.consumerStrictProbeKey,
      probeId: fixture.consumerProbeId,
    });

    const producerProbeJson = ((producerStatus.structuredContent?.response as Record<string, unknown>)?.json ??
      {}) as Record<string, unknown>;
    const consumerProbeJson = ((consumerStatus.structuredContent?.response as Record<string, unknown>)?.json ??
      {}) as Record<string, unknown>;

    assert.equal(producerProbeJson.lineValidation, "resolvable");
    assert.equal(consumerProbeJson.lineValidation, "resolvable");
    assert.equal(typeof producerProbeJson.hitCount, "number");
    assert.equal(typeof consumerProbeJson.hitCount, "number");
    assert.equal((producerProbeJson.hitCount as number) >= 1, true);
    assert.equal((consumerProbeJson.hitCount as number) >= 1, true);

    const producerCapturePreview = (producerProbeJson.capturePreview ?? {}) as Record<string, unknown>;
    const consumerCapturePreview = (consumerProbeJson.capturePreview ?? {}) as Record<string, unknown>;
    assert.equal(producerCapturePreview.available, true);
    assert.equal(consumerCapturePreview.available, true);
  } finally {
    await fixture.stop();
  }
});
