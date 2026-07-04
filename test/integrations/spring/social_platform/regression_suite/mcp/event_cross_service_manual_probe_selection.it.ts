import assert from "node:assert/strict";
import test from "node:test";

import { startEventCrossServiceRegressionSuiteFixture } from "@test/integrations/support/spring/social_platform/regression_suite.fixture";

test("mcp IT: manual strict probe verification in a multi-probe workspace requires explicit probeId and confirms the trigger line hit", async () => {
  const fixture = await startEventCrossServiceRegressionSuiteFixture({
    projectName: "event-cross-service-manual-probe-selection-project",
    tmpPrefix: "mcp-event-cross-service-manual-probe-selection-it-",
    keepTmpEnvVar: "KEEP_EVENT_CROSS_SERVICE_MANUAL_PROBE_SELECTION_TMP",
  });

  try {
    const ambiguousStatus = await fixture.callProbe("status", {
      key: fixture.producerStrictProbeKey,
    });
    assert.equal(ambiguousStatus.structuredContent?.status, "probe_selection_failed");
    assert.equal(ambiguousStatus.structuredContent?.reasonCode, "probe_id_required");

    const reset = await fixture.callProbe("reset", {
      key: fixture.producerStrictProbeKey,
      probeId: fixture.producerProbeId,
    });
    assert.equal(typeof reset.structuredContent, "object");

    const response = await fixture.executeProducerTriggerRequest();
    assert.equal(response.status, 200, response.bodyText);
    assert.equal(response.bodyText.includes("evt-"), true, response.bodyText);

    const waited = await fixture.callProbe("wait_for_hit", {
      key: fixture.producerStrictProbeKey,
      probeId: fixture.producerProbeId,
      timeoutMs: 10_000,
      pollIntervalMs: 250,
      maxRetries: 2,
    });
    assert.equal((waited.structuredContent?.result as Record<string, unknown>)?.hit, true);

    const explicitStatus = await fixture.callProbe("status", {
      key: fixture.producerStrictProbeKey,
      probeId: fixture.producerProbeId,
    });
    const json = ((explicitStatus.structuredContent?.response as Record<string, unknown>)?.json ??
      {}) as Record<string, unknown>;
    assert.equal(json.lineValidation, "resolvable");
    assert.equal(typeof json.hitCount, "number");
    assert.equal((json.hitCount as number) >= 1, true);
    const capturePreview = (json.capturePreview ?? {}) as Record<string, unknown>;
    assert.equal(capturePreview.available, true);
  } finally {
    await fixture.stop();
  }
});
