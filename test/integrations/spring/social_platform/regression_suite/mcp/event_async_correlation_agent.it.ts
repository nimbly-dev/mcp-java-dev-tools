import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import test from "node:test";

import {
  eventListenerSourceFileAbs,
  findLineNumberBySnippet,
  startEventAppWithAgent,
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
  throw new Error("Timed out waiting for async correlation evidence");
}

test("agent IT: generic JDK executor handoff preserves event correlation after consumer entry", async () => {
  const correlationSessionId = "agent-async-correlation-session";
  const correlationExecutionId = "agent-async-correlation-execution";
  const runtime = await startEventAppWithAgent({
    extraJavaArgs: [
      "-Dmcp.correlation.eventKeyPath=$.eventId",
      `-Dmcp.correlation.sessionId=${correlationSessionId}`,
      `-Dmcp.correlation.executionId=${correlationExecutionId}`,
    ],
  });

  try {
    const processorSourceFile = eventListenerSourceFileAbs.replace(
      "listener\\ExampleQueueListener.java",
      "service\\AsyncPropagationProcessor.java",
    );
    const processorLine = await findLineNumberBySnippet(
      processorSourceFile,
      "processingStore.markProcessed(",
    );
    const processorLineKey =
      `com.example.social.event.app.service.AsyncPropagationProcessor#process:${processorLine}`;

    const trigger = await readJson(`${runtime.apiBaseUrl}/api/v1/events/trigger`, {
      method: "POST",
      headers: {
        authorization: "Bearer alice-token",
        "content-type": "application/json",
      },
      body: JSON.stringify({
        context: "entities",
        type: "TriggerIndex",
        groupId: "group-001",
        source: "agent-correlation-fixture",
        dataFormatVersion: 1,
        dataId: "tenant-batch-01",
        data: ["tenant-social-001"],
        notes: "generic-jdk-correlation",
      }),
    });
    const eventId = trigger.eventId;
    assert.equal(typeof eventId, "string");

    const processed = await waitFor(async () => {
      const status = await readJson(`${runtime.apiBaseUrl}/api/v1/events/${eventId}`, {
        headers: { authorization: "Bearer alice-token" },
      });
      return status.status === "processed" && status.processedBy === "async-propagation-processor"
        ? status
        : undefined;
    }, 10_000);
    assert.equal(processed.processedBy, "async-propagation-processor");

    let lastEventsPayload: JsonRecord | undefined;
    const events = await waitFor(async () => {
      const payload = await readJson(
        `${runtime.probeBaseUrl}/__probe/correlation/events?sessionId=${correlationSessionId}&afterSequence=0&limit=100`,
      );
      lastEventsPayload = payload;
      const rawEvents = Array.isArray(payload.events) ? payload.events : [];
      const match = rawEvents.find(
        (event): event is JsonRecord =>
          typeof event === "object" &&
          event !== null &&
          (event as JsonRecord).lineKey === processorLineKey,
      );
      return match ? match : undefined;
    }, 10_000).catch((error) => {
      throw new Error(
        `${String(error)} payload=${JSON.stringify(lastEventsPayload)} logs=${runtime.logs()}`,
      );
    });

    assert.equal(events.eventType, "runtime_line_hit");
    assert.equal(events.correlationSessionId, correlationSessionId);
    assert.equal(events.correlationExecutionId, correlationExecutionId);
    assert.equal(
      events.keyFingerprint,
      `sha256:${createHash("sha256").update(String(eventId), "utf8").digest("hex")}`,
    );
  } finally {
    await runtime.stop();
  }
});
