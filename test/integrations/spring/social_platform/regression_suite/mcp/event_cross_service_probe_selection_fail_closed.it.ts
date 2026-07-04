import assert from "node:assert/strict";
import test from "node:test";

import { startEventCrossServiceRegressionSuiteFixture } from "@test/integrations/support/spring/social_platform/regression_suite.fixture";

test("mcp IT: execution_orchestration fails closed when strict probe verification omits probeId in a multi-probe workspace", async () => {
  const fixture = await startEventCrossServiceRegressionSuiteFixture({
    projectName: "event-cross-service-probe-selection-fail-closed-project",
    tmpPrefix: "mcp-event-cross-service-probe-selection-fail-closed-it-",
    keepTmpEnvVar: "KEEP_EVENT_CROSS_SERVICE_PROBE_SELECTION_FAIL_CLOSED_TMP",
  });

  try {
    const executionProfile = "multi-probe-selection-fail-closed-run";
    const planName = "producer-trigger-plan";

    await fixture.writeExecutionProfile({
      executionProfile,
      executionPolicy: "stop_on_fail",
      plans: [{ order: 1, planName }],
    });
    await fixture.writeTriggerPlan({
      planName,
      target: "producer",
    });

    const out = await fixture.callExecutionOrchestration(executionProfile);

    assert.equal(out.structuredContent?.resultType, "execution_orchestration");
    assert.equal(out.structuredContent?.status, "blocked");

    const planRuns = out.structuredContent?.planRuns as
      | Array<{
          order: number;
          planName: string;
          status: string;
          runStatus?: string;
          blockedReasonCode?: string;
          blockedReasonMeta?: Record<string, unknown>;
        }>
      | undefined;
    assert.equal(Array.isArray(planRuns), true);
    assert.equal(planRuns?.length, 1);
    assert.equal(planRuns?.[0]?.status, "executed");
    assert.equal(planRuns?.[0]?.runStatus, "blocked");
    assert.equal(planRuns?.[0]?.blockedReasonCode, "probe_wait_for_hit_failed");
    assert.deepEqual(planRuns?.[0]?.blockedReasonMeta, {
      failedStep: "probe_wait_for_hit",
      probeStatus: "probe_selection_failed",
      probeReasonCode: "probe_id_required",
      nextActionCode: "provide_probe_id",
      nextAction: "Provide probeId or baseUrl. Multi-probe profiles require explicit selection.",
    });

    const executionResult = await fixture.readLatestExecutionResult(planName);
    assert.equal(executionResult.status, "blocked");
    assert.equal(executionResult.steps[0]?.status, "blocked_runtime");
    assert.equal(executionResult.steps[0]?.statusCode, 200);
    assert.equal(executionResult.steps[0]?.reasonCode, "probe_wait_for_hit_failed");
    assert.deepEqual(executionResult.steps[0]?.reasonMeta, {
      failedStep: "probe_wait_for_hit",
      probeStatus: "probe_selection_failed",
      probeReasonCode: "probe_id_required",
      nextActionCode: "provide_probe_id",
      nextAction: "Provide probeId or baseUrl. Multi-probe profiles require explicit selection.",
    });
    assert.equal(Array.isArray(executionResult.steps[0]?.assertions), true);
    assert.equal(executionResult.steps[0]?.assertions?.length, 0);
  } finally {
    await fixture.stop();
  }
});
