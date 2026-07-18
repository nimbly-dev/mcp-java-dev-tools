import assert from "node:assert/strict";
import test from "node:test";

import { startEventCrossServiceRegressionSuiteFixture } from "@test/integrations/support/spring/social_platform/regression_suite.fixture";

test("mcp IT: readiness 200 does not satisfy a missing consumer expectedFlow stage", async () => {
  const fixture = await startEventCrossServiceRegressionSuiteFixture({
    projectName: "event-cross-service-correlation-missing-flow-project",
    tmpPrefix: "mcp-event-cross-service-correlation-missing-flow-it-",
    keepTmpEnvVar: "KEEP_EVENT_CROSS_SERVICE_CORRELATION_MISSING_FLOW_TMP",
    consumerAgentExclude: "com.example.social.event.consumer.**",
  });

  try {
    const executionProfile = "cross-service-correlation-missing-flow-run";
    const correlationSessionId = "cross-service-correlation-missing-flow";
    const sharedCorrelationKey = "tenant-social-001::group-001::TriggerIndex";
    const expectedFlow = [fixture.producerProbeId, fixture.consumerProbeId];

    await fixture.writeExecutionProfile({
      executionProfile,
      executionPolicy: "continue_on_fail",
      plans: [
        { order: 1, planName: "producer-trigger-plan" },
        { order: 2, planName: "consumer-readiness-plan" },
      ],
    });
    await fixture.writeTriggerPlan({
      planName: "producer-trigger-plan",
      target: "producer",
      probeId: fixture.producerProbeId,
      correlation: {
        correlationSessionId,
        expectedFlow,
        crossPlan: false,
        keyMode: "explicit_message_id",
        explicitKeyValue: sharedCorrelationKey,
      },
    });
    await fixture.writeTriggerPlan({
      planName: "consumer-readiness-plan",
      target: "consumer",
      probeVerification: false,
      includeProbeExpectation: false,
      requestMode: "readiness",
      correlation: {
        correlationSessionId,
        expectedFlow,
        crossPlan: false,
        keyMode: "explicit_message_id",
        explicitKeyValue: sharedCorrelationKey,
      },
    });

    const out = await fixture.callExecutionOrchestration(executionProfile);
    assert.equal(out.structuredContent?.resultType, "execution_orchestration");
    assert.equal(out.structuredContent?.status, "partial_fail");

    const producerExecution = await fixture.readLatestExecutionResult("producer-trigger-plan");
    const consumerExecution = await fixture.readLatestExecutionResult("consumer-readiness-plan");
    const producerCorrelation = await fixture.readLatestCorrelation("producer-trigger-plan");
    const consumerCorrelation = await fixture.readLatestCorrelation("consumer-readiness-plan");

    assert.equal(producerExecution.status, "fail");
    assert.equal(consumerExecution.status, "fail");
    assert.equal(producerCorrelation.status, "fail_closed");
    assert.equal(producerCorrelation.reasonCode, "missing_expected_flow_event");
    assert.deepEqual(producerCorrelation.reasonMeta, {
      expectedFlow,
      observedProbeIds: [fixture.producerProbeId],
      missingProbeIds: [fixture.consumerProbeId],
      firstUnsatisfiedFlowIndex: 1,
    });
    assert.equal(consumerCorrelation.status, "fail_closed");

    const readiness = await fixture.callProbe("status", {
      key: fixture.consumerStrictProbeKey,
      probeId: fixture.consumerProbeId,
    });
    const response = readiness.structuredContent?.response as Record<string, unknown>;
    assert.equal(response.status, 200);
    const consumerProbeJson = (response.json ?? {}) as Record<string, unknown>;
    assert.equal(consumerProbeJson.hitCount, 0);
  } finally {
    await fixture.stop();
  }
});
