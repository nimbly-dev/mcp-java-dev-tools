const assert = require("node:assert/strict");
const http = require("node:http");
const test = require("node:test");

const {
  createFailureAnalysisDomain,
  dispatchFailureAnalysisAction,
} = require("@tools-feature-failure-analysis");

async function createSidecarServer() {
  const verifyRequests: Array<Record<string, unknown>> = [];
  const authorizations: string[] = [];
  const server = http.createServer((req: any, res: any) => {
    authorizations.push(String(req.headers.authorization ?? ""));
    let body = "";
    req.on("data", (chunk: string) => {
      body += chunk;
    });
    req.on("end", () => {
      res.setHeader("content-type", "application/json");
      if (req.url === "/__probe/failure/analyze") {
        const request = JSON.parse(body);
        res.end(
          JSON.stringify({
            fingerprint: {
              exceptionType: "com.example.OrderFailure",
              rootCauseType: "java.lang.IllegalStateException",
              normalizedMessage: "Authorization: Bearer very-secret-value",
              complete: request.trace !== "incomplete",
              nearestApplicationFrame: {
                className: "com.example.OrderService",
                methodName: "submit",
              },
            },
            investigationCandidates: [],
            reasons: request.trace === "incomplete" ? ["application_frame_missing"] : [],
          }),
        );
        return;
      }
      const request = JSON.parse(body);
      verifyRequests.push(request);
      if (request.captureId === "missing") {
        res.statusCode = 404;
        res.end(JSON.stringify({ reasonCode: "capture_not_found" }));
        return;
      }
      res.end(
        JSON.stringify({
          outcome: request.captureId === "match" ? "matched" : "different_exception",
          observedFingerprint: { exceptionType: "com.example.OrderFailure" },
          reasons: [],
        }),
      );
    });
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const address = server.address();
  if (!address || typeof address === "string") throw new Error("sidecar address unavailable");
  return { server, baseUrl: `http://127.0.0.1:${address.port}`, verifyRequests, authorizations };
}

test("[UT][failure-analysis] analyze_trace returns a fingerprint without a diagnosis", async () => {
  const { server, baseUrl, authorizations } = await createSidecarServer();
  try {
    const result = await dispatchFailureAnalysisAction(createFailureAnalysisDomain(), {
      action: "analyze_trace",
      input: {
        trace: "com.example.OrderFailure: failed",
        sidecarBaseUrl: baseUrl,
        sidecarAuthorization: "Bearer supplied-observe-token",
      },
    });
    assert.equal(result.structuredContent.outcome, "ANALYZED");
    assert.equal(result.structuredContent.diagnosisClaimed, undefined);
    assert.equal(
      (result.structuredContent.fingerprint as { normalizedMessage?: string }).normalizedMessage,
      "<redacted>",
    );
    assert.equal(authorizations[0], "Bearer supplied-observe-token");
  } finally {
    await new Promise<void>((resolve) => server.close(resolve));
  }
});

test("[UT][failure-analysis] incomplete trace facts cannot start a runtime diagnosis", async () => {
  const { server, baseUrl } = await createSidecarServer();
  try {
    const result = await dispatchFailureAnalysisAction(createFailureAnalysisDomain(), {
      action: "analyze_trace",
      input: {
        trace: "incomplete",
        sidecarBaseUrl: baseUrl,
        investigation: { mode: "guided", attemptLimit: 1, elapsedTimeLimitMs: 1_000 },
      },
    });
    assert.equal(result.structuredContent.outcome, "INCONCLUSIVE");
    assert.equal(result.structuredContent.reasonCode, "failure_fingerprint_incomplete");
    assert.equal(result.structuredContent.diagnosisClaimed, false);
  } finally {
    await new Promise<void>((resolve) => server.close(resolve));
  }
});

test("[UT][failure-analysis] analyze_trace fails closed when the Sidecar is unavailable", async () => {
  const result = await dispatchFailureAnalysisAction(createFailureAnalysisDomain(), {
    action: "analyze_trace",
    input: { trace: "com.example.OrderFailure: failed", sidecarBaseUrl: "http://127.0.0.1:1" },
  });

  assert.equal(result.structuredContent.outcome, "BLOCKED_SIDECAR_UNAVAILABLE");
  assert.equal(result.structuredContent.reasonCode, "sidecar_failure_analysis_unavailable");
  assert.equal(result.structuredContent.diagnosisClaimed, undefined);
});

test("[UT][failure-analysis] verify_reproduction only diagnoses a matching fingerprint", async () => {
  const { server, baseUrl, verifyRequests } = await createSidecarServer();
  try {
    const domain = createFailureAnalysisDomain();
    const commonInput = {
      expectedFingerprint: {
        exceptionType: "com.example.OrderFailure",
        rootCauseType: "java.lang.IllegalStateException",
        nearestApplicationMethodKey: "com.example.OrderService#submit",
      },
      lineHit: { strictLineKey: "com.example.OrderService#submit:42", hitCount: 1 },
      sidecarBaseUrl: baseUrl,
    };
    const reproduced = await dispatchFailureAnalysisAction(domain, {
      action: "verify_reproduction",
      input: { captureId: "match", ...commonInput },
    });
    const notReproduced = await dispatchFailureAnalysisAction(domain, {
      action: "verify_reproduction",
      input: { captureId: "mismatch", ...commonInput },
    });
    assert.equal(reproduced.structuredContent.outcome, "REPRODUCED");
    assert.equal(notReproduced.structuredContent.outcome, "NOT_REPRODUCED");
    assert.equal(notReproduced.structuredContent.diagnosisClaimed, false);
    assert.deepEqual(verifyRequests[0], {
      captureId: "match",
      expectedExceptionType: "com.example.OrderFailure",
      expectedRootCauseType: "java.lang.IllegalStateException",
      expectedNearestApplicationMethodKey: "com.example.OrderService#submit",
    });
    const missing = await dispatchFailureAnalysisAction(domain, {
      action: "verify_reproduction",
      input: { captureId: "missing", ...commonInput },
    });
    assert.equal(missing.structuredContent.outcome, "INCONCLUSIVE");
    assert.equal(missing.structuredContent.reasonCode, "capture_not_found");
    assert.equal(missing.structuredContent.diagnosisClaimed, false);
  } finally {
    await new Promise<void>((resolve) => server.close(resolve));
  }
});

test("[UT][failure-analysis] verify_reproduction reports bounded terminal states without a diagnosis", async () => {
  const result = await dispatchFailureAnalysisAction(createFailureAnalysisDomain(), {
    action: "verify_reproduction",
    input: {
      investigation: { mode: "guided", attemptLimit: 2, elapsedTimeLimitMs: 2_000 },
      terminalState: {
        outcome: "CANCELLED",
        reasonCode: "user_cancelled",
        cleanupStatus: "cleanup_confirmed",
        attemptCount: 1,
      },
    },
  });

  assert.equal(result.structuredContent.outcome, "CANCELLED");
  assert.equal(result.structuredContent.reasonCode, "user_cancelled");
  assert.equal(result.structuredContent.cleanupStatus, "cleanup_confirmed");
  assert.equal(result.structuredContent.diagnosisClaimed, false);
});
