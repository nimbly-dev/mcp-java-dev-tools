import {
  assert,
  fs,
  fssync,
  http,
  os,
  path,
  test,
  callTool,
  writeJson,
  listen,
  startMcpClient,
} from "./execution_orchestration.shared";

test("[IT][execution_orchestration][execute] execution_orchestration blocks non-canonical env-style prerequisite keys and placeholders", async () => {
  const tmpRoot = await fs.mkdtemp(
    path.join(os.tmpdir(), "mcp-execution-orchestration-env-prereq-alias-it-"),
  );
  const workspaceRootAbs = path.join(tmpRoot, "workspace");
  const projectName = "test-project-env-prereq-alias";
  const projectRootAbs = workspaceRootAbs;
  const probeConfigAbs = path.join(workspaceRootAbs, ".mcpjvm", "probe-config.json");
  const planName = "secure-env-alias-regression";
  const planRootAbs = path.join(
    workspaceRootAbs,
    ".mcpjvm",
    projectName,
    "plans",
    "regression",
    planName,
  );

  await writeJson(probeConfigAbs, {
    defaultProfile: "dev",
    profiles: {
      dev: {
        probes: {
          "gateway-service": {
            baseUrl: "http://127.0.0.1:9196",
            include: ["com.example.**"],
            exclude: [],
          },
        },
      },
    },
    workspaces: [{ root: workspaceRootAbs, profile: "dev" }],
  });

  await writeJson(path.join(workspaceRootAbs, ".mcpjvm", projectName, "projects.json"), {
    workspaces: [
      {
        projectRoot: projectRootAbs,
        variables: { bearerTokenEnv: "AUTH_BEARER_TOKEN" },
        executionProfiles: [
          {
            executionProfile: "env-prereq-alias-run",
            executionPolicy: "stop_on_fail",
            plans: [{ order: 1, planName, onFail: "inherit" }],
          },
        ],
      },
    ],
  });

  await writeJson(path.join(planRootAbs, "metadata.json"), {
    execution: { intent: "regression" },
  });
  await writeJson(path.join(planRootAbs, "contract.json"), {
    targets: [{ type: "class_method", selectors: { fqcn: "x.A", method: "m" } }],
    prerequisites: [
      {
        key: "AUTH_BEARER_TOKEN",
        required: true,
        secret: true,
        provisioning: "user_input",
      },
    ],
    steps: [
      {
        order: 1,
        id: "secure_call",
        targetRef: 0,
        protocol: "http",
        transport: {
          http: {
            method: "GET",
            url: "http://127.0.0.1:8080/secure",
            headers: { Authorization: "Bearer {{AUTH_BEARER_TOKEN}}" },
          },
        },
        expect: [
          { id: "outcome_ok", actualPath: "status", operator: "outcome_status", expected: "pass" },
        ],
      },
    ],
  });

  let mcp: Awaited<ReturnType<typeof startMcpClient>> | undefined;
  try {
    mcp = await startMcpClient({
      workspaceRootAbs,
      probeBaseUrl: "http://127.0.0.1:9196",
      extraEnv: {
        MCP_PROBE_CONFIG_FILE: probeConfigAbs,
        AUTH_BEARER_TOKEN: "runtime-token-from-env",
      },
    });

    const out = await callTool(mcp, "execution_orchestration", {
      action: "execute",
      input: {
        projectName,
        executionProfile: "env-prereq-alias-run",
      },
    });

    assert.equal(out.structuredContent?.resultType, "execution_orchestration");
    assert.equal(out.structuredContent?.status, "blocked");
    const planRuns = Array.isArray(out.structuredContent?.planRuns)
      ? (out.structuredContent?.planRuns as Array<Record<string, unknown>>)
      : [];
    assert.equal(planRuns.length, 1);
    assert.equal(planRuns[0]?.status, "blocked");
    assert.equal(planRuns[0]?.blockedReasonCode, "plan_context_key_noncanonical");
  } finally {
    await mcp?.close();
    if (fssync.existsSync(tmpRoot)) {
      await fs.rm(tmpRoot, { recursive: true, force: true });
    }
  }
});

test("[IT][execution_orchestration][execute] execution_orchestration supports resumable in_progress slicing by suiteRunId", async () => {
  const tmpRoot = await fs.mkdtemp(
    path.join(os.tmpdir(), "mcp-execution-orchestration-resume-it-"),
  );
  const workspaceRootAbs = path.join(tmpRoot, "workspace");
  const projectName = "test-project-performance";
  const projectRootAbs = workspaceRootAbs;
  const probeConfigAbs = path.join(workspaceRootAbs, ".mcpjvm", "probe-config.json");

  await writeJson(probeConfigAbs, {
    defaultProfile: "dev",
    profiles: {
      dev: {
        probes: {
          "gateway-service": {
            baseUrl: "http://127.0.0.1:9196",
            include: ["com.example.**"],
            exclude: [],
          },
        },
      },
    },
    workspaces: [{ root: workspaceRootAbs, profile: "dev" }],
  });

  await writeJson(path.join(workspaceRootAbs, ".mcpjvm", projectName, "projects.json"), {
    workspaces: [
      {
        projectRoot: projectRootAbs,
        executionProfiles: [
          {
            executionProfile: "resumable-suite",
            executionPolicy: "continue_on_fail",
            plans: [
              { order: 1, planName: "plan-a", onFail: "inherit" },
              { order: 2, planName: "plan-b", onFail: "inherit" },
            ],
          },
        ],
      },
    ],
  });

  for (const planName of ["plan-a", "plan-b"]) {
    await writeJson(
      path.join(
        workspaceRootAbs,
        ".mcpjvm",
        projectName,
        "plans",
        "regression",
        planName,
        "metadata.json",
      ),
      { execution: { intent: "regression" } },
    );
    await writeJson(
      path.join(
        workspaceRootAbs,
        ".mcpjvm",
        projectName,
        "plans",
        "regression",
        planName,
        "contract.json",
      ),
      {
        targets: [{ type: "class_method", selectors: { fqcn: "x.A", method: "m" } }],
        prerequisites: [],
        steps: [],
      },
    );
  }

  let mcp: Awaited<ReturnType<typeof startMcpClient>> | undefined;
  try {
    mcp = await startMcpClient({
      workspaceRootAbs,
      probeBaseUrl: "http://127.0.0.1:9196",
      extraEnv: { MCP_PROBE_CONFIG_FILE: probeConfigAbs },
    });

    const first = await callTool(mcp, "execution_orchestration", {
      action: "execute",
      input: {
        projectName,
        executionProfile: "resumable-suite",
        maxPlansPerCall: 1,
      },
    });

    assert.equal(first.structuredContent?.resultType, "execution_orchestration");
    assert.equal(first.structuredContent?.status, "in_progress");
    assert.equal(first.structuredContent?.nextPlanOrder, 2);
    const suiteRunId = String(first.structuredContent?.suiteRunId ?? "");
    assert.equal(suiteRunId.length > 0, true);

    const second = await callTool(mcp, "execution_orchestration", {
      action: "execute",
      input: {
        projectName,
        executionProfile: "resumable-suite",
        suiteRunId,
        maxPlansPerCall: 1,
      },
    });

    assert.equal(second.structuredContent?.resultType, "execution_orchestration");
    assert.equal(second.structuredContent?.status, "partial_fail");
    assert.equal(second.structuredContent?.suiteRunId, suiteRunId);
    const planRuns = Array.isArray(second.structuredContent?.planRuns)
      ? (second.structuredContent?.planRuns as Array<Record<string, unknown>>)
      : [];
    assert.equal(planRuns.length, 2);
  } finally {
    await mcp?.close();
    if (fssync.existsSync(tmpRoot)) {
      await fs.rm(tmpRoot, { recursive: true, force: true });
    }
  }
});

test("[IT][execution_orchestration][execute] execution_orchestration resumes a suite Watcher with the prior extracted value", async () => {
  const tmpRoot = await fs.mkdtemp(
    path.join(os.tmpdir(), "mcp-execution-orchestration-resumed-watcher-it-"),
  );
  const workspaceRootAbs = path.join(tmpRoot, "workspace");
  const projectName = "test-project-resumed-watcher";
  const projectRootAbs = workspaceRootAbs;
  const probeConfigAbs = path.join(workspaceRootAbs, ".mcpjvm", "probe-config.json");
  const eventId = "evt-resumed-123";
  let postCount = 0;
  let indexChecks = 0;
  const requests: string[] = [];
  let appServer: http.Server | undefined;
  try {
    appServer = http.createServer((req, res) => {
      requests.push(`${req.method} ${req.url}`);
      if (req.method === "POST" && req.url === "/events") {
        postCount += 1;
        res.statusCode = 202;
        res.setHeader("content-type", "application/json");
        res.end(JSON.stringify({ id: eventId }));
        return;
      }
      if (req.method === "GET" && req.url === `/imports/${eventId}`) {
        res.statusCode = 200;
        res.setHeader("content-type", "application/json");
        res.end(JSON.stringify({ id: eventId, status: "accepted" }));
        return;
      }
      if (req.method === "GET" && req.url === `/index/${eventId}`) {
        indexChecks += 1;
        res.statusCode = 200;
        res.setHeader("content-type", "application/json");
        res.end(JSON.stringify(indexChecks >= 4 ? { state: "ready" } : { phase: "pending" }));
        return;
      }
      res.statusCode = 404;
      res.end(JSON.stringify({ reason: "missing" }));
    });
    const appPort = await listen(appServer);
    const actualBaseUrl = `http://127.0.0.1:${appPort}`;
    await writeJson(probeConfigAbs, {
      defaultProfile: "dev",
      profiles: {
        dev: {
          probes: {
            gateway: { baseUrl: "http://127.0.0.1:9196", include: ["com.example.**"], exclude: [] },
          },
        },
      },
      workspaces: [{ root: workspaceRootAbs, profile: "dev" }],
    });
    await writeJson(path.join(workspaceRootAbs, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: projectRootAbs,
          defaults: { requestTimeoutMs: 4000, retryMax: 4 },
          executionProfiles: [
            {
              executionProfile: "resumed-watcher-run",
              executionPolicy: "stop_on_fail",
              plans: [
                { order: 1, planName: "producer", providedContext: { apiBaseUrl: actualBaseUrl } },
                { order: 2, planName: "consumer", providedContext: { apiBaseUrl: actualBaseUrl } },
              ],
            },
          ],
        },
      ],
    });
    const baseContract = {
      targets: [{ type: "class_method", selectors: { fqcn: "x.A", method: "m" } }],
      prerequisites: [
        {
          key: "apiBaseUrl",
          required: true,
          secret: false,
          provisioning: "user_input",
          default: actualBaseUrl,
        },
      ],
    };
    await writeJson(
      path.join(
        workspaceRootAbs,
        ".mcpjvm",
        projectName,
        "plans",
        "regression",
        "producer",
        "metadata.json",
      ),
      { execution: { intent: "regression" } },
    );
    await writeJson(
      path.join(
        workspaceRootAbs,
        ".mcpjvm",
        projectName,
        "plans",
        "regression",
        "producer",
        "contract.json",
      ),
      {
        ...baseContract,
        steps: [
          {
            order: 1,
            id: "post_event",
            targetRef: 0,
            protocol: "http",
            transport: { http: { method: "POST", pathTemplate: "/events" } },
            extract: [
              {
                from: "response.bodyJson.id",
                as: "eventId",
                scope: "suite",
                secret: false,
                required: true,
              },
            ],
            expect: [
              {
                id: "accepted",
                actualPath: "response.statusCode",
                operator: "field_equals",
                expected: 202,
              },
            ],
          },
        ],
      },
    );
    await writeJson(
      path.join(
        workspaceRootAbs,
        ".mcpjvm",
        projectName,
        "plans",
        "regression",
        "consumer",
        "metadata.json",
      ),
      { execution: { intent: "regression" } },
    );
    await writeJson(
      path.join(
        workspaceRootAbs,
        ".mcpjvm",
        projectName,
        "plans",
        "regression",
        "consumer",
        "contract.json",
      ),
      {
        ...baseContract,
        steps: [
          {
            order: 1,
            id: "get_import",
            targetRef: 0,
            protocol: "http",
            transport: { http: { method: "GET", pathTemplate: "/imports/${eventId}" } },
            expect: [
              {
                id: "accepted",
                actualPath: "response.statusCode",
                operator: "field_equals",
                expected: 200,
              },
            ],
          },
        ],
        watchers: [
          {
            id: "indexed",
            dependency: { stepOrder: 1 },
            provider: {
              type: "http",
              transport: { request: { method: "GET", url: `${actualBaseUrl}/index/\${eventId}` } },
            },
            expect: [
              {
                id: "ready",
                actualPath: "response.bodyJson.state",
                operator: "field_equals",
                expected: "ready",
              },
            ],
          },
        ],
      },
    );
    const mcp = await startMcpClient({
      workspaceRootAbs,
      probeBaseUrl: "http://127.0.0.1:9196",
      extraEnv: { MCP_PROBE_CONFIG_FILE: probeConfigAbs },
    });
    try {
      const first = await callTool(mcp, "execution_orchestration", {
        action: "execute",
        input: { projectName, executionProfile: "resumed-watcher-run", maxPlansPerCall: 1 },
      });
      assert.equal(first.structuredContent?.status, "in_progress");
      const suiteRunId = String(first.structuredContent?.suiteRunId ?? "");
      assert.ok(suiteRunId);
      const [second, concurrent] = await Promise.all([
        callTool(mcp, "execution_orchestration", {
          action: "execute",
          input: { projectName, executionProfile: "resumed-watcher-run", suiteRunId },
        }),
        callTool(mcp, "execution_orchestration", {
          action: "execute",
          input: { projectName, executionProfile: "resumed-watcher-run", suiteRunId },
        }),
      ]);
      const statuses = [second.structuredContent?.status, concurrent.structuredContent?.status];
      assert.equal(statuses.filter((status) => status === "pass").length, 1);
      assert.equal(statuses.filter((status) => status === "in_progress").length, 1);
      assert.equal(second.structuredContent?.suiteRunId, suiteRunId);
      assert.equal(concurrent.structuredContent?.suiteRunId, suiteRunId);
      const conflict = [second, concurrent].find(
        (result) => result.structuredContent?.status === "in_progress",
      );
      assert.equal(conflict?.structuredContent?.reasonCode, "suite_checkpoint_owner_active");
      assert.equal(typeof conflict?.structuredContent?.leaseExpiresAtEpochMs, "number");
      const retry = await callTool(mcp, "execution_orchestration", {
        action: "execute",
        input: { projectName, executionProfile: "resumed-watcher-run", suiteRunId },
      });
      assert.equal(retry.structuredContent?.status, "pass");
      assert.equal(retry.structuredContent?.suiteRunId, suiteRunId);
      assert.equal(
        (retry.structuredContent?.progressSummary as Record<string, unknown>)?.progressState,
        "terminal",
      );
      assert.equal(postCount, 1);
      assert.equal(indexChecks, 4);
      assert.ok(requests.includes(`GET /imports/${eventId}`));
      assert.ok(requests.includes(`GET /index/${eventId}`));

      const consumerRunId = String(
        (
          (retry.structuredContent?.planRuns as Array<Record<string, unknown>>).find(
            (entry) => entry.planName === "consumer",
          ) ?? {}
        ).runId ?? "",
      );
      assert.ok(consumerRunId);
      const database = new (require("node:sqlite").DatabaseSync)(
        path.join(workspaceRootAbs, ".mcpjvm", projectName, "run-state.sqlite"),
      );
      const suiteRow = database
        .prepare(
          "SELECT status, owner_id, lease_expires_at_epoch_ms FROM suite_runs WHERE suite_run_id = ?",
        )
        .get(suiteRunId);
      assert.equal(suiteRow.status, "pass");
      assert.equal(suiteRow.owner_id, null);
      assert.equal(suiteRow.lease_expires_at_epoch_ms, null);
      const watcherRow = database
        .prepare(
          "SELECT status, outcome, continuation_json, attempt_count FROM watcher_runs WHERE suite_run_id = ? AND plan_name = 'consumer' AND run_id = ? AND watcher_name = 'indexed'",
        )
        .get(suiteRunId, consumerRunId);
      assert.equal(watcherRow.status, "pass");
      assert.equal(watcherRow.outcome, "verified");
      assert.equal(watcherRow.continuation_json, null);
      assert.equal(watcherRow.attempt_count, 4);
      database.close();
    } finally {
      await mcp.close();
    }
  } finally {
    appServer?.close();
    if (fssync.existsSync(tmpRoot)) await fs.rm(tmpRoot, { recursive: true, force: true });
  }
});

test("[IT][execution_orchestration][execute] execution_orchestration classifies watcher outer poll exhaustion deterministically", async () => {
  const tmpRoot = await fs.mkdtemp(
    path.join(os.tmpdir(), "mcp-execution-orchestration-watcher-progress-it-"),
  );
  const workspaceRootAbs = path.join(tmpRoot, "workspace");
  const projectName = "test-project-watchers";
  const projectRootAbs = workspaceRootAbs;
  const probeConfigAbs = path.join(workspaceRootAbs, ".mcpjvm", "probe-config.json");
  const planName = "watcher-progress";
  const planRootAbs = path.join(
    workspaceRootAbs,
    ".mcpjvm",
    projectName,
    "plans",
    "regression",
    planName,
  );
  let watcherChecks = 0;

  const appServer = http.createServer(async (req, res) => {
    if (req.method === "POST" && req.url === "/events") {
      res.statusCode = 202;
      res.setHeader("content-type", "application/json");
      res.end(JSON.stringify({ eventId: "evt-900" }));
      return;
    }
    if (req.method === "GET" && req.url === "/index/evt-900") {
      watcherChecks += 1;
      if (watcherChecks === 1) {
        await new Promise((resolve) => setTimeout(resolve, 25));
        res.statusCode = 200;
        res.setHeader("content-type", "application/json");
        res.end(JSON.stringify({ state: "pending" }));
        return;
      }
      res.statusCode = 200;
      res.setHeader("content-type", "application/json");
      res.end(JSON.stringify({ state: "ready" }));
      return;
    }
    res.statusCode = 404;
    res.end(JSON.stringify({ reason: "missing" }));
  });
  const appPort = await listen(appServer);

  await writeJson(probeConfigAbs, {
    defaultProfile: "dev",
    profiles: {
      dev: {
        probes: {
          "gateway-service": {
            baseUrl: "http://127.0.0.1:9196",
            include: ["com.example.**"],
            exclude: [],
          },
        },
      },
    },
    workspaces: [{ root: workspaceRootAbs, profile: "dev" }],
  });

  await writeJson(path.join(workspaceRootAbs, ".mcpjvm", projectName, "projects.json"), {
    workspaces: [
      {
        projectRoot: projectRootAbs,
        defaults: {
          requestTimeoutMs: 100,
          retryMax: 3,
          orchestrator: {
            resumePollMax: 1,
            resumePollIntervalMs: 5,
            resumePollTimeoutMs: 20,
          },
        },
        executionProfiles: [
          {
            executionProfile: "watcher-progress-run",
            executionPolicy: "stop_on_fail",
            plans: [{ order: 1, planName, onFail: "inherit" }],
          },
        ],
      },
    ],
  });
  await writeJson(path.join(planRootAbs, "metadata.json"), {
    specVersion: "1.0.0",
    execution: {
      intent: "regression",
      probeVerification: false,
      pinStrictProbeKey: false,
      discoveryPolicy: "allow_discoverable_prerequisites",
    },
  });
  await writeJson(path.join(planRootAbs, "contract.json"), {
    targets: [{ type: "class_method", selectors: { fqcn: "x.A", method: "m" } }],
    prerequisites: [
      {
        key: "apiBaseUrl",
        required: true,
        secret: false,
        provisioning: "user_input",
        default: `http://127.0.0.1:${appPort}`,
      },
    ],
    steps: [
      {
        order: 1,
        id: "trigger_event",
        targetRef: 0,
        protocol: "http",
        transport: { http: { method: "POST", pathTemplate: "/events" } },
        extract: [{ from: "response.bodyJson.eventId", as: "eventId", required: true }],
        expect: [
          {
            id: "accepted",
            actualPath: "response.statusCode",
            operator: "field_equals",
            expected: 202,
          },
        ],
      },
    ],
    watchers: [
      {
        id: "indexed_ready",
        dependency: { stepOrder: 1 },
        provider: {
          type: "http",
          transport: {
            request: {
              method: "GET",
              url: `http://127.0.0.1:${appPort}/index/\${eventId}`,
            },
          },
        },
        waitPolicy: { timeoutMs: 1_000, retryMax: 3 },
        expect: [
          {
            id: "ready",
            actualPath: "response.bodyJson.state",
            operator: "field_equals",
            expected: "ready",
          },
        ],
      },
    ],
  });

  let mcp: Awaited<ReturnType<typeof startMcpClient>> | undefined;
  try {
    mcp = await startMcpClient({
      workspaceRootAbs,
      probeBaseUrl: "http://127.0.0.1:9196",
      extraEnv: { MCP_PROBE_CONFIG_FILE: probeConfigAbs },
    });

    const cutover = await callTool(mcp, "artifact_management", {
      artifactType: "run_result",
      action: "cutover",
      input: { projectName },
    });
    assert.equal(cutover.structuredContent?.status, "ok");

    const first = await callTool(mcp, "execution_orchestration", {
      action: "execute",
      input: {
        projectName,
        executionProfile: "watcher-progress-run",
      },
    });

    assert.equal(first.structuredContent?.status, "blocked");
    assert.equal(first.structuredContent?.reasonCode, "orchestrator_poll_limit_exhausted");
    assert.equal(
      (first.structuredContent?.progressSummary as Record<string, unknown>)?.progressState,
      "terminal",
    );
    assert.equal(
      (
        (first.structuredContent?.progressSummary as Record<string, unknown>)?.activePlan as Record<
          string,
          unknown
        >
      )?.phase,
      "watchers",
    );
    assert.equal(
      (
        (
          (first.structuredContent?.progressSummary as Record<string, unknown>)
            ?.activePlan as Record<string, unknown>
        )?.waitingOn as Record<string, unknown>
      )?.targetId,
      "indexed_ready",
    );

    const suiteRunId = String(first.structuredContent?.suiteRunId ?? "");
    assert.equal(first.structuredContent?.stateSurface, "run_state");
    assert.equal(first.structuredContent?.statusArtifactPath, undefined);
    const persistedSummary = first.structuredContent?.progressSummary as Record<string, unknown>;
    assert.equal(persistedSummary.progressState, "terminal");
    const persistedActivePlan = persistedSummary.activePlan as Record<string, unknown>;
    assert.equal(persistedActivePlan?.phase, "watchers");
    assert.equal(typeof persistedActivePlan?.deadlineAtEpochMs, "number");
    assert.equal(
      (
        (persistedSummary.activePlan as Record<string, unknown>)?.waitingOn as Record<
          string,
          unknown
        >
      )?.targetId,
      "indexed_ready",
    );

    const watcherChecksBeforeTerminalResume = watcherChecks;
    await new Promise((resolve) =>
      setTimeout(
        resolve,
        Math.max(0, Number(persistedActivePlan.deadlineAtEpochMs) - Date.now() + 25),
      ),
    );
    await writeJson(path.join(workspaceRootAbs, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: projectRootAbs,
          defaults: {
            requestTimeoutMs: 100,
            retryMax: 3,
            orchestrator: {
              resumePollMax: 1,
              resumePollIntervalMs: 5,
              resumePollTimeoutMs: 1000,
            },
          },
          executionProfiles: [
            {
              executionProfile: "watcher-progress-run",
              executionPolicy: "stop_on_fail",
              plans: [{ order: 1, planName, onFail: "inherit" }],
            },
          ],
        },
      ],
    });

    const second = await callTool(mcp, "execution_orchestration", {
      action: "execute",
      input: {
        projectName,
        executionProfile: "watcher-progress-run",
        suiteRunId,
      },
    });

    assert.equal(second.structuredContent?.status, "blocked");
    assert.equal(second.structuredContent?.reasonCode, "watcher_timeout");
    assert.equal(second.structuredContent?.stateSurface, "run_state");
    assert.equal(watcherChecks, watcherChecksBeforeTerminalResume);
  } finally {
    appServer.close();
    await mcp?.close();
    if (fssync.existsSync(tmpRoot)) {
      await fs.rm(tmpRoot, { recursive: true, force: true });
    }
  }
});

test("[IT][execution_orchestration][execute] execution_orchestration classifies outer progress stall deterministically for external verification waits", async () => {
  const tmpRoot = await fs.mkdtemp(
    path.join(os.tmpdir(), "mcp-execution-orchestration-external-progress-it-"),
  );
  const workspaceRootAbs = path.join(tmpRoot, "workspace");
  const projectName = "test-project-external";
  const projectRootAbs = workspaceRootAbs;
  const probeConfigAbs = path.join(workspaceRootAbs, ".mcpjvm", "probe-config.json");
  const planName = "external-progress";
  const planRootAbs = path.join(
    workspaceRootAbs,
    ".mcpjvm",
    projectName,
    "plans",
    "regression",
    planName,
  );
  let triggerCalls = 0;
  let verificationCalls = 0;

  const appServer = http.createServer(async (req, res) => {
    if (req.method === "POST" && req.url === "/tasks") {
      triggerCalls += 1;
      await new Promise((resolve) => setTimeout(resolve, 15));
      res.statusCode = 202;
      res.setHeader("content-type", "application/json");
      res.end(JSON.stringify({ taskId: "task-900" }));
      return;
    }
    if (req.method === "GET" && req.url === "/tasks/task-900") {
      verificationCalls += 1;
      res.statusCode = 200;
      res.setHeader("content-type", "application/json");
      res.end(JSON.stringify({ completed: true }));
      return;
    }
    res.statusCode = 404;
    res.end(JSON.stringify({ reason: "missing" }));
  });
  const appPort = await listen(appServer);

  await writeJson(probeConfigAbs, {
    defaultProfile: "dev",
    profiles: {
      dev: {
        probes: {
          "gateway-service": {
            baseUrl: "http://127.0.0.1:9196",
            include: ["com.example.**"],
            exclude: [],
          },
        },
      },
    },
    workspaces: [{ root: workspaceRootAbs, profile: "dev" }],
  });

  await writeJson(path.join(workspaceRootAbs, ".mcpjvm", projectName, "projects.json"), {
    workspaces: [
      {
        projectRoot: projectRootAbs,
        defaults: {
          requestTimeoutMs: 100,
          retryMax: 2,
          orchestrator: {
            resumePollMax: 2,
            resumePollIntervalMs: 5,
            resumePollTimeoutMs: 10,
          },
        },
        executionProfiles: [
          {
            executionProfile: "external-progress-run",
            executionPolicy: "stop_on_fail",
            plans: [{ order: 1, planName, onFail: "inherit" }],
          },
        ],
      },
    ],
  });
  await writeJson(path.join(planRootAbs, "metadata.json"), {
    specVersion: "1.0.0",
    execution: {
      intent: "regression",
      probeVerification: false,
      pinStrictProbeKey: false,
      discoveryPolicy: "allow_discoverable_prerequisites",
    },
  });
  await writeJson(path.join(planRootAbs, "contract.json"), {
    targets: [{ type: "class_method", selectors: { fqcn: "x.A", method: "m" } }],
    prerequisites: [
      {
        key: "apiBaseUrl",
        required: true,
        secret: false,
        provisioning: "user_input",
        default: `http://127.0.0.1:${appPort}`,
      },
    ],
    steps: [
      {
        order: 1,
        id: "submit_task",
        targetRef: 0,
        protocol: "http",
        transport: { http: { method: "POST", pathTemplate: "/tasks" } },
        extract: [{ from: "response.bodyJson.taskId", as: "taskId", required: true }],
        expect: [
          {
            id: "accepted",
            actualPath: "response.statusCode",
            operator: "field_equals",
            expected: 202,
          },
        ],
      },
    ],
    externalVerification: [
      {
        id: "verify_task_completed",
        provider: { type: "http" },
        request: { http: { method: "GET", url: `http://127.0.0.1:${appPort}/tasks/\${taskId}` } },
        expect: [
          {
            id: "completed",
            actualPath: "response.bodyJson.completed",
            operator: "field_equals",
            expected: true,
          },
        ],
      },
    ],
  });

  let mcp: Awaited<ReturnType<typeof startMcpClient>> | undefined;
  try {
    mcp = await startMcpClient({
      workspaceRootAbs,
      probeBaseUrl: "http://127.0.0.1:9196",
      extraEnv: { MCP_PROBE_CONFIG_FILE: probeConfigAbs },
    });

    const first = await callTool(mcp, "execution_orchestration", {
      action: "execute",
      input: {
        projectName,
        executionProfile: "external-progress-run",
      },
    });

    assert.equal(first.structuredContent?.status, "blocked");
    assert.equal(first.structuredContent?.reasonCode, "orchestrator_progress_stalled");
    assert.equal(
      (first.structuredContent?.progressSummary as Record<string, unknown>)?.progressState,
      "terminal",
    );
    assert.equal(
      (
        (first.structuredContent?.progressSummary as Record<string, unknown>)?.activePlan as Record<
          string,
          unknown
        >
      )?.phase,
      "external_verification",
    );
    assert.equal(
      (
        (
          (first.structuredContent?.progressSummary as Record<string, unknown>)
            ?.activePlan as Record<string, unknown>
        )?.waitingOn as Record<string, unknown>
      )?.targetId,
      "verify_task_completed",
    );

    const suiteRunId = String(first.structuredContent?.suiteRunId ?? "");
    const persisted = JSON.parse(
      await fs.readFile(
        path.join(
          workspaceRootAbs,
          ".mcpjvm",
          projectName,
          "suite-runs",
          suiteRunId,
          "execution_orchestration.result.json",
        ),
        "utf8",
      ),
    ) as Record<string, unknown>;
    assert.equal(persisted.reasonCode, "orchestrator_progress_stalled");
    const persistedSummary = persisted.progressSummary as Record<string, unknown>;
    assert.equal(persistedSummary.progressState, "terminal");
    assert.equal(
      (persistedSummary.activePlan as Record<string, unknown>)?.phase,
      "external_verification",
    );
    assert.equal(
      (
        (persistedSummary.activePlan as Record<string, unknown>)?.waitingOn as Record<
          string,
          unknown
        >
      )?.targetId,
      "verify_task_completed",
    );
    assert.equal(triggerCalls, 1);
    assert.equal(verificationCalls, 0);
  } finally {
    appServer.close();
    await mcp?.close();
    if (fssync.existsSync(tmpRoot)) {
      await fs.rm(tmpRoot, { recursive: true, force: true });
    }
  }
});
