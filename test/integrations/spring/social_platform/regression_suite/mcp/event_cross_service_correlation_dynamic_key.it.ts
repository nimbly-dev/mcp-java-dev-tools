import assert from "node:assert/strict";
import test from "node:test";

import { startEventCrossServiceRegressionSuiteFixture } from "@test/integrations/support/spring/social_platform/regression_suite.fixture";

test("mcp IT: execution_orchestration reuses a prior plan response-derived correlation key across plans", async () => {
  const fixture = await startEventCrossServiceRegressionSuiteFixture({
    projectName: "event-cross-service-correlation-dynamic-key-project",
    tmpPrefix: "mcp-event-cross-service-correlation-dynamic-key-it-",
    keepTmpEnvVar: "KEEP_EVENT_CROSS_SERVICE_CORRELATION_DYNAMIC_KEY_TMP",
  });

  try {
    const executionProfile = "cross-service-correlation-dynamic-key-run";
    const correlationSessionId = "cross-service-correlation-dynamic-key";
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
        keyMode: "response_body_id",
        responseJsonPath: "response.bodyJson.eventId",
      },
    });
    await fixture.writeTriggerPlan({
      planName: "consumer-listener-plan",
      target: "consumer",
      probeId: fixture.consumerProbeId,
      correlation: {
        correlationSessionId,
        expectedFlow,
        keyMode: "suite_session_message_id",
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

    assert.equal(producerExecution.status, "pass");
    assert.equal(consumerExecution.status, "pass");
    assert.equal(producerExecution.steps[0]?.assertions?.[2]?.actual, true);
    assert.equal(consumerExecution.steps[0]?.assertions?.[2]?.actual, true);

    const producerCorrelationPolicy = (producerEvidence.correlationPolicy ?? {}) as Record<string, unknown>;
    const consumerCorrelationPolicy = (consumerEvidence.correlationPolicy ?? {}) as Record<string, unknown>;
    const producerEvents = (producerEvidence.correlationEvents ?? []) as Array<Record<string, unknown>>;
    const consumerEvents = (consumerEvidence.correlationEvents ?? []) as Array<Record<string, unknown>>;

    assert.equal(producerCorrelationPolicy.correlationSessionId, correlationSessionId);
    assert.equal(consumerCorrelationPolicy.correlationSessionId, correlationSessionId);
    assert.equal(producerCorrelationPolicy.keySourceType, "json_path");
    assert.equal(producerCorrelationPolicy.keySourcePath, "response.bodyJson.eventId");
    assert.equal(typeof producerCorrelationPolicy.keyExtractionReasonCode, "undefined");
    assert.equal(typeof consumerCorrelationPolicy.keySourceType, "undefined");
    assert.equal(typeof consumerCorrelationPolicy.keyExtractionReasonCode, "undefined");

    const producerKeyValue = producerCorrelationPolicy.keyValue;
    assert.equal(typeof producerKeyValue, "string");
    assert.equal((producerKeyValue as string).startsWith("evt-"), true);
    assert.equal(producerEvents.length, 1);
    assert.equal(consumerEvents.length, 1);
    assert.equal(producerEvents[0]?.keyValue, producerKeyValue);
    assert.equal(consumerEvents[0]?.keyValue, producerKeyValue);

    assert.equal(producerCorrelation.status, "ok");
    assert.equal(consumerCorrelation.status, "ok");
    assert.equal(producerCorrelation.keyValue, producerKeyValue);
    assert.equal(consumerCorrelation.keyValue, producerKeyValue);
  } finally {
    await fixture.stop();
  }
});
