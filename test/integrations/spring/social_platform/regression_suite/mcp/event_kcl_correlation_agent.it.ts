import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import test from "node:test";

import {
  findLineNumberBySnippet,
  kclProcessorFqcn,
  kclProcessorSourceFileAbs,
  startEventConsumerAppWithAgent,
} from "@test/integrations/support/spring/social_platform/shared.fixture";

type JsonRecord = Record<string, unknown>;

async function readJson(url: string, init?: RequestInit): Promise<JsonRecord> {
  const response = await fetch(url, init);
  const body = (await response.json()) as unknown;
  assert.equal(response.ok, true, `${response.status} ${JSON.stringify(body)}`);
  assert.equal(typeof body, "object");
  assert.notEqual(body, null);
  return body as JsonRecord;
}

async function waitFor<T>(read: () => Promise<T | undefined>, timeoutMs: number): Promise<T> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const value = await read();
    if (value !== undefined) return value;
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw new Error("Timed out waiting for KCL correlation evidence");
}

function fingerprint(value: string): string {
  return `sha256:${createHash("sha256").update(value, "utf8").digest("hex")}`;
}

test("agent IT: KCL processRecords binds a consistent partition key and rejects mixed batches", async () => {
  const correlationSessionId = "kcl-agent-correlation-session";
  const correlationExecutionId = "kcl-agent-correlation-execution";
  const partitionKey = "tenant-kcl-001";
  const runtime = await startEventConsumerAppWithAgent({
    agentInclude: "com.example.social.event.consumer.app.**",
  });

  try {
    await readJson(`${runtime.probeBaseUrl}/__probe/correlation/configure`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        sessionId: correlationSessionId,
        executionId: correlationExecutionId,
        eventKeyPath: "$.kcl.partitionKey",
        leaseTtlMs: 60_000,
      }),
    });

    const processorLine = await findLineNumberBySnippet(
      kclProcessorSourceFileAbs,
      "processingStore.markProcessed(",
    );
    const processorLineKey = `${kclProcessorFqcn}#processRecords:${processorLine}`;
    const eventId = "kcl-event-consistent";

    await readJson(`${runtime.apiBaseUrl}/internal/events/kcl`, {
      method: "POST",
      headers: {
        authorization: "Bearer alice-token",
        "content-type": "application/json",
      },
      body: JSON.stringify({ records: [{ eventId, partitionKey }] }),
    });

    const processed = await waitFor(async () => {
      const status = await readJson(`${runtime.apiBaseUrl}/internal/events/${eventId}`, {
        headers: { authorization: "Bearer alice-token" },
      });
      return status.status === "processed" ? status : undefined;
    }, 10_000);
    assert.equal(processed.status, "processed");

    const events = await waitFor(async () => {
      const payload = await readJson(
        `${runtime.probeBaseUrl}/__probe/correlation/events?sessionId=${correlationSessionId}&afterSequence=0&limit=100`,
      );
      const rawEvents = Array.isArray(payload.events) ? payload.events : [];
      return rawEvents.find(
        (event): event is JsonRecord =>
          typeof event === "object" &&
          event !== null &&
          (event as JsonRecord).lineKey === processorLineKey,
      );
    }, 10_000);

    assert.equal(events.correlationSessionId, correlationSessionId);
    assert.equal(events.correlationExecutionId, correlationExecutionId);
    assert.equal(events.keyType, "messageId");
    assert.equal(events.keyFingerprint, fingerprint(partitionKey));
    const boundStatus = await readJson(
      `${runtime.probeBaseUrl}/__probe/correlation/status?sessionId=${correlationSessionId}`,
    );
    assert.equal(boundStatus.outcome, "bound");
    assert.equal(boundStatus.reasonCode, "ok");

    const mixedEventA = "kcl-event-mixed-a";
    const mixedEventB = "kcl-event-mixed-b";
    await readJson(`${runtime.apiBaseUrl}/internal/events/kcl`, {
      method: "POST",
      headers: {
        authorization: "Bearer alice-token",
        "content-type": "application/json",
      },
      body: JSON.stringify({
        records: [
          { eventId: mixedEventA, partitionKey: "tenant-kcl-a" },
          { eventId: mixedEventB, partitionKey: "tenant-kcl-b" },
        ],
      }),
    });

    await waitFor(async () => {
      const status = await readJson(`${runtime.apiBaseUrl}/internal/events/${mixedEventB}`, {
        headers: { authorization: "Bearer alice-token" },
      });
      return status.status === "processed" ? status : undefined;
    }, 10_000);

    const afterMixed = await readJson(
      `${runtime.probeBaseUrl}/__probe/correlation/events?sessionId=${correlationSessionId}&afterSequence=0&limit=100`,
    );
    const mixedFingerprintEvents = (
      Array.isArray(afterMixed.events) ? afterMixed.events : []
    ).filter(
      (event): event is JsonRecord =>
        typeof event === "object" &&
        event !== null &&
        ((event as JsonRecord).keyFingerprint === fingerprint("tenant-kcl-a") ||
          (event as JsonRecord).keyFingerprint === fingerprint("tenant-kcl-b")),
    );
    assert.equal(mixedFingerprintEvents.length, 0);
    const mixedStatus = await readJson(
      `${runtime.probeBaseUrl}/__probe/correlation/status?sessionId=${correlationSessionId}`,
    );
    assert.equal(mixedStatus.outcome, "refused");
    assert.equal(mixedStatus.reasonCode, "kcl_mixed_partition_keys");

    const failedResponse = await fetch(`${runtime.apiBaseUrl}/internal/events/kcl`, {
      method: "POST",
      headers: {
        authorization: "Bearer alice-token",
        "content-type": "application/json",
      },
      body: JSON.stringify({
        records: [{ eventId: "kcl-event-fail", partitionKey: "tenant-kcl-failure" }],
      }),
    });
    assert.equal(failedResponse.status, 500);
    await waitFor(async () => {
      const status = await readJson(`${runtime.apiBaseUrl}/internal/events/kcl-after-failure`, {
        headers: { authorization: "Bearer alice-token" },
      });
      return status.status === "processed" ? status : undefined;
    }, 10_000);
    const afterFailure = await readJson(
      `${runtime.probeBaseUrl}/__probe/correlation/events?sessionId=${correlationSessionId}&afterSequence=0&limit=100`,
    );
    const failureFingerprintEvents = (
      Array.isArray(afterFailure.events) ? afterFailure.events : []
    ).filter((event): event is JsonRecord => {
      if (typeof event !== "object" || event === null) return false;
      const candidate = event as JsonRecord;
      return (
        typeof candidate.lineKey === "string" &&
        candidate.lineKey.includes("KclInProcessFixtureService#publish") &&
        candidate.keyFingerprint === fingerprint("tenant-kcl-failure")
      );
    });
    assert.equal(failureFingerprintEvents.length, 0);
  } finally {
    await runtime.stop();
  }
});
