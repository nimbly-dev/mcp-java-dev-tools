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
  renderPerformanceResultFromArtifacts,
  startMcpClient,
} from "./execution_orchestration.shared";

test("[IT][execution_orchestration][execute] execution_orchestration executes performance suite profiles through the same MCP Tool", async () => {
  const tmpRoot = await fs.mkdtemp(
    path.join(os.tmpdir(), "mcp-execution-orchestration-performance-it-"),
  );
  const workspaceRootAbs = path.join(tmpRoot, "workspace");
  const projectName = "test-project-performance";
  const projectRootAbs = workspaceRootAbs;
  const lineKey = "com.example.catalog.CatalogService#search:42";
  let lineHitCount = 0;

  const appServer = http.createServer((_req, res) => {
    lineHitCount += 1;
    res.statusCode = 200;
    res.setHeader("content-type", "application/json");
    res.end('{"ok":true}');
  });
  const probeServer = http.createServer((req, res) => {
    const url = new URL(req.url ?? "/", "http://127.0.0.1");
    if (req.method === "POST" && url.pathname === "/__probe/reset") {
      lineHitCount = 0;
      res.statusCode = 200;
      res.setHeader("content-type", "application/json");
      res.end(
        JSON.stringify({
          ok: true,
          key: lineKey,
          lineResolvable: true,
          lineValidation: "resolvable",
        }),
      );
      return;
    }
    if (req.method === "GET" && url.pathname === "/__probe/status") {
      const key = url.searchParams.get("key") ?? lineKey;
      res.statusCode = 200;
      res.setHeader("content-type", "application/json");
      res.end(
        JSON.stringify({
          key,
          hitCount: lineHitCount,
          lastHitEpoch: lineHitCount > 0 ? Date.now() : 0,
          mode: "observe",
          lineResolvable: true,
          lineValidation: "resolvable",
        }),
      );
      return;
    }
    res.statusCode = 404;
    res.end("not found");
  });

  const appPort = await listen(appServer);
  const probePort = await listen(probeServer);
  const probeBaseUrl = `http://127.0.0.1:${probePort}`;
  const probeConfigAbs = path.join(workspaceRootAbs, ".mcpjvm", "probe-config.json");

  await writeJson(probeConfigAbs, {
    defaultProfile: "dev",
    profiles: {
      dev: {
        probes: {
          "catalog-service": { baseUrl: probeBaseUrl, include: ["com.example.**"], exclude: [] },
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
            executionProfile: "test-performance-stress-suite",
            suiteType: "performance",
            executionPolicy: "stop_on_fail",
            runtimeConfig: {
              requestTimeoutMs: 250,
            },
            plans: [{ order: 1, planName: "catalog-search-perf", onFail: "inherit" }],
          },
        ],
      },
    ],
  });
  await writeJson(
    path.join(
      workspaceRootAbs,
      ".mcpjvm",
      projectName,
      "plans",
      "performance",
      "catalog-search-perf",
      "metadata.json",
    ),
    {
      specVersion: "0.1.0",
      suiteType: "performance",
      execution: { intent: "performance" },
    },
  );
  await writeJson(
    path.join(
      workspaceRootAbs,
      ".mcpjvm",
      projectName,
      "plans",
      "performance",
      "catalog-search-perf",
      "contract.json",
    ),
    {
      entrypoints: [
        {
          transport: {
            protocol: "http",
            baseUrl: `http://127.0.0.1:${appPort}`,
            wrappedOnly: true,
          },
          request: {
            method: "GET",
            path: "/search",
          },
        },
      ],
      observationTargets: {
        requiredLineHits: [lineKey],
      },
      loadModel: {
        mode: "concurrency",
        concurrency: 1,
        rampUpSeconds: 0,
        durationSeconds: 1,
      },
      successCriteria: {
        maxErrorRatePct: 0,
        minThroughputPerSec: 0.5,
        p95LatencyMs: 500,
      },
      analysis: {
        correlation: {
          enabled: true,
          kind: "sampled_attribution",
          anchorSource: "msta_resolved_anchors",
          requireLineHit: true,
          requireMsta: false,
        },
      },
    },
  );

  let mcp: Awaited<ReturnType<typeof startMcpClient>> | undefined;
  try {
    mcp = await startMcpClient({
      workspaceRootAbs,
      probeBaseUrl,
      extraEnv: { MCP_PROBE_CONFIG_FILE: probeConfigAbs },
    });

    const out = await callTool(mcp, "execution_orchestration", {
      action: "execute",
      input: {
        projectName,
        executionProfile: "test-performance-stress-suite",
      },
    });

    assert.equal(out.structuredContent?.resultType, "execution_orchestration");
    assert.equal(out.structuredContent?.status, "pass");
    assert.notEqual(out.structuredContent?.reasonCode, "runtime_suite_invalid");
    const planRuns = Array.isArray(out.structuredContent?.planRuns)
      ? (out.structuredContent?.planRuns as Array<Record<string, unknown>>)
      : [];
    assert.equal(planRuns.length, 1);
    assert.equal(planRuns[0]?.status, "executed");
    assert.equal(planRuns[0]?.runStatus, "pass");
    assert.equal(lineHitCount > 0, true);
    const runId = planRuns[0]?.runId;
    assert.equal(typeof runId, "string");
    if (typeof runId !== "string") throw new Error("MCP performance run did not persist runId");
    const rendered = await renderPerformanceResultFromArtifacts({
      runDirAbs: path.join(
        workspaceRootAbs,
        ".mcpjvm",
        projectName,
        "plans",
        "performance",
        "catalog-search-perf",
        "runs",
        runId,
      ),
    });
    assert.equal(rendered.status, "rendered");
    assert.match(rendered.text, /Correlation: n\/a \(msta_not_configured\)/);
  } finally {
    appServer.close();
    probeServer.close();
    await mcp?.close();
    if (fssync.existsSync(tmpRoot)) {
      await fs.rm(tmpRoot, { recursive: true, force: true });
    }
  }
});

test("[IT][execution_orchestration][execute] execution_orchestration resolves extracted context through later step, Watcher, and external verification", async () => {
  const tmpRoot = await fs.mkdtemp(
    path.join(os.tmpdir(), "mcp-execution-orchestration-watcher-success-it-"),
  );
  const workspaceRootAbs = path.join(tmpRoot, "workspace");
  const projectName = "test-project-watchers";
  const projectRootAbs = workspaceRootAbs;
  const probeConfigAbs = path.join(workspaceRootAbs, ".mcpjvm", "probe-config.json");
  const planName = "watcher-success";
  const planRootAbs = path.join(
    workspaceRootAbs,
    ".mcpjvm",
    projectName,
    "plans",
    "regression",
    planName,
  );
  const runRootAbs = path.join(planRootAbs, "runs");
  let stateChecks = 0;

  const appServer = http.createServer((req, res) => {
    if (req.method === "POST" && req.url === "/events") {
      res.statusCode = 202;
      res.setHeader("content-type", "application/json");
      res.end(JSON.stringify({ id: "evt-123" }));
      return;
    }
    if (req.method === "GET" && req.url === "/index/evt-123") {
      stateChecks += 1;
      res.statusCode = 200;
      res.setHeader("content-type", "application/json");
      res.end(JSON.stringify({ state: stateChecks >= 3 ? "ready" : "pending" }));
      return;
    }
    if (req.method === "GET" && req.url === "/imports/evt-123") {
      res.statusCode = 200;
      res.setHeader("content-type", "application/json");
      res.end(JSON.stringify({ status: "accepted", id: "evt-123" }));
      return;
    }
    if (req.method === "GET" && req.url === "/objects/evt-123") {
      res.statusCode = 200;
      res.setHeader("content-type", "application/json");
      res.end(JSON.stringify({ importJobId: "evt-123" }));
      return;
    }
    res.statusCode = 404;
    res.setHeader("content-type", "application/json");
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
          requestTimeoutMs: 120,
          retryMax: 3,
        },
        executionProfiles: [
          {
            executionProfile: "watcher-success-run",
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
        transport: {
          http: {
            method: "POST",
            pathTemplate: "/events",
          },
        },
        extract: [{ from: "response.bodyJson.id", as: "eventId", required: true }],
        expect: [
          {
            id: "accepted",
            actualPath: "response.statusCode",
            operator: "field_equals",
            expected: 202,
          },
        ],
      },
      {
        order: 2,
        id: "read_import_status",
        targetRef: 0,
        protocol: "http",
        transport: { http: { method: "GET", pathTemplate: "/imports/${eventId}" } },
        expect: [
          {
            id: "readable",
            actualPath: "response.statusCode",
            operator: "field_equals",
            expected: 200,
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
    externalVerification: [
      {
        id: "verify_object_imported",
        provider: { type: "http" },
        request: {
          http: { method: "GET", url: `http://127.0.0.1:${appPort}/objects/\${eventId}` },
        },
        expect: [
          {
            id: "object_available",
            actualPath: "response.statusCode",
            operator: "field_equals",
            expected: 200,
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

    const out = await callTool(mcp, "execution_orchestration", {
      action: "execute",
      input: {
        projectName,
        executionProfile: "watcher-success-run",
      },
    });

    assert.equal(out.structuredContent?.resultType, "execution_orchestration");
    assert.equal(out.structuredContent?.status, "pass");
    assert.equal(stateChecks, 3);
    const planRuns = Array.isArray(out.structuredContent?.planRuns)
      ? (out.structuredContent?.planRuns as Array<Record<string, unknown>>)
      : [];
    assert.equal(planRuns.length, 1);
    assert.equal(planRuns[0]?.runStatus, "pass");

    const runDirs = await fs.readdir(runRootAbs);
    assert.equal(runDirs.length, 1);
    const runDir = runDirs[0];
    if (!runDir) throw new Error("expected one watcher run directory");
    const executionResult = JSON.parse(
      await fs.readFile(path.join(runRootAbs, runDir, "execution.result.json"), "utf8"),
    ) as Record<string, unknown>;
    const evidence = JSON.parse(
      await fs.readFile(path.join(runRootAbs, runDir, "evidence.json"), "utf8"),
    ) as Record<string, unknown>;
    const watchers = executionResult.watchers as Array<Record<string, unknown>>;
    assert.equal(executionResult.triggerStatus, "pass");
    assert.equal(executionResult.watcherStatus, "pass");
    assert.equal(Array.isArray(watchers), true);
    assert.equal(watchers[0]?.status, "pass");
    assert.equal(watchers[0]?.attemptCount, 3);
    assert.equal(Array.isArray(evidence.watcherExecutions), true);
    assert.equal((evidence.watcherExecutions as Array<Record<string, unknown>>)[0]?.status, "ok");
  } finally {
    appServer.close();
    await mcp?.close();
    if (fssync.existsSync(tmpRoot)) {
      await fs.rm(tmpRoot, { recursive: true, force: true });
    }
  }
});

test("[IT][execution_orchestration][execute] execution_orchestration fails closed when watcher response normalization fails", async () => {
  const tmpRoot = await fs.mkdtemp(
    path.join(os.tmpdir(), "mcp-execution-orchestration-watcher-normalization-it-"),
  );
  const workspaceRootAbs = path.join(tmpRoot, "workspace");
  const projectName = "test-project-watchers";
  const projectRootAbs = workspaceRootAbs;
  const probeConfigAbs = path.join(workspaceRootAbs, ".mcpjvm", "probe-config.json");
  const planName = "watcher-normalization-failure";
  const planRootAbs = path.join(
    workspaceRootAbs,
    ".mcpjvm",
    projectName,
    "plans",
    "regression",
    planName,
  );
  const runRootAbs = path.join(planRootAbs, "runs");

  let watcherCalls = 0;
  const appServer = http.createServer((req, res) => {
    if (req.method === "POST" && req.url === "/events") {
      res.statusCode = 202;
      res.setHeader("content-type", "application/json");
      res.end(JSON.stringify({ eventId: "evt-401" }));
      return;
    }
    if (req.method === "GET" && req.url === "/index/evt-401") {
      watcherCalls += 1;
      res.statusCode = 200;
      res.setHeader("content-type", "application/json");
      res.end("not-json");
      return;
    }
    res.statusCode = 404;
    res.end();
  });

  let mcp: Awaited<ReturnType<typeof startMcpClient>> | undefined;
  try {
    const appPort = await listen(appServer);
    await fs.mkdir(runRootAbs, { recursive: true });
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
          defaults: { requestTimeoutMs: 100, retryMax: 2 },
          executionProfiles: [
            {
              executionProfile: "watcher-normalization-run",
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
            transport: { http: { method: "GET", pathTemplate: "/index/${eventId}" } },
            config: {
              response: {
                bodyFormat: "json",
              },
            },
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
    });

    mcp = await startMcpClient({
      workspaceRootAbs,
      probeBaseUrl: "http://127.0.0.1:9196",
      extraEnv: { MCP_PROBE_CONFIG_FILE: probeConfigAbs },
    });

    const out = await callTool(mcp, "execution_orchestration", {
      action: "execute",
      input: {
        projectName,
        executionProfile: "watcher-normalization-run",
      },
    });

    assert.equal(out.structuredContent?.resultType, "execution_orchestration");
    assert.equal(out.structuredContent?.status, "blocked");
    const planRuns = Array.isArray(out.structuredContent?.planRuns)
      ? (out.structuredContent?.planRuns as Array<Record<string, unknown>>)
      : [];
    assert.equal(planRuns.length, 1);
    assert.equal(planRuns[0]?.blockedReasonCode, "watcher_configuration_invalid");
    assert.equal(watcherCalls, 1);
  } finally {
    appServer.close();
    await mcp?.close();
    if (fssync.existsSync(tmpRoot)) {
      await fs.rm(tmpRoot, { recursive: true, force: true });
    }
  }
});

test("[IT][execution_orchestration][execute] execution_orchestration fails closed when watcher target stays unreachable", async () => {
  const tmpRoot = await fs.mkdtemp(
    path.join(os.tmpdir(), "mcp-execution-orchestration-watcher-unreachable-it-"),
  );
  const workspaceRootAbs = path.join(tmpRoot, "workspace");
  const projectName = "test-project-watchers";
  const projectRootAbs = workspaceRootAbs;
  const probeConfigAbs = path.join(workspaceRootAbs, ".mcpjvm", "probe-config.json");
  const planName = "watcher-unreachable";
  const planRootAbs = path.join(
    workspaceRootAbs,
    ".mcpjvm",
    projectName,
    "plans",
    "regression",
    planName,
  );
  const runRootAbs = path.join(planRootAbs, "runs");
  const appServer = http.createServer((req, res) => {
    if (req.method === "POST" && req.url === "/events") {
      res.statusCode = 202;
      res.setHeader("content-type", "application/json");
      res.end(JSON.stringify({ ok: true }));
      return;
    }
    res.statusCode = 404;
    res.end("not found");
  });
  const appPort = await listen(appServer);

  const unreachableServer = http.createServer((_req, res) => {
    res.statusCode = 200;
    res.end("unused");
  });
  const unreachablePort = await listen(unreachableServer);
  await new Promise<void>((resolve, reject) =>
    unreachableServer.close((error) => (error ? reject(error) : resolve())),
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
        defaults: { requestTimeoutMs: 100, retryMax: 2 },
        executionProfiles: [
          {
            executionProfile: "watcher-unreachable-run",
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
        transport: {
          http: {
            method: "POST",
            pathTemplate: "/events",
          },
        },
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
              url: `http://127.0.0.1:${unreachablePort}/index/evt-123`,
            },
          },
        },
        waitPolicy: { timeoutMs: 80, retryMax: 2 },
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

    const out = await callTool(mcp, "execution_orchestration", {
      action: "execute",
      input: {
        projectName,
        executionProfile: "watcher-unreachable-run",
      },
    });

    assert.equal(out.structuredContent?.resultType, "execution_orchestration");
    assert.equal(out.structuredContent?.status, "blocked");
    const planRuns = Array.isArray(out.structuredContent?.planRuns)
      ? (out.structuredContent?.planRuns as Array<Record<string, unknown>>)
      : [];
    assert.equal(planRuns.length, 1);
    assert.equal(planRuns[0]?.runStatus, "blocked");

    const runDirs = await fs.readdir(runRootAbs);
    assert.equal(runDirs.length, 1);
    const runDir = runDirs[0];
    if (!runDir) throw new Error("expected one watcher run directory");
    const executionResult = JSON.parse(
      await fs.readFile(path.join(runRootAbs, runDir, "execution.result.json"), "utf8"),
    ) as Record<string, unknown>;
    const watchers = executionResult.watchers as Array<Record<string, unknown>>;
    assert.equal(executionResult.triggerStatus, "pass");
    assert.equal(executionResult.watcherStatus, "blocked");
    assert.equal(watchers[0]?.status, "blocked_runtime");
    assert.equal(watchers[0]?.reasonCode, "watcher_target_unreachable");
  } finally {
    appServer.close();
    await mcp?.close();
    if (fssync.existsSync(tmpRoot)) {
      await fs.rm(tmpRoot, { recursive: true, force: true });
    }
  }
});
