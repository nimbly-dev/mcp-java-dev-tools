const assert = require("node:assert/strict");
const test = require("node:test");

const {
  probeReset,
  probeStatus,
  probeWaitHit,
  probeCaptureGet,
  probeActuate,
  probeProfiler,
  createProbeDomain,
} = require("@tools-feature-probe/domain");
const { LAST_RESET_EPOCH_BY_KEY } = require("@/utils/probe/constants.util");

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8" },
  });
}

async function withMockedFetch(
  mockFetch: typeof globalThis.fetch,
  fn: () => Promise<void>,
): Promise<void> {
  const originalFetch = globalThis.fetch;
  if (!originalFetch) throw new Error("global fetch is unavailable in this Node runtime");
  globalThis.fetch = mockFetch;
  try {
    await fn();
  } finally {
    globalThis.fetch = originalFetch;
  }
}

function parseProbeText(result: { content: Array<{ text: string }> }): Record<string, unknown> {
  assert.equal(Array.isArray(result.content), true);
  assert.equal(result.content.length > 0, true);
  const first = result.content[0];
  if (!first) throw new Error("missing text content");
  return JSON.parse(first.text);
}

test("probe_get_status returns invalid_line_target for unresolved runtime line", async () => {
  let calls = 0;
  await withMockedFetch(async () => {
    calls += 1;
    return jsonResponse(200, {
      key: "com.example.social.post.app.controller.PostController#updatePost:122",
      hitCount: 0,
      lastHitEpoch: 0,
      mode: "observe",
      lineResolvable: false,
      lineValidation: "invalid_line_target",
    });
  }, async () => {
    const out = await probeStatus({
      key: "com.example.social.post.app.controller.PostController#updatePost:122",
      baseUrl: "http://127.0.0.1:9191",
      statusPath: "/__probe/status",
    });
    const parsed = parseProbeText(out);
    assert.equal(parsed.reproStatus, "invalid_line_target");
    assert.equal(parsed.executionHit, "not_hit");
    assert.match(parsed.probeHit, /cannot be resolved to executable bytecode/i);
    assert.equal(parsed.actionCode, "runtime_not_aligned");
    assert.equal(parsed.nextAction, "rebuild_app_artifact_and_restart_jvm_then_rerun_probe");
    assert.equal((parsed as any).httpResponse, undefined);
    assert.equal((parsed as any).requestDetails?.headers, undefined);
    assert.equal((parsed as any).requestDetails?.body, undefined);
    assert.equal(out.structuredContent.response.json.lineValidation, "invalid_line_target");
    assert.equal(out.structuredContent.result.reasonCode, "invalid_line_target");
    assert.equal(out.structuredContent.result.actionCode, "runtime_not_aligned");
    assert.equal(out.structuredContent.result.nextActionCode, "align_runtime_and_artifact");
  });
  assert.equal(calls, 1);
});

test("probe_get_status supports 0.1.0 nested envelope", async () => {
  await withMockedFetch(async () => {
    return jsonResponse(200, {
      contractVersion: "0.1.0",
      probe: {
        key: "com.example.social.post.app.controller.PostController#updatePost:122",
        hitCount: 1,
        lastHitEpoch: 1234,
        lineResolvable: true,
        lineValidation: "resolvable",
      },
      capturePreview: {
        available: true,
        captureId: "abc123",
        capturedAtEpoch: 5555,
        executionStartedAtEpoch: 5500,
        executionEndedAtEpoch: 5555,
        executionDurationMs: 55,
        threadAllocatedBytesDelta: 2048,
            executionPaths: [
              "CatalogController.listCatalogShoes()#42",
              "CatalogService.listCatalogShoes()#101",
            ],
          },
      runtime: {
        mode: "observe",
        actuatorId: "",
        actuateTargetKey: "",
        actuateReturnBoolean: false,
        serverEpoch: 7777,
        applicationType: {
          value: "spring-boot",
          source: "classpath:org.springframework.boot.SpringApplication",
          confidence: 0.9,
        },
        appPort: {
          value: 8082,
          source: "system_property:server.port",
          confidence: 0.95,
        },
      },
    });
  }, async () => {
    const out = await probeStatus({
      key: "com.example.social.post.app.controller.PostController#updatePost:122",
      baseUrl: "http://127.0.0.1:9191",
      statusPath: "/__probe/status",
    });
    const parsed = parseProbeText(out);
    assert.equal(parsed.reproStatus, "status_checked");
    assert.equal(parsed.executionHit, "line_hit");
    assert.equal((parsed as any).httpResponse, undefined);
    assert.equal((parsed as any).requestDetails?.headers, undefined);
    assert.equal((parsed as any).requestDetails?.body, undefined);
    assert.equal(out.structuredContent.response.json.contractVersion, "0.1.0");
    assert.equal(out.structuredContent.response.json.capturePreview.captureId, "abc123");
    assert.equal(out.structuredContent.response.json.capturePreview.capturedAtEpoch, 5555);
    assert.equal(out.structuredContent.response.json.capturePreview.executionStartedAtEpoch, 5500);
    assert.equal(out.structuredContent.response.json.capturePreview.executionEndedAtEpoch, 5555);
    assert.equal(out.structuredContent.response.json.capturePreview.executionDurationMs, 55);
    assert.equal(out.structuredContent.response.json.capturePreview.threadAllocatedBytesDelta, 2048);
    assert.equal(out.structuredContent.response.json.capturePreview.executionPaths, undefined);
    assert.equal(out.structuredContent.response.json.runtime.applicationType, undefined);
    assert.equal(out.structuredContent.response.json.runtime.serverEpoch, undefined);
    assert.equal(out.structuredContent.response.json.runtime.serverMs, undefined);
    assert.equal(out.structuredContent.response.json.runtime.appPort.value, 8082);
    assert.equal(out.structuredContent.response.json.runtime.appPort.confidence, undefined);
    assert.equal(
      out.structuredContent.request.key,
      "com.example.social.post.app.controller.PostController#updatePost:122",
    );
    assert.equal(out.structuredContent.request.resolvedKey, undefined);
  });
});

test("probe_reset returns invalid_line_target semantics when runtime line is unresolved", async () => {
  await withMockedFetch(async () => {
    return jsonResponse(200, {
      ok: true,
      key: "com.example.social.post.app.controller.PostController#updatePost:122",
      lineResolvable: false,
      lineValidation: "invalid_line_target",
    });
  }, async () => {
    const out = await probeReset({
      key: "com.example.social.post.app.controller.PostController#updatePost:122",
      baseUrl: "http://127.0.0.1:9191",
      resetPath: "/__probe/reset",
    });
    const parsed = parseProbeText(out);
    assert.equal(parsed.reproStatus, "invalid_line_target");
    assert.equal(parsed.apiOutcome, "error");
    assert.match(parsed.probeHit, /counter reset requested/i);
    assert.equal(out.structuredContent.result.reason, "invalid_line_target");
    assert.equal(out.structuredContent.result.reasonCode, "invalid_line_target");
    assert.equal(out.structuredContent.result.actionCode, "runtime_not_aligned");
    assert.equal(out.structuredContent.result.nextActionCode, "align_runtime_and_artifact");
    assert.equal(
      out.structuredContent.request.key,
      "com.example.social.post.app.controller.PostController#updatePost:122",
    );
    assert.equal(out.structuredContent.request.resolvedKey, undefined);
  });
});

test("probe_wait_for_hit exits immediately for invalid_line_target", async () => {
  let calls = 0;
  await withMockedFetch(async () => {
    calls += 1;
    return jsonResponse(200, {
      key: "com.example.social.post.app.controller.PostController#updatePost:122",
      hitCount: 0,
      lastHitEpoch: 0,
      mode: "observe",
      lineResolvable: false,
      lineValidation: "invalid_line_target",
    });
  }, async () => {
    const out = await probeWaitHit({
      key: "com.example.social.post.app.controller.PostController#updatePost:122",
      baseUrl: "http://127.0.0.1:9191",
      statusPath: "/__probe/status",
      timeoutMs: 250,
      pollIntervalMs: 100,
      maxRetries: 3,
    });
    const parsed = parseProbeText(out);
    assert.equal(parsed.reproStatus, "invalid_line_target");
    assert.equal(parsed.httpCode, 422);
    assert.equal(parsed.actionCode, "runtime_not_aligned");
    assert.equal(parsed.nextAction, "rebuild_app_artifact_and_restart_jvm_then_rerun_probe");
    assert.equal((parsed as any).httpResponse, undefined);
    assert.equal(out.structuredContent.result.reason, "invalid_line_target");
    assert.equal(out.structuredContent.result.reasonCode, "invalid_line_target");
    assert.equal(out.structuredContent.result.actionCode, "runtime_not_aligned");
    assert.equal(out.structuredContent.result.nextActionCode, "align_runtime_and_artifact");
    assert.equal(out.structuredContent.result.reasonMeta.failedStep, "line_validation");
  });
  assert.equal(calls, 1);
});

test("probe_wait_for_hit returns structured service_unreachable by default", async () => {
  let calls = 0;
  await withMockedFetch(async () => {
    calls += 1;
    throw new Error("fetch failed");
  }, async () => {
    const out = await probeWaitHit({
      key: "com.example.social.post.app.controller.PostController#updatePost:122",
      baseUrl: "http://127.0.0.1:9191",
      statusPath: "/__probe/status",
      timeoutMs: 250,
      pollIntervalMs: 100,
      maxRetries: 3,
    });
    const parsed = parseProbeText(out);
    assert.equal(parsed.reproStatus, "probe_unreachable");
    assert.equal(parsed.httpCode, 503);
    assert.equal(parsed.actionCode, "probe_connectivity_issue");
    assert.equal(parsed.nextAction, "verify_probe_base_url_and_agent_reachability_then_rerun");
    assert.equal(out.structuredContent.result.reason, "service_unreachable");
    assert.equal(out.structuredContent.result.reasonCode, "service_unreachable");
    assert.equal(out.structuredContent.result.nextActionCode, "verify_probe_connectivity");
    assert.equal(out.structuredContent.result.reasonMeta.failedStep, "baseline_status_check");
    assert.equal(out.structuredContent.result.unreachableAttempts, 1);
    assert.equal(out.structuredContent.result.unreachableRetryEnabled, false);
  });
  assert.equal(calls, 1);
});

test("probe_wait_for_hit retries unreachable status checks when enabled and can recover", async () => {
  let calls = 0;
  await withMockedFetch(async () => {
    calls += 1;
    if (calls === 1) throw new Error("fetch failed");
    return jsonResponse(200, {
      key: "com.example.social.post.app.controller.PostController#updatePost:122",
      hitCount: 1,
      lastHitEpoch: Date.now(),
      mode: "observe",
      lineResolvable: true,
      lineValidation: "resolvable",
    });
  }, async () => {
    const out = await probeWaitHit({
      key: "com.example.social.post.app.controller.PostController#updatePost:122",
      baseUrl: "http://127.0.0.1:9191",
      statusPath: "/__probe/status",
      timeoutMs: 250,
      pollIntervalMs: 100,
      maxRetries: 3,
      unreachableRetryEnabled: true,
      unreachableMaxRetries: 3,
    });
    assert.equal(out.structuredContent.result.hit, true);
    assert.equal(out.structuredContent.result.inline, true);
  });
  assert.equal(calls, 2);
});

test("probe_wait_for_hit returns structured service_unreachable after unreachable retries are exhausted", async () => {
  let calls = 0;
  await withMockedFetch(async () => {
    calls += 1;
    throw new Error("fetch failed");
  }, async () => {
    const out = await probeWaitHit({
      key: "com.example.social.post.app.controller.PostController#updatePost:122",
      baseUrl: "http://127.0.0.1:9191",
      statusPath: "/__probe/status",
      timeoutMs: 250,
      pollIntervalMs: 100,
      maxRetries: 3,
      unreachableRetryEnabled: true,
      unreachableMaxRetries: 2,
    });
    const parsed = parseProbeText(out);
    assert.equal(parsed.reproStatus, "probe_unreachable");
    assert.equal(parsed.httpCode, 503);
    assert.equal(parsed.actionCode, "probe_connectivity_issue");
    assert.equal(parsed.nextAction, "verify_probe_base_url_and_agent_reachability_then_rerun");
    assert.equal(out.structuredContent.result.reason, "service_unreachable");
    assert.equal(out.structuredContent.result.reasonCode, "service_unreachable");
    assert.equal(out.structuredContent.result.nextActionCode, "verify_probe_connectivity");
    assert.equal(out.structuredContent.result.reasonMeta.failedStep, "baseline_status_check");
    assert.equal(out.structuredContent.result.unreachableAttempts, 2);
    assert.equal(out.structuredContent.result.unreachableRetryEnabled, true);
  });
  assert.equal(calls, 2);
});

test("probe_wait_for_hit timeout_no_inline_hit returns line-not-executed guidance", async () => {
  let calls = 0;
  await withMockedFetch(async () => {
    calls += 1;
    return jsonResponse(200, {
      key: "com.example.social.post.app.controller.PostController#updatePost:122",
      hitCount: 0,
      lastHitEpoch: 0,
      mode: "observe",
      lineResolvable: true,
      lineValidation: "resolvable",
    });
  }, async () => {
    const out = await probeWaitHit({
      key: "com.example.social.post.app.controller.PostController#updatePost:122",
      baseUrl: "http://127.0.0.1:9191",
      statusPath: "/__probe/status",
      timeoutMs: 120,
      pollIntervalMs: 60,
      maxRetries: 1,
    });
    const parsed = parseProbeText(out);
    assert.equal(parsed.httpCode, 408);
    assert.equal(parsed.actionCode, "line_not_executed_in_window");
    assert.equal(parsed.nextAction, "verify_trigger_path_or_branch_then_rerun_probe_wait_for_hit");
    assert.equal(out.structuredContent.result.reason, "timeout_no_inline_hit");
    assert.equal(out.structuredContent.result.reasonCode, "timeout_no_inline_hit");
    assert.equal(out.structuredContent.result.actionCode, "line_not_executed_in_window");
    assert.equal(out.structuredContent.result.nextActionCode, "verify_trigger_path");
    assert.equal(out.structuredContent.result.reasonMeta.failedStep, "wait_poll");
  });
  assert.ok(calls >= 2);
});

test("probe_wait_for_hit marks staleCandidate when hit count changes outside inline time window", async () => {
  const key = "com.example.social.post.app.controller.PostController#updatePost:122";
  const originalNow = Date.now;
  let now = 2_000;
  let fetchCalls = 0;
  try {
    LAST_RESET_EPOCH_BY_KEY.set(key, 1_900);
    Date.now = () => {
      now += 25;
      return now;
    };
    await withMockedFetch(async () => {
      fetchCalls += 1;
      // Baseline read: no hit yet in this wait window.
      if (fetchCalls === 1) {
        return jsonResponse(200, {
          key,
          hitCount: 0,
          lastHitEpoch: 0,
          mode: "observe",
          lineResolvable: true,
          lineValidation: "resolvable",
        });
      }
      // Poll reads: hit count changed, but lastHitEpoch is stale (< triggerWindowStartEpoch).
      return jsonResponse(200, {
        key,
        hitCount: 1,
        lastHitEpoch: 1_500,
        mode: "observe",
        lineResolvable: true,
        lineValidation: "resolvable",
      });
    }, async () => {
      const out = await probeWaitHit({
        key,
        baseUrl: "http://127.0.0.1:9191",
        statusPath: "/__probe/status",
        timeoutMs: 120,
        pollIntervalMs: 60,
        maxRetries: 1,
      });
      assert.equal(out.structuredContent.result.reason, "timeout_no_inline_hit");
      assert.equal(out.structuredContent.result.reasonCode, "timeout_no_inline_hit");
      assert.equal(out.structuredContent.result.staleCandidate.hitDelta, 1);
      assert.equal(
        out.structuredContent.result.staleCandidate.reason,
        "hit_count_changed_but_not_inline_to_current_wait_window",
      );
    });
    assert.equal(fetchCalls >= 2, true);
  } finally {
    Date.now = originalNow;
    LAST_RESET_EPOCH_BY_KEY.delete(key);
  }
});

test("probe_wait_for_hit emits minimal non-duplicative epoch fields", async () => {
  const key = "com.example.social.post.app.controller.PostController#updatePost:122";
  const originalNow = Date.now;
  let calls = 0;
  try {
    LAST_RESET_EPOCH_BY_KEY.set(key, 1_000);
    Date.now = () => 2_000;
    await withMockedFetch(async () => {
      calls += 1;
      return jsonResponse(200, {
        key,
        hitCount: 1,
        lastHitEpoch: 1_500,
        mode: "observe",
        lineResolvable: true,
        lineValidation: "resolvable",
      });
    }, async () => {
      const out = await probeWaitHit({
        key,
        baseUrl: "http://127.0.0.1:9191",
        statusPath: "/__probe/status",
        timeoutMs: 250,
        pollIntervalMs: 100,
        maxRetries: 1,
      });
      assert.equal(out.structuredContent.result.hit, true);
      assert.equal(out.structuredContent.result.inline, true);
      assert.equal(out.structuredContent.request.key, key);
      assert.equal(out.structuredContent.request.resolvedKey, undefined);
      assert.equal(out.structuredContent.request.waitStartEpoch, 2_000);
      assert.equal(out.structuredContent.request.triggerWindowStartEpoch, 1_000);
      assert.equal(out.structuredContent.request.inlineStartEpoch, undefined);
      assert.equal(out.structuredContent.request.lastResetEpoch, undefined);
    });
    assert.equal(calls, 1);
    assert.equal(LAST_RESET_EPOCH_BY_KEY.has(key), false);
  } finally {
    Date.now = originalNow;
    LAST_RESET_EPOCH_BY_KEY.delete(key);
  }
});

test("probe domain reuses reset window across probeIds when strict line keys are identical", async () => {
  const key = "com.example.social.post.app.controller.PostController#updatePost:122";
  const originalNow = Date.now;
  let now = 1_000;
  try {
    Date.now = () => now;
    const domain = createProbeDomain({
      probeBaseUrl: "",
      probeStatusPath: "/__probe/status",
      probeResetPath: "/__probe/reset",
      probeActuatePath: "/__probe/actuate",
      probeCapturePath: "/__probe/capture",
      probeProfilerPath: "/__probe/profiler",
      probeWaitMaxRetries: 1,
      probeWaitUnreachableRetryEnabled: false,
      probeWaitUnreachableMaxRetries: 1,
      getProbeRegistry: () => ({
        configFileAbs: "C:\\probe-config.json",
        activeProfile: "dev",
        profileSource: "env",
        probesById: new Map([
          ["producer-service", { id: "producer-service", baseUrl: "http://127.0.0.1:9191", include: [], exclude: [] }],
          ["consumer-service", { id: "consumer-service", baseUrl: "http://127.0.0.1:9192", include: [], exclude: [] }],
        ]),
      }),
    });

    await withMockedFetch(async (input, init) => {
      const url = String(input);
      const method = String(init?.method ?? "GET").toUpperCase();
      if (url === "http://127.0.0.1:9191/__probe/reset" && method === "POST") {
        return jsonResponse(200, {
          ok: true,
          key,
          lineResolvable: true,
          lineValidation: "resolvable",
        });
      }
      if (url === `http://127.0.0.1:9192/__probe/status?key=${encodeURIComponent(key)}` && method === "GET") {
        return jsonResponse(200, {
          key,
          hitCount: 1,
          lastHitEpoch: 1_500,
          mode: "observe",
          lineResolvable: true,
          lineValidation: "resolvable",
        });
      }
      throw new Error(`unexpected fetch ${method} ${url}`);
    }, async () => {
      const resetOut = await domain.reset({
        probeId: "producer-service",
        key,
      });
      assert.equal(resetOut.structuredContent.response.status, 200);

      now = 2_000;
      const waitOut = await domain.waitForHit({
        probeId: "consumer-service",
        key,
        timeoutMs: 250,
        pollIntervalMs: 100,
        maxRetries: 1,
      });

      assert.equal(waitOut.structuredContent.result.hit, true);
      assert.equal(waitOut.structuredContent.result.source, "already_hit_since_inline_start");
      assert.equal(waitOut.structuredContent.result.hitDelta, 0);
      assert.equal(waitOut.structuredContent.request.triggerWindowStartEpoch, 1_000);
      assert.equal(waitOut.structuredContent.request.waitStartEpoch, 2_000);
    });
  } finally {
    Date.now = originalNow;
    LAST_RESET_EPOCH_BY_KEY.delete(key);
  }
});

test("probe_wait_for_hit preserves the original reset window across retry attempts", async () => {
  const key = "com.example.social.post.app.controller.PostController#updatePost:122";
  const originalNow = Date.now;
  let now = 0;
  let fetchCalls = 0;
  try {
    LAST_RESET_EPOCH_BY_KEY.set(key, 500);
    Date.now = () => {
      now += 600;
      return now;
    };
    await withMockedFetch(async () => {
      fetchCalls += 1;
      if (fetchCalls <= 3) {
        return jsonResponse(200, {
          key,
          hitCount: 0,
          lastHitEpoch: 0,
          mode: "observe",
          lineResolvable: true,
          lineValidation: "resolvable",
        });
      }
      return jsonResponse(200, {
        key,
        hitCount: 1,
        lastHitEpoch: 1_500,
        mode: "observe",
        lineResolvable: true,
        lineValidation: "resolvable",
      });
    }, async () => {
      const out = await probeWaitHit({
        key,
        baseUrl: "http://127.0.0.1:9191",
        statusPath: "/__probe/status",
        timeoutMs: 120,
        pollIntervalMs: 60,
        maxRetries: 2,
      });
      assert.equal(out.structuredContent.result.hit, true);
      assert.equal(out.structuredContent.result.inline, true);
      assert.equal(out.structuredContent.request.maxRetries, 2);
      assert.equal(out.structuredContent.request.triggerWindowStartEpoch, 500);
    });
    assert.equal(fetchCalls, 4);
    assert.equal(LAST_RESET_EPOCH_BY_KEY.has(key), false);
  } finally {
    Date.now = originalNow;
    LAST_RESET_EPOCH_BY_KEY.delete(key);
  }
});

test("probe_wait_for_hit line_key_required includes diagnostics contract", async () => {
  const out = await probeWaitHit({
    key: "com.example.social.post.app.controller.PostController#updatePost",
    baseUrl: "http://127.0.0.1:9191",
    statusPath: "/__probe/status",
    timeoutMs: 250,
    pollIntervalMs: 100,
    maxRetries: 1,
  });
  assert.equal(out.structuredContent.result.reason, "line_key_required");
  assert.equal(out.structuredContent.result.reasonCode, "line_key_required");
  assert.equal(out.structuredContent.result.nextActionCode, "provide_strict_line_key");
  assert.equal(out.structuredContent.result.reasonMeta.failedStep, "input_validation");
});

test("probe_get_status remains backward-compatible when line validation fields are absent", async () => {
  await withMockedFetch(async () => {
    return jsonResponse(200, {
      key: "com.example.social.post.app.controller.PostController#updatePost:122",
      hitCount: 0,
      lastHitEpoch: 0,
      mode: "observe",
    });
  }, async () => {
    const out = await probeStatus({
      key: "com.example.social.post.app.controller.PostController#updatePost:122",
      baseUrl: "http://127.0.0.1:9191",
      statusPath: "/__probe/status",
    });
    const parsed = parseProbeText(out);
    assert.equal(parsed.reproStatus, "status_checked");
    assert.equal(parsed.executionHit, "not_hit");
    assert.equal(out.structuredContent.response.json.capturePreview, undefined);
  });
});

test("probe_get_status supports keys[] batch with partial success semantics", async () => {
  let calls = 0;
  let postedBody: any = null;
  const lineKey = "com.example.social.post.app.controller.PostController#updatePost:122";
  const nonLineKey = "com.example.social.post.app.controller.PostController#updatePost";

  await withMockedFetch(async (_input, init) => {
    calls += 1;
    postedBody = init?.body ? JSON.parse(String(init.body)) : null;
    return jsonResponse(200, {
      ok: true,
      count: 1,
      results: [
        {
          ok: true,
          key: lineKey,
          hitCount: 2,
          lastHitEpoch: 1234567,
          mode: "observe",
          lineResolvable: true,
          lineValidation: "resolvable",
        },
      ],
    });
  }, async () => {
    const out = await probeStatus({
      keys: [lineKey, nonLineKey],
      baseUrl: "http://127.0.0.1:9191",
      statusPath: "/__probe/status",
    });
    const parsed = parseProbeText(out);
    const summary = parsed.summary as Record<string, unknown>;
    assert.equal(parsed.mode, "probe_batch");
    assert.equal(parsed.operation, "status");
    assert.equal(summary.total, 2);
    assert.equal(summary.ok, 1);
    assert.equal(summary.failed, 1);
    const failures = parsed.failures as Array<Record<string, unknown>>;
    assert.equal(Array.isArray(failures), true);
    assert.equal(failures.length, 1);
    const firstFailure = failures[0];
    if (!firstFailure) throw new Error("expected first failure row");
    assert.equal(firstFailure.key, nonLineKey);
    assert.equal(firstFailure.reproStatus, "line_key_required");
    assert.equal(firstFailure.reasonCode, "line_key_required");
    assert.equal(firstFailure.nextActionCode, "provide_strict_line_key");
  });

  assert.equal(calls, 1);
  assert.deepEqual(postedBody, { keys: [lineKey] });
});

test("probe_get_status supports 0.1.0 batch rows with nested probe payload", async () => {
  const lineKey = "com.example.social.post.app.controller.PostController#updatePost:122";
  await withMockedFetch(async () => {
    return jsonResponse(200, {
      contractVersion: "0.1.0",
      ok: true,
      count: 1,
      results: [
        {
          ok: true,
          probe: {
            key: lineKey,
            hitCount: 3,
            lastHitEpoch: 2222,
            lineResolvable: true,
            lineValidation: "resolvable",
          },
          capturePreview: {
            available: true,
            captureId: "cap-1",
            capturedAtEpoch: 4444,
            executionStartedAtEpoch: 4420,
            executionEndedAtEpoch: 4444,
            executionDurationMs: 24,
            threadAllocatedBytesDelta: 512,
              executionPaths: ["CatalogController.listCatalogShoes()#42"],
          },
          runtime: {
            mode: "observe",
            actuatorId: "",
            actuateTargetKey: "",
            actuateReturnBoolean: false,
            serverEpoch: 3333,
            applicationType: {
              value: "spring-boot",
              source: "classpath:org.springframework.boot.SpringApplication",
              confidence: 0.9,
            },
            appPort: {
              value: 8082,
              source: "system_property:server.port",
              confidence: 0.95,
            },
          },
        },
      ],
    });
  }, async () => {
    const out = await probeStatus({
      keys: [lineKey],
      baseUrl: "http://127.0.0.1:9191",
      statusPath: "/__probe/status",
    });
    const parsed = parseProbeText(out);
    const failures = parsed.failures as Array<Record<string, unknown>>;
    assert.equal(Array.isArray(failures), true);
    assert.deepEqual(failures, []);
    assert.equal(parsed.notes, "Use structuredContent.results for full per-key payload.");
    const rows = out.structuredContent.results as Array<Record<string, any>>;
    assert.equal(rows.length, 1);
    const firstRow = rows[0];
    if (!firstRow) throw new Error("expected first batch row");
    assert.equal(firstRow.key, lineKey);
    assert.equal(firstRow.hitCount, 3);
    assert.equal(firstRow.lastHitEpoch, 2222);
    assert.equal(firstRow.runtimeMode, "observe");
    assert.equal(firstRow.capturePreview.captureId, "cap-1");
    assert.equal(firstRow.capturePreview.executionStartedAtEpoch, 4420);
    assert.equal(firstRow.capturePreview.executionEndedAtEpoch, 4444);
    assert.equal(firstRow.capturePreview.executionDurationMs, 24);
    assert.equal(firstRow.capturePreview.threadAllocatedBytesDelta, 512);
    assert.equal(firstRow.capturePreview.executionPaths, undefined);
  });
});

test("probe_get_capture returns capture payload when available", async () => {
  await withMockedFetch(async () => {
    return jsonResponse(200, {
      contractVersion: "0.1.0",
      capture: {
        captureId: "abc123",
        methodKey: "com.example.social.post.app.controller.PostController#updatePost",
        capturedAtEpoch: 1000,
        executionStartedAtEpoch: 950,
        executionEndedAtEpoch: 1000,
        executionDurationMs: 50,
        threadAllocatedBytesDelta: 1024,
        redactionMode: "basic",
        args: [{ index: 0, value: "{\"sku\":\"A1\"}", truncated: false, originalLength: 12, redacted: false }],
        returnValue: { value: "{\"ok\":true}", truncated: false, originalLength: 11, redacted: false },
        thrownValue: null,
        truncatedAny: false,
            executionPaths: ["CatalogRepo.save()#88"],
      },
    });
  }, async () => {
    const out = await probeCaptureGet({
      captureId: "abc123",
      baseUrl: "http://127.0.0.1:9191",
      capturePath: "/__probe/capture",
    });
    const parsed = parseProbeText(out);
    assert.equal(parsed.mode, "probe_get_capture");
    assert.equal((parsed as any).result.found, true);
    assert.equal((parsed as any).result.captureId, "abc123");
    assert.equal((parsed as any).result.executionStartedAtEpoch, 950);
    assert.equal((parsed as any).result.executionEndedAtEpoch, 1000);
    assert.equal((parsed as any).result.executionDurationMs, 50);
    assert.equal((parsed as any).result.threadAllocatedBytesDelta, 1024);
    assert.equal((parsed as any).result.argsCount, 1);
    assert.equal((parsed as any).result.executionPathCount, 1);
    assert.equal((parsed as any).result.capture, undefined);
    assert.equal(parsed.notes, "Use structuredContent.result.capture for full payload.");
    assert.equal(out.structuredContent.result.found, true);
    assert.equal(out.structuredContent.result.capture.captureId, "abc123");
    assert.equal(out.structuredContent.result.capture.executionStartedAtEpoch, 950);
    assert.equal(out.structuredContent.result.capture.executionEndedAtEpoch, 1000);
    assert.equal(out.structuredContent.result.capture.executionDurationMs, 50);
    assert.equal(out.structuredContent.result.capture.threadAllocatedBytesDelta, 1024);
    assert.equal(out.structuredContent.result.capture.argsCount, 1);
    assert.equal(out.structuredContent.result.capture.hasReturnValue, true);
    assert.equal(out.structuredContent.result.capture.hasThrownValue, false);
    assert.equal(out.structuredContent.result.capture.executionPaths, undefined);
  });
});

test("probe_profiler surfaces deterministic reason code for fail-closed start response", async () => {
  await withMockedFetch(async () => {
    return jsonResponse(409, {
      error: "profiler_unsupported_platform",
    });
  }, async () => {
    const out = await probeProfiler({
      action: "start",
      sessionId: "perf-1",
      event: "wall",
      outputFormat: "jfr",
      baseUrl: "http://127.0.0.1:9191",
      profilerPath: "/__probe/profiler",
    });

    assert.equal(out.structuredContent.response.status, 409);
    assert.equal(out.structuredContent.result.status, "profiler_unsupported_platform");
    assert.equal(out.structuredContent.result.reasonCode, "profiler_unsupported_platform");
    assert.equal(out.structuredContent.result.detail, "profiler_unsupported_platform");
    assert.equal(out.structuredContent.result.supported, false);
  });
});

test("probe_get_capture returns not found state when capture is missing", async () => {
  await withMockedFetch(async () => {
    return jsonResponse(404, {
      contractVersion: "0.1.0",
      error: "capture_not_found",
      captureId: "missing",
    });
  }, async () => {
    const out = await probeCaptureGet({
      captureId: "missing",
      baseUrl: "http://127.0.0.1:9191",
      capturePath: "/__probe/capture",
    });
    const parsed = parseProbeText(out);
    assert.equal(parsed.mode, "probe_get_capture");
    assert.equal((parsed as any).result.found, false);
    assert.equal((parsed as any).result.reason, "capture_not_found");
    assert.equal(out.structuredContent.result.found, false);
    assert.equal(out.structuredContent.result.reason, "capture_not_found");
    assert.equal(out.structuredContent.result.reasonCode, "capture_not_found");
    assert.equal(out.structuredContent.result.nextActionCode, "request_new_capture");
  });
});

test("probe_get_capture includes timing fields for thrown captures", async () => {
  await withMockedFetch(async () => {
    return jsonResponse(200, {
      contractVersion: "0.1.0",
      capture: {
        captureId: "err-1",
        methodKey: "com.example.social.post.app.service.PostService#updatePost",
        capturedAtEpoch: 1500,
        executionStartedAtEpoch: 1400,
        executionEndedAtEpoch: 1500,
        executionDurationMs: 100,
        threadAllocatedBytesDelta: 1536,
        redactionMode: "basic",
        args: [],
        returnValue: null,
        thrownValue: {
          value: "{\"message\":\"boom\"}",
          truncated: false,
          originalLength: 18,
          redacted: false,
        },
        truncatedAny: false,
      },
    });
  }, async () => {
    const out = await probeCaptureGet({
      captureId: "err-1",
      baseUrl: "http://127.0.0.1:9191",
      capturePath: "/__probe/capture",
    });
    assert.equal(out.structuredContent.result.found, true);
    assert.equal(out.structuredContent.result.capture.captureId, "err-1");
    assert.equal(out.structuredContent.result.capture.hasReturnValue, false);
    assert.equal(out.structuredContent.result.capture.hasThrownValue, true);
    assert.equal(out.structuredContent.result.capture.executionStartedAtEpoch, 1400);
    assert.equal(out.structuredContent.result.capture.executionEndedAtEpoch, 1500);
    assert.equal(out.structuredContent.result.capture.executionDurationMs, 100);
    assert.equal(out.structuredContent.result.capture.threadAllocatedBytesDelta, 1536);
  });
});

test("probe_reset supports keys[] batch with partial success semantics", async () => {
  let calls = 0;
  let postedBody: any = null;
  const validLineKey = "com.example.social.post.app.controller.PostController#updatePost:122";
  const invalidLineKey = "com.example.social.post.app.controller.PostController#updatePost:123";
  const nonLineKey = "com.example.social.post.app.controller.PostController#updatePost";

  await withMockedFetch(async (_input, init) => {
    calls += 1;
    postedBody = init?.body ? JSON.parse(String(init.body)) : null;
    return jsonResponse(200, {
      ok: true,
      selector: "keys",
      count: 2,
      results: [
        {
          ok: true,
          key: validLineKey,
          lineResolvable: true,
          lineValidation: "resolvable",
        },
        {
          ok: true,
          key: invalidLineKey,
          lineResolvable: false,
          lineValidation: "invalid_line_target",
        },
      ],
    });
  }, async () => {
    const out = await probeReset({
      keys: [validLineKey, invalidLineKey, nonLineKey, validLineKey],
      baseUrl: "http://127.0.0.1:9191",
      resetPath: "/__probe/reset",
    });
    const parsed = parseProbeText(out);
    const summary = parsed.summary as Record<string, unknown>;
    assert.equal(parsed.mode, "probe_batch");
    assert.equal(parsed.operation, "reset");
    assert.equal(summary.total, 3);
    assert.equal(summary.ok, 1);
    assert.equal(summary.failed, 2);
    const failures = parsed.failures as Array<Record<string, unknown>>;
    const invalidRow = failures.find((row) => row.key === invalidLineKey);
    if (!invalidRow) throw new Error("expected invalid line row in batch response");
    assert.equal(invalidRow.apiOutcome, "error");
    assert.equal(invalidRow.reproStatus, "invalid_line_target");
    assert.equal(invalidRow.reasonCode, "invalid_line_target");
    assert.equal(invalidRow.nextActionCode, "align_runtime_and_artifact");
  });

  assert.equal(calls, 1);
  assert.deepEqual(postedBody, { keys: [validLineKey, invalidLineKey] });
});

test("probe_reset supports className selector and class_not_found no-op response", async () => {
  let calls = 0;
  await withMockedFetch(async () => {
    calls += 1;
    return jsonResponse(200, {
      ok: true,
      selector: "className",
      className: "com.example.social.post.app.controller.PostController",
      count: 0,
      reason: "class_not_found",
      results: [],
    });
  }, async () => {
    const out = await probeReset({
      className: "com.example.social.post.app.controller.PostController",
      baseUrl: "http://127.0.0.1:9191",
      resetPath: "/__probe/reset",
    });
    const parsed = parseProbeText(out);
    const summary = parsed.summary as Record<string, unknown>;
    assert.equal(parsed.mode, "probe_batch");
    assert.equal(parsed.operation, "reset");
    assert.equal(summary.total, 0);
    const failures = parsed.failures as Array<Record<string, unknown>>;
    assert.deepEqual(failures, []);
    assert.equal((out.structuredContent.response as any).reason, "class_not_found");
  });
  assert.equal(calls, 1);
});

test("probe_get_status rejects conflicting or missing selectors", async () => {
  await assert.rejects(
    probeStatus({
      key: "com.example.social.post.app.controller.PostController#updatePost:122",
      keys: ["com.example.social.post.app.controller.PostController#updatePost:123"],
      baseUrl: "http://127.0.0.1:9191",
      statusPath: "/__probe/status",
    }),
    /conflicting selectors/i,
  );

  await assert.rejects(
    probeStatus({
      baseUrl: "http://127.0.0.1:9191",
      statusPath: "/__probe/status",
    } as any),
    /requires exactly one selector/i,
  );

  await assert.rejects(
    probeStatus({
      keys: ["com.example.social.post.app.controller.PostController#updatePost:123"],
      lineHint: 123,
      baseUrl: "http://127.0.0.1:9191",
      statusPath: "/__probe/status",
    }),
    /does not allow lineHint with keys\[\]/i,
  );
});

test("probe_enable arm requires strict line target key", async () => {
  const out = await probeActuate({
    action: "arm",
    sessionId: "test-session",
    targetKey: "com.example.social.post.app.controller.PostController#updatePost",
    returnBoolean: true,
    ttlMs: 10_000,
    baseUrl: "http://127.0.0.1:9191",
    actuatePath: "/__probe/actuate",
  });
  assert.equal(out.structuredContent.result.reason, "line_key_required");
  assert.equal(out.structuredContent.result.reasonCode, "line_key_required");
  assert.equal(out.structuredContent.result.nextActionCode, "provide_strict_line_key");
});

test("probe_reset rejects conflicting or missing selectors", async () => {
  await assert.rejects(
    probeReset({
      key: "com.example.social.post.app.controller.PostController#updatePost:122",
      className: "com.example.social.post.app.controller.PostController",
      baseUrl: "http://127.0.0.1:9191",
      resetPath: "/__probe/reset",
    }),
    /conflicting selectors/i,
  );

  await assert.rejects(
    probeReset({
      baseUrl: "http://127.0.0.1:9191",
      resetPath: "/__probe/reset",
    } as any),
    /requires exactly one selector/i,
  );

  await assert.rejects(
    probeReset({
      className: "com.example.social.post.app.controller.PostController",
      lineHint: 88,
      baseUrl: "http://127.0.0.1:9191",
      resetPath: "/__probe/reset",
    }),
    /does not allow lineHint with keys\[\] or className/i,
  );
});

test("probe domain fails closed for unknown probeId", async () => {
  const domain = createProbeDomain({
    probeBaseUrl: "http://127.0.0.1:9190",
    probeStatusPath: "/__probe/status",
    probeResetPath: "/__probe/reset",
    probeActuatePath: "/__probe/actuate",
    probeCapturePath: "/__probe/capture",
    probeWaitMaxRetries: 1,
    probeWaitUnreachableRetryEnabled: false,
    probeWaitUnreachableMaxRetries: 1,
    getProbeRegistry: () => ({
      configFileAbs: "C:\\probe-config.json",
      activeProfile: "dev",
      profileSource: "env",
      implicitProbeId: "order-service",
      probesById: new Map([
        [
          "order-service",
          { id: "order-service", baseUrl: "http://127.0.0.1:9190", include: [], exclude: [] },
        ],
      ]),
    }),
  });
  const out = await domain.getStatus({
    probeId: "missing-service",
    key: "com.example.social.post.app.controller.PostController#updatePost:122",
  });
  assert.equal(out.structuredContent.status, "probe_selection_failed");
  assert.equal(out.structuredContent.reasonCode, "probe_id_unknown");
  assert.equal(out.structuredContent.nextActionCode, "select_registered_probe_id");
});

test("probe domain fails closed when registry is multi-probe and selection is omitted", async () => {
  const domain = createProbeDomain({
    probeBaseUrl: "",
    probeStatusPath: "/__probe/status",
    probeResetPath: "/__probe/reset",
    probeActuatePath: "/__probe/actuate",
    probeCapturePath: "/__probe/capture",
    probeWaitMaxRetries: 1,
    probeWaitUnreachableRetryEnabled: false,
    probeWaitUnreachableMaxRetries: 1,
    getProbeRegistry: () => ({
      configFileAbs: "C:\\probe-config.json",
      activeProfile: "dev",
      profileSource: "env",
      probesById: new Map([
        ["order-service", { id: "order-service", baseUrl: "http://127.0.0.1:9190", include: [], exclude: [] }],
        ["auth-service", { id: "auth-service", baseUrl: "http://127.0.0.1:9191", include: [], exclude: [] }],
      ]),
    }),
  });
  const out = await domain.getStatus({
    key: "com.example.social.post.app.controller.PostController#updatePost:122",
  });
  assert.equal(out.structuredContent.status, "probe_selection_failed");
  assert.equal(out.structuredContent.reasonCode, "probe_id_required");
  assert.equal(out.structuredContent.reasonMeta.probeCount, 2);
});


