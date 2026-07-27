import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import test from "node:test";

import {
  exactConventionConsumerFqcn,
  exactConventionConsumerSourceFileAbs,
  findLineNumberBySnippet,
  legacyConventionConsumerFqcn,
  legacyConventionConsumerSourceFileAbs,
  startEventConsumerAppWithAgent,
} from "@test/support/spring/social-platform/shared.fixture";

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
  throw new Error("Timed out waiting for convention consumer correlation evidence");
}

function fingerprint(value: string): string {
  return `sha256:${createHash("sha256").update(value, "utf8").digest("hex")}`;
}

async function invokeConsumer(args: {
  apiBaseUrl: string;
  consumer: "exact" | "exact-overload" | "legacy";
  eventId: string;
}): Promise<void> {
  const response = await fetch(`${args.apiBaseUrl}/internal/events/convention/${args.consumer}`, {
    method: "POST",
    headers: {
      authorization: "Bearer alice-token",
      "content-type": "application/json",
      "X-Event-Id": args.eventId,
      "X-Accepted-By": "consumer-boundary-it",
    },
    body: JSON.stringify({
      context: "entities",
      type: "ConventionConsumer",
      groupId: "group-001",
      source: "consumer-boundary-it",
      dataFormatVersion: 1,
      dataId: args.eventId,
      data: ["tenant-consumer-boundary"],
      notes: "direct convention consumer invocation",
    }),
  });
  const responseBody = await response.text();
  assert.equal(response.status, 202, responseBody);
}

async function assertCorrelatedLineHit(args: {
  probeBaseUrl: string;
  sessionId: string;
  executionId: string;
  lineKey: string;
  eventId: string;
}): Promise<void> {
  const event = await waitFor(async () => {
    const payload = await readJson(
      `${args.probeBaseUrl}/__probe/correlation/events?sessionId=${args.sessionId}&afterSequence=0&limit=100`,
    );
    const rawEvents = Array.isArray(payload.events) ? payload.events : [];
    return rawEvents.find(
      (candidate): candidate is JsonRecord =>
        typeof candidate === "object" &&
        candidate !== null &&
        (candidate as JsonRecord).eventType === "runtime_line_hit" &&
        (candidate as JsonRecord).lineKey === args.lineKey,
    );
  }, 10_000);

  assert.equal(event.correlationSessionId, args.sessionId);
  assert.equal(event.correlationExecutionId, args.executionId);
  assert.equal(event.keyFingerprint, fingerprint(args.eventId));
}

test("[IT][java-agent][probe] exact unannotated receive(Event) boundary and legacy receiveEvent(Event) both correlate", async () => {
  const runtime = await startEventConsumerAppWithAgent({
    agentInclude: "com.example.social.event.consumer.app.**",
  });

  try {
    const eventType = "com.example.social.event.consumer.app.model.IndexRequestedEvent";
    const exactLine = await findLineNumberBySnippet(
      exactConventionConsumerSourceFileAbs,
      "processingStore.markProcessed(",
    );
    const legacyLine = await findLineNumberBySnippet(
      legacyConventionConsumerSourceFileAbs,
      "processingStore.markProcessed(",
    );

    const exactSessionId = "consumer-boundary-exact-session";
    const exactExecutionId = "consumer-boundary-exact-execution";
    await readJson(`${runtime.probeBaseUrl}/__probe/correlation/configure`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        sessionId: exactSessionId,
        executionId: exactExecutionId,
        eventKeyPath: "$.eventId",
        leaseTtlMs: 60_000,
        consumerBoundaries: [
          {
            id: "exact-convention-receive",
            fqcn: exactConventionConsumerFqcn,
            method: "receive",
            parameterTypes: [eventType],
            eventArgumentIndex: 0,
          },
        ],
      }),
    });

    const exactEventId = "consumer-boundary-exact-event";
    await invokeConsumer({
      apiBaseUrl: runtime.apiBaseUrl,
      consumer: "exact",
      eventId: exactEventId,
    });
    await assertCorrelatedLineHit({
      probeBaseUrl: runtime.probeBaseUrl,
      sessionId: exactSessionId,
      executionId: exactExecutionId,
      lineKey: `${exactConventionConsumerFqcn}#receive:${exactLine}`,
      eventId: exactEventId,
    });

    await readJson(`${runtime.probeBaseUrl}/__probe/correlation/configure`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        sessionId: exactSessionId,
        executionId: exactExecutionId,
        eventKeyPath: "$.eventId",
        leaseTtlMs: 60_000,
        consumerBoundaries: [
          {
            id: "exact-convention-receive-overload",
            fqcn: exactConventionConsumerFqcn,
            method: "receive",
            parameterTypes: ["java.lang.String", eventType],
            eventArgumentIndex: 1,
          },
        ],
      }),
    });

    const exactOverloadLine = await findLineNumberBySnippet(
      exactConventionConsumerSourceFileAbs,
      "// exact overload boundary line",
    );
    const exactOverloadEventId = "consumer-boundary-exact-overload-event";
    await invokeConsumer({
      apiBaseUrl: runtime.apiBaseUrl,
      consumer: "exact-overload",
      eventId: exactOverloadEventId,
    });
    await assertCorrelatedLineHit({
      probeBaseUrl: runtime.probeBaseUrl,
      sessionId: exactSessionId,
      executionId: exactExecutionId,
      lineKey: `${exactConventionConsumerFqcn}#receive:${exactOverloadLine}`,
      eventId: exactOverloadEventId,
    });

    await readJson(`${runtime.probeBaseUrl}/__probe/correlation/configure`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        sessionId: exactSessionId,
        executionId: exactExecutionId,
        release: true,
      }),
    });

    const legacySessionId = "consumer-boundary-legacy-session";
    const legacyExecutionId = "consumer-boundary-legacy-execution";
    await readJson(`${runtime.probeBaseUrl}/__probe/correlation/configure`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        sessionId: legacySessionId,
        executionId: legacyExecutionId,
        eventKeyPath: "$.eventId",
        leaseTtlMs: 60_000,
      }),
    });

    const legacyEventId = "consumer-boundary-legacy-event";
    await invokeConsumer({
      apiBaseUrl: runtime.apiBaseUrl,
      consumer: "legacy",
      eventId: legacyEventId,
    });
    await assertCorrelatedLineHit({
      probeBaseUrl: runtime.probeBaseUrl,
      sessionId: legacySessionId,
      executionId: legacyExecutionId,
      lineKey: `${legacyConventionConsumerFqcn}#receiveEvent:${legacyLine}`,
      eventId: legacyEventId,
    });
  } finally {
    await runtime.stop();
  }
});
