#!/usr/bin/env node
const fs = require("node:fs");
const path = require("node:path");
const cancellation = new AbortController();
const MAX_SAMPLES = 100000;
process.on("SIGINT", () => cancellation.abort());
process.on("SIGTERM", () => cancellation.abort());

function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === "--bundle") out.bundlePath = argv[++i];
    else if (arg === "--env-file") out.envFilePath = argv[++i];
    else if (arg === "--export-dir") out.exportDir = argv[++i];
  }
  return out;
}

function fail(code, detail) {
  const payload = { status: "blocked", reasonCode: code, detail };
  process.stderr.write(`${JSON.stringify(payload, null, 2)}\n`);
  process.exit(1);
}

function parseEnvFile(filePath) {
  const env = {};
  const text = fs.existsSync(filePath) ? fs.readFileSync(filePath, "utf8") : "";
  for (const line of text.split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith("#")) continue;
    const index = trimmed.indexOf("=");
    if (index <= 0) continue;
    const key = trimmed.slice(0, index).trim();
    let value = trimmed.slice(index + 1);
    if (value.startsWith('"') && value.endsWith('"')) value = JSON.parse(value);
    env[key] = value;
  }
  return env;
}

function toEnvKey(input) {
  const raw = String(input).trim().replace(/^env[.:_-]/i, "");
  const normalized = raw
    .replace(/([a-z0-9])([A-Z])/g, "$1_$2")
    .replace(/[^A-Za-z0-9_]/g, "_")
    .replace(/_+/g, "_")
    .replace(/^_+|_+$/g, "")
    .toUpperCase();
  const safe = normalized.length > 0 ? normalized : "REQUIRED_INPUT";
  return /^[A-Z_]/.test(safe) ? safe : `V_${safe}`;
}

function substitutePlaceholders(value, context) {
  if (typeof value === "string") {
    return value.replace(/\$\{([^}]+)\}/g, (_full, key) => {
      const envKey = toEnvKey(key);
      const replacement = context[key] ?? context[envKey] ?? context[canonicalEnvKey(key)];
      if (typeof replacement !== "string" || replacement.length === 0) {
        throw new Error(`missing_context:${envKey}`);
      }
      return replacement;
    });
  }
  if (Array.isArray(value)) {
    return value.map((entry) => substitutePlaceholders(entry, context));
  }
  if (value && typeof value === "object") {
    const out = {};
    for (const [key, child] of Object.entries(value)) {
      out[key] = substitutePlaceholders(child, context);
    }
    return out;
  }
  return value;
}

function canonicalEnvKey(key) {
  return String(key).toUpperCase().replace(/[^A-Z0-9]+/g, "_");
}

function bindContext(context, key, value) {
  context[key] = value;
  context[toEnvKey(key)] = value;
  context[canonicalEnvKey(key)] = value;
}

async function fetchWithTimeout(url, options, timeoutMs, cleanup = false) {
  const timeout = AbortSignal.timeout(timeoutMs);
  const signal = cleanup ? timeout : AbortSignal.any([timeout, cancellation.signal]);
  return fetch(url, { ...options, signal });
}

function errorDetail(error) {
  return error instanceof Error ? error.message : String(error);
}

function escapeXml(value) {
  return String(value)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&apos;");
}

function renderHeaderManager(headers) {
  if (!headers || Object.keys(headers).length === 0) {
    return "";
  }
  const items = Object.entries(headers)
    .map(
      ([name, value]) => `              <elementProp name="${escapeXml(name)}" elementType="Header">
                <stringProp name="Header.name">${escapeXml(name)}</stringProp>
                <stringProp name="Header.value">${escapeXml(value)}</stringProp>
              </elementProp>`
    )
    .join("\n");
  return `        <HeaderManager guiclass="HeaderPanel" testclass="HeaderManager" testname="HTTP Header Manager" enabled="true">
          <collectionProp name="HeaderManager.headers">
${items}
          </collectionProp>
        </HeaderManager>
        <hashTree/>`;
}

function renderGeneratedHttpJmx(args) {
  const serializedBody =
    typeof args.request.body === "undefined"
      ? ""
      : typeof args.request.body === "string"
        ? args.request.body
        : JSON.stringify(args.request.body);
  const hasBody = serializedBody.length > 0;
  const timeoutMs =
    typeof args.request.timeoutMs === "number" && args.request.timeoutMs > 0
      ? String(args.request.timeoutMs)
      : "";
  const bodyBlock = hasBody
    ? `          <boolProp name="HTTPSampler.postBodyRaw">true</boolProp>
          <elementProp name="HTTPsampler.Arguments" elementType="Arguments">
            <collectionProp name="Arguments.arguments">
              <elementProp name="" elementType="HTTPArgument">
                <boolProp name="HTTPArgument.always_encode">false</boolProp>
                <stringProp name="Argument.value">${escapeXml(serializedBody)}</stringProp>
                <stringProp name="Argument.metadata">=</stringProp>
              </elementProp>
            </collectionProp>
          </elementProp>`
    : `          <boolProp name="HTTPSampler.postBodyRaw">false</boolProp>
          <elementProp name="HTTPsampler.Arguments" elementType="Arguments">
            <collectionProp name="Arguments.arguments"/>
          </elementProp>`;

  return `<?xml version="1.0" encoding="UTF-8"?>
<jmeterTestPlan version="1.2" properties="5.0" jmeter="5.6.3">
  <hashTree>
    <TestPlan guiclass="TestPlanGui" testclass="TestPlan" testname="${escapeXml(args.planName)}" enabled="true">
      <stringProp name="TestPlan.comments"></stringProp>
      <boolProp name="TestPlan.functional_mode">false</boolProp>
      <boolProp name="TestPlan.tearDown_on_shutdown">true</boolProp>
      <boolProp name="TestPlan.serialize_threadgroups">false</boolProp>
      <elementProp name="TestPlan.user_defined_variables" elementType="Arguments" guiclass="ArgumentsPanel" testclass="Arguments" testname="User Defined Variables" enabled="true">
        <collectionProp name="Arguments.arguments"/>
      </elementProp>
      <stringProp name="TestPlan.user_define_classpath"></stringProp>
    </TestPlan>
    <hashTree>
      <ThreadGroup guiclass="ThreadGroupGui" testclass="ThreadGroup" testname="Performance Threads" enabled="true">
        <stringProp name="ThreadGroup.on_sample_error">continue</stringProp>
        <elementProp name="ThreadGroup.main_controller" elementType="LoopController" guiclass="LoopControlPanel" testclass="LoopController" testname="Loop Controller" enabled="true">
          <boolProp name="LoopController.continue_forever">false</boolProp>
          <intProp name="LoopController.loops">-1</intProp>
        </elementProp>
        <stringProp name="ThreadGroup.num_threads">${String(args.loadModel.concurrency)}</stringProp>
        <stringProp name="ThreadGroup.ramp_time">${String(args.loadModel.rampUpSeconds)}</stringProp>
        <boolProp name="ThreadGroup.scheduler">true</boolProp>
        <stringProp name="ThreadGroup.duration">${String(args.loadModel.durationSeconds)}</stringProp>
        <stringProp name="ThreadGroup.delay">0</stringProp>
        <boolProp name="ThreadGroup.same_user_on_next_iteration">true</boolProp>
      </ThreadGroup>
      <hashTree>
${renderHeaderManager(args.request.headers)}
        <HTTPSamplerProxy guiclass="HttpTestSampleGui" testclass="HTTPSamplerProxy" testname="${escapeXml(args.request.method)} ${escapeXml(args.request.url)}" enabled="true">
${bodyBlock}
          <stringProp name="HTTPSampler.domain"></stringProp>
          <stringProp name="HTTPSampler.port"></stringProp>
          <stringProp name="HTTPSampler.protocol"></stringProp>
          <stringProp name="HTTPSampler.contentEncoding"></stringProp>
          <stringProp name="HTTPSampler.path">${escapeXml(args.request.url)}</stringProp>
          <stringProp name="HTTPSampler.method">${escapeXml(args.request.method)}</stringProp>
          <boolProp name="HTTPSampler.follow_redirects">true</boolProp>
          <boolProp name="HTTPSampler.auto_redirects">false</boolProp>
          <boolProp name="HTTPSampler.use_keepalive">true</boolProp>
          <boolProp name="HTTPSampler.DO_MULTIPART_POST">false</boolProp>
          <stringProp name="HTTPSampler.embedded_url_re"></stringProp>
          <stringProp name="HTTPSampler.connect_timeout">${timeoutMs}</stringProp>
          <stringProp name="HTTPSampler.response_timeout">${timeoutMs}</stringProp>
        </HTTPSamplerProxy>
        <hashTree/>
      </hashTree>
    </hashTree>
  </hashTree>
</jmeterTestPlan>
`;
}

function parseCsvLine(line) {
  const values = [];
  let current = "";
  let inQuotes = false;
  for (let index = 0; index < line.length; index += 1) {
    const char = line[index];
    const next = line[index + 1];
    if (char === '"') {
      if (inQuotes && next === '"') {
        current += '"';
        index += 1;
        continue;
      }
      inQuotes = !inQuotes;
      continue;
    }
    if (char === "," && !inQuotes) {
      values.push(current);
      current = "";
      continue;
    }
    current += char;
  }
  values.push(current);
  return values;
}

async function collectJmeterJtlMetrics(jtlPathAbs) {
  const text = fs.readFileSync(jtlPathAbs, "utf8");
  const lines = text
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line.length > 0);
  if (lines.length < 2) {
    return null;
  }
  const headers = parseCsvLine(lines[0]);
  const rows = lines.slice(1).map((line) => {
    const values = parseCsvLine(line);
    const row = {};
    for (let index = 0; index < headers.length; index += 1) {
      const header = headers[index];
      if (typeof header === "string" && header.length > 0) {
        row[header] = values[index] ?? "";
      }
    }
    return row;
  });
  const latenciesMs = rows
    .map((row) => Number(row.elapsed))
    .filter((value) => Number.isFinite(value) && value >= 0)
    .map((value) => Math.max(1, Math.round(value)));
  if (latenciesMs.length === 0) {
    return null;
  }
  const failedRequests = rows.reduce((count, row) => count + (row.success === "true" ? 0 : 1), 0);
  return {
    totalRequests: rows.length,
    failedRequests,
    latenciesMs,
  };
}

function fileExists(filePath) {
  return fs.existsSync(filePath);
}

function resolveJmeterExecutable(installationPath) {
  const candidates = [];
  const configuredHome =
    typeof installationPath === "string" && installationPath.trim().length > 0
      ? installationPath.trim()
      : typeof process.env.MCP_JAVA_DEV_TOOLS_JMETER_HOME === "string" &&
          process.env.MCP_JAVA_DEV_TOOLS_JMETER_HOME.trim().length > 0
        ? process.env.MCP_JAVA_DEV_TOOLS_JMETER_HOME.trim()
        : undefined;

  if (configuredHome) {
    if (process.platform === "win32") {
      candidates.push(path.join(configuredHome, "bin", "jmeter.bat"));
      candidates.push(path.join(configuredHome, "bin", "jmeter.cmd"));
    } else {
      candidates.push(path.join(configuredHome, "bin", "jmeter"));
    }
  }

  const pathEntries = String(process.env.PATH ?? "")
    .split(path.delimiter)
    .map((entry) => entry.trim())
    .filter((entry) => entry.length > 0);
  for (const entry of pathEntries) {
    if (process.platform === "win32") {
      candidates.push(path.join(entry, "jmeter.bat"));
      candidates.push(path.join(entry, "jmeter.cmd"));
      candidates.push(path.join(entry, "jmeter.exe"));
    } else {
      candidates.push(path.join(entry, "jmeter"));
    }
  }

  for (const candidate of candidates) {
    if (fileExists(candidate)) {
      return candidate;
    }
  }
  return null;
}

function blockedPlanResult(reasonCode, detail, extra) {
  return {
    status: "blocked",
    runStatus: "blocked",
    reasonCode,
    detail,
    ...(extra && typeof extra === "object" ? extra : {}),
  };
}

async function probeReset(baseUrl, key) {
  const response = await fetchWithTimeout(`${baseUrl.replace(/\/+$/, "")}/__probe/reset`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ key }),
  }, 10000);
  const result = await response.json();
  if (!response.ok || result.key !== key || result.ok !== true) throw new Error("probe_reset_failed");
  return true;
}

async function probeWaitForHit(baseUrl, key, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const url = new URL(`${baseUrl.replace(/\/+$/, "")}/__probe/status`);
    url.searchParams.set("key", key);
    const response = await fetchWithTimeout(url.toString(), { method: "GET" }, 10000);
    const json = await response.json().catch(() => ({}));
    const probe = json && typeof json.probe === "object" && json.probe ? json.probe : json;
    if (response.ok && probe.key === key && typeof probe.hitCount === "number" && probe.hitCount > 0) {
      return { hit: true };
    }
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  return { hit: false, reasonCode: "timeout_no_inline_hit" };
}

async function profiler(baseUrl, input) {
  const url = `${baseUrl.replace(/\/+$/, "")}/__probe/profiler`;
  if (input.action === "download") {
    const downloadUrl = new URL(url);
    downloadUrl.searchParams.set("action", "download");
    downloadUrl.searchParams.set("sessionId", input.sessionId);
    const response = await fetchWithTimeout(downloadUrl.toString(), { method: "GET" }, 20000, true);
    if (!response.ok) {
      return { status: "profiler_download_failed", httpStatus: response.status };
    }
    const bytes = Buffer.from(await response.arrayBuffer());
    if (bytes.length === 0) {
      return { status: "profiler_download_failed", httpStatus: response.status, bytesWritten: 0 };
    }
    fs.writeFileSync(input.outputPath, bytes);
    return { status: "downloaded", outputPath: input.outputPath, bytesWritten: bytes.length };
  }
  const response = await fetchWithTimeout(url, {
    method: input.action === "status" ? "GET" : "POST",
    headers: input.action === "status" ? undefined : { "content-type": "application/json" },
    body: input.action === "status" ? undefined : JSON.stringify(input),
  }, 20000, input.action === "stop" || input.action === "status");
  const json = await response.json().catch(() => ({}));
  const profilerPayload = json && typeof json.profiler === "object" && json.profiler ? json.profiler : null;
  if (profilerPayload) {
    return {
      ...profilerPayload,
      httpStatus: response.status,
      ...(json && typeof json.error === "string" ? { responseError: json.error } : {}),
    };
  }
  return {
    status: "profiler_response_invalid",
    httpStatus: response.status,
    ...(json && typeof json.error === "string" ? { responseError: json.error } : {}),
    response: json,
  };
}

function resolveProfilerStartFailure(result, sessionId) {
  if (!result || typeof result !== "object") {
    return {
      reasonCode: "performance_profiler_start_failed",
      detail: "Profiler start response is missing structured content.",
    };
  }
  const detail =
    typeof result.detail === "string" && result.detail.trim().length > 0
      ? result.detail.trim()
      : typeof result.responseError === "string" && result.responseError.trim().length > 0
        ? result.responseError.trim()
        : undefined;
  if (typeof result.httpStatus === "number" && result.httpStatus >= 400) {
    return {
      reasonCode:
        typeof result.responseError === "string" && result.responseError.trim().length > 0
          ? result.responseError.trim()
          : "performance_profiler_start_failed",
      detail: detail ?? `Probe profiler start returned HTTP ${String(result.httpStatus)}.`,
    };
  }
  if (result.supported === false) {
    return {
      reasonCode: detail ?? "performance_profiler_unsupported",
      detail: detail ?? "Profiler provider is unsupported for the active JVM runtime.",
    };
  }
  if (result.status === "failed") {
    return {
      reasonCode: detail ?? "performance_profiler_start_failed",
      detail: detail ?? "Profiler provider failed to start.",
    };
  }
  if (result.sessionId !== sessionId || !["running", "started"].includes(result.status)) {
    return { reasonCode: "performance_profiler_start_failed", detail: "The requested profiler session is not owned." };
  }
  return null;
}

function resolveSelectedProfilerProvider(result, fallback) {
  return result && typeof result.provider === "string" && result.provider.trim().length > 0
    ? result.provider.trim()
    : fallback;
}

function percentile(values, ratio) {
  if (values.length === 0) return 0;
  const sorted = [...values].sort((a, b) => a - b);
  const index = Math.min(sorted.length - 1, Math.max(0, Math.ceil(sorted.length * ratio) - 1));
  return sorted[index] ?? 0;
}

function resolveEntrypointRequest(spec, context) {
  const baseUrl = substitutePlaceholders(spec.transport.baseUrl, context);
  const requestPath = substitutePlaceholders(spec.request.path, context);
  const url = new URL(
    `${String(baseUrl).replace(/\/$/, "")}${String(requestPath).startsWith("/") ? "" : "/"}${String(requestPath)}`
  );
  if (spec.request.queryTemplate && typeof spec.request.queryTemplate === "object") {
    const queryTemplate = substitutePlaceholders(spec.request.queryTemplate, context);
    for (const [key, raw] of Object.entries(queryTemplate)) {
      if (typeof raw === "undefined" || raw === null) continue;
      url.searchParams.set(key, String(raw));
    }
  }
  const headers = {
    ...(spec.transport.defaultHeaders ?? {}),
    ...(spec.request.headers ? substitutePlaceholders(spec.request.headers, context) : {}),
  };
  const bodyResolved = typeof spec.request.body === "undefined" ? undefined : substitutePlaceholders(spec.request.body, context);
  return {
    method: String(spec.request.method).toUpperCase(),
    url: url.toString(),
    headers,
    ...(typeof bodyResolved === "undefined" ? {} : { body: bodyResolved }),
  };
}

async function executeRequest(spec, context, requestTimeoutMs) {
  const resolvedRequest = resolveEntrypointRequest(spec, context);
  const start = Date.now();
  try {
    const response = await fetchWithTimeout(resolvedRequest.url, {
      method: resolvedRequest.method,
      headers: resolvedRequest.headers,
      ...(!("body" in resolvedRequest)
        ? {}
        : {
            body:
              typeof resolvedRequest.body === "string" ? resolvedRequest.body : JSON.stringify(resolvedRequest.body),
          }),
    }, requestTimeoutMs);
    await response.text();
    return {
      status: response.status >= 200 && response.status < 400 ? "pass" : "fail_http",
      statusCode: response.status,
      durationMs: Math.max(1, Date.now() - start),
    };
  } catch (error) {
    let reasonCode = "transport_request_failed";
    if (cancellation.signal.aborted) reasonCode = "performance_workload_interrupted";
    else if (["TimeoutError", "AbortError"].includes(error?.name)) reasonCode = "transport_request_timeout";
    return {
      status: "blocked_runtime",
      reasonCode,
      durationMs: Math.max(1, Date.now() - start),
    };
  }
}

async function runJmeterGeneratedHttpWorkload(plan, spec, context, runDirAbs, requestTimeoutMs) {
  const resolvedRequest = resolveEntrypointRequest(spec, context);
  const childEnvironment = { ...process.env };
  const runtimeValue = (key, value) => {
    childEnvironment[key] = value;
    return "${__groovy(System.getenv('" + key + "') ?: '')}";
  };
  const jmeterRequest = { ...resolvedRequest, url: runtimeValue("MCPJVM_REPLAY_URL", resolvedRequest.url), headers: {} };
  Object.entries(resolvedRequest.headers).forEach(([key, value], index) => {
    jmeterRequest.headers[key] = runtimeValue("MCPJVM_REPLAY_HEADER_" + index, String(value));
  });
  if ("body" in resolvedRequest) {
    const body = typeof resolvedRequest.body === "string" ? resolvedRequest.body : JSON.stringify(resolvedRequest.body);
    jmeterRequest.body = runtimeValue("MCPJVM_REPLAY_BODY", body);
  }
  const installationPath = plan.contract.workloadProvider.options?.installationPath;
  const executable = resolveJmeterExecutable(installationPath);
  const jmxPathAbs = path.join(runDirAbs, `${plan.planName}.workload.jmeter.jmx`);
  const jtlPathAbs = path.join(runDirAbs, `${plan.planName}.workload.jmeter.jtl`);
  const logPathAbs = path.join(runDirAbs, `${plan.planName}.workload.jmeter.log`);

  fs.writeFileSync(
    jmxPathAbs,
    renderGeneratedHttpJmx({
      request: {
        ...jmeterRequest,
        timeoutMs: requestTimeoutMs,
      },
      loadModel: plan.contract.loadModel,
      planName: plan.planName,
    }),
    "utf8"
  );

  if (!executable) {
    return {
      status: "blocked",
      reasonCode: "performance_jmeter_missing",
      requiredUserAction: [
        "Install Apache JMeter locally or set workloadProvider.options.installationPath / MCP_JAVA_DEV_TOOLS_JMETER_HOME.",
      ],
      artifacts: {
        jmxPathAbs,
        jtlPathAbs,
        logPathAbs,
      },
    };
  }

  let argsList = [
    "-n",
    "-t",
    jmxPathAbs,
    "-l",
    jtlPathAbs,
    "-j",
    logPathAbs,
    "-Jjmeter.save.saveservice.output_format=csv",
    "-Jjmeter.save.saveservice.print_field_names=true",
    "-Jjmeter.save.saveservice.timestamp_format=ms",
    "-Jjmeter.save.saveservice.time=true",
    "-Jjmeter.save.saveservice.timestamp=true",
    "-Jjmeter.save.saveservice.success=true",
    "-Jjmeter.save.saveservice.label=true",
    "-Jjmeter.save.saveservice.response_code=true",
    "-Jjmeter.save.saveservice.response_message=true",
    "-Jjmeter.save.saveservice.thread_name=true",
    "-Jjmeter.save.saveservice.url=false",
  ];

  let command = executable;
  if (process.platform === "win32") {
    const jar = path.join(path.dirname(executable), "ApacheJMeter.jar");
    if (!fileExists(jar)) return blockedPlanResult("performance_jmeter_missing", "ApacheJMeter.jar is required");
    command = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, "bin", "java.exe") : "java";
    argsList = ["-jar", jar, ...argsList];
  }

  const exitCode = await new Promise((resolve) => {
    const child = require("node:child_process").spawn(command, argsList, {
      cwd: runDirAbs,
      env: childEnvironment,
      windowsHide: true,
      shell: false,
      detached: process.platform !== "win32",
    });
    child.stdin.end();
    child.stdout.resume();
    const cancel = () => {
      if (child.exitCode !== null || child.signalCode !== null) return;
      if (process.platform === "win32") child.kill();
      else {
        try { process.kill(-child.pid, "SIGTERM"); } catch { }
      }
    };
    const timer = setTimeout(cancel, (plan.contract.loadModel.durationSeconds + 30) * 1000);
    process.on("SIGINT", cancel);
    process.on("SIGTERM", cancel);
    let stderr = "";
    child.stderr.on("data", (chunk) => {
      stderr = (stderr + chunk.toString()).slice(-65536);
    });
    child.on("error", () => resolve(-1));
    child.on("close", (code) => {
      clearTimeout(timer);
      process.removeListener("SIGINT", cancel);
      process.removeListener("SIGTERM", cancel);
      if (stderr.trim().length > 0 && !fileExists(logPathAbs)) {
        fs.writeFileSync(logPathAbs, stderr, "utf8");
      }
      resolve(typeof code === "number" ? code : -1);
    });
  });

  if (exitCode !== 0) {
    return {
      status: "blocked",
      reasonCode: "performance_jmeter_execution_failed",
      requiredUserAction: ["Inspect workload.jmeter.log and workload.jmeter.jmx, then rerun the replay package."],
      artifacts: {
        jmxPathAbs,
        jtlPathAbs,
        logPathAbs,
      },
    };
  }

  const metrics = fileExists(jtlPathAbs) ? await collectJmeterJtlMetrics(jtlPathAbs) : null;
  if (!metrics) {
    return {
      status: "blocked",
      reasonCode: "performance_jmeter_results_missing",
      requiredUserAction: ["Ensure the JMeter CLI writes a readable CSV JTL result file and rerun."],
      artifacts: {
        jmxPathAbs,
        jtlPathAbs,
        logPathAbs,
      },
    };
  }

  return {
    status: "completed",
    metrics,
    artifacts: {
      jmxPathAbs,
      jtlPathAbs,
      logPathAbs,
    },
  };
}

async function runPlan(plan, envContext, runtimeConfig, runDirAbs) {
  try {
  const entrypoint = plan.contract.entrypoints[0];
  const requestTimeoutMs = entrypoint.request.timeoutMs ?? runtimeConfig?.requestTimeoutMs ?? 20000;
  if (!(requestTimeoutMs > 0 && requestTimeoutMs <= 300000)) return blockedPlanResult("http_timeout_invalid", "Invalid request timeout");
  const context = { ...envContext };
  for (const [key, value] of Object.entries(plan.providedContext ?? {})) {
    if (value != null && !String(value).includes("REDACTED")) bindContext(context, key, String(value));
  }
  const probeBaseUrl = plan.probeBaseUrl;
  if (!probeBaseUrl) {
    return {
      status: "blocked",
      runStatus: "blocked",
      reasonCode: "performance_probe_selection_missing",
      failedStep: "probe_selection",
      reasonMeta: {
        planName: plan.planName,
      },
      requiredUserAction: ["Set observationTargets.probeId and ensure probe-config.json resolves a concrete Probe base URL at export time."],
    };
  }

  if (entrypoint.transport.healthCheckPath) {
    try {
      const baseUrl = substitutePlaceholders(entrypoint.transport.baseUrl, context);
      const healthcheckUrl = `${String(baseUrl).replace(/\/$/, "")}${String(entrypoint.transport.healthCheckPath).startsWith("/") ? "" : "/"}${String(entrypoint.transport.healthCheckPath)}`;
      const healthcheckResponse = await fetchWithTimeout(healthcheckUrl, { method: "GET" }, requestTimeoutMs);
      if (!(healthcheckResponse.status >= 200 && healthcheckResponse.status < 400)) {
        return {
          status: "blocked",
          runStatus: "blocked",
          reasonCode: "performance_healthcheck_failed",
          failedStep: "healthcheck",
          reasonMeta: {
            healthcheckUrl,
            httpStatus: healthcheckResponse.status,
            planName: plan.planName,
          },
          requiredUserAction: [`Target healthcheck failed: ${healthcheckUrl}`],
        };
      }
    } catch (error) {
      return blockedPlanResult("performance_healthcheck_transport_failed", errorDetail(error), {
        failedStep: "healthcheck",
        reasonMeta: {
          healthCheckPath: entrypoint.transport.healthCheckPath,
          baseUrl: entrypoint.transport.baseUrl,
          planName: plan.planName,
        },
      });
    }
  }

  for (const key of plan.contract.observationTargets.requiredLineHits) {
    try {
      await probeReset(probeBaseUrl, key);
    } catch (error) {
      return blockedPlanResult("performance_probe_reset_failed", errorDetail(error), {
        failedStep: "probe_reset",
        failedStrictLineKey: key,
        reasonMeta: {
          probeBaseUrl,
          strictLineKey: key,
          planName: plan.planName,
        },
      });
    }
  }

  let profilerStopResult;
  let profilerStartResult;
  let selectedProfilerProvider;
  if (plan.contract.analysis?.executionTiming?.enabled === true) {
    const sessionId = `${plan.planName}-${Date.now()}`;
    plan._profilerCandidateSessionId = sessionId;
    try {
      const startResult = await profiler(probeBaseUrl, {
        action: "start",
        provider: plan.contract.analysis.executionTiming.provider ?? "jfr",
        sessionId,
        event: plan.contract.analysis.executionTiming.event ?? "cpu",
        ...(typeof plan.contract.analysis.executionTiming.intervalNanos === "number"
          ? { intervalNanos: plan.contract.analysis.executionTiming.intervalNanos }
          : {}),
        outputFormat: plan.contract.analysis.executionTiming.outputFormat ?? "jfr",
      });
      const activeProfilerSessionId =
        typeof startResult.sessionId === "string" && startResult.sessionId.trim().length > 0
          ? startResult.sessionId
          : sessionId;
      plan._profilerSessionId = activeProfilerSessionId;
      plan._profilerStartResult = startResult;
      profilerStartResult = startResult;
      selectedProfilerProvider = resolveSelectedProfilerProvider(
        startResult,
        plan.contract.analysis.executionTiming.provider,
      );
      const profilerStartFailure = resolveProfilerStartFailure(startResult, sessionId);
      if (profilerStartFailure) {
        return blockedPlanResult(profilerStartFailure.reasonCode, profilerStartFailure.detail, {
          failedStep: "profiler_start",
          reasonMeta: {
            probeBaseUrl,
            sessionId,
            provider: selectedProfilerProvider,
            event: plan.contract.analysis.executionTiming.event ?? "cpu",
            planName: plan.planName,
          },
          executionTiming: {
            start: profilerStartResult,
            status: "profiler_start_failed",
            detail: profilerStartFailure.detail,
          },
        });
      }
    } catch (error) {
      return blockedPlanResult("performance_profiler_start_failed", errorDetail(error), {
        failedStep: "profiler_start",
        reasonMeta: {
          probeBaseUrl,
          sessionId,
          provider: selectedProfilerProvider ?? plan.contract.analysis.executionTiming.provider,
          event: plan.contract.analysis.executionTiming.event ?? "cpu",
          planName: plan.planName,
        },
        executionTiming: {
          status: "profiler_start_failed",
          detail: errorDetail(error),
        },
      });
    }
  }

  const latencies = [];
  let totalRequests = 0;
  let failedRequests = 0;
  let blockedReasonCode;
  const deadlineEpochMs = Date.now() + plan.contract.loadModel.durationSeconds * 1000;
  const perWorkerDelayMs =
    plan.contract.loadModel.concurrency > 1
      ? Math.floor((plan.contract.loadModel.rampUpSeconds * 1000) / plan.contract.loadModel.concurrency)
      : 0;

  let workloadProviderArtifacts;
  if (plan.contract.workloadProvider && plan.contract.workloadProvider.type === "jmeter") {
    const jmeterResult = await runJmeterGeneratedHttpWorkload(plan, entrypoint, context, runDirAbs, requestTimeoutMs);
    workloadProviderArtifacts = jmeterResult.artifacts;
    if (jmeterResult.status === "blocked") {
      blockedReasonCode = jmeterResult.reasonCode;
    } else {
      totalRequests = jmeterResult.metrics.totalRequests;
      failedRequests = jmeterResult.metrics.failedRequests;
      latencies.push(...jmeterResult.metrics.latenciesMs);
    }
  } else {
    const worker = async () => {
      while (Date.now() < deadlineEpochMs && !blockedReasonCode) {
        const result = await executeRequest(entrypoint, context, requestTimeoutMs);
        if (result.status === "pass" || result.status === "fail_http") {
          totalRequests += 1;
          if (totalRequests > MAX_SAMPLES) {
            blockedReasonCode = "performance_workload_sample_limit_exceeded";
            return;
          }
          latencies.push(result.durationMs);
          if (result.status !== "pass") failedRequests += 1;
          continue;
        }
        blockedReasonCode = result.reasonCode ?? "transport_request_failed";
        return;
      }
    };

    await Promise.all(
      Array.from({ length: plan.contract.loadModel.concurrency }, async (_unused, index) => {
        if (perWorkerDelayMs > 0 && index > 0) {
          await new Promise((resolve) => setTimeout(resolve, Math.min(perWorkerDelayMs * index, plan.contract.loadModel.durationSeconds * 1000)));
        }
        await worker();
      }),
    );
  }

  if (cancellation.signal.aborted) blockedReasonCode = "performance_workload_interrupted";

  if (plan.contract.analysis?.executionTiming?.enabled === true && plan._profilerSessionId) {
    try {
      profilerStopResult = await profiler(probeBaseUrl, {
        action: "stop",
        sessionId: plan._profilerSessionId,
        outputFormat: plan.contract.analysis.executionTiming.outputFormat ?? "jfr",
      });
      if (profilerStopResult.sessionId !== plan._profilerSessionId || !["completed", "stopped"].includes(profilerStopResult.status)) {
        throw new Error("profiler_stop_unverified");
      }
      plan._profilerCandidateSessionId = null;
      selectedProfilerProvider = resolveSelectedProfilerProvider(
        profilerStopResult,
        selectedProfilerProvider ?? plan.contract.analysis.executionTiming.provider,
      );
    } catch (error) {
      return blockedPlanResult("performance_profiler_stop_failed", errorDetail(error), {
        failedStep: "profiler_stop",
        reasonMeta: {
          probeBaseUrl,
          sessionId: plan._profilerSessionId,
          provider: selectedProfilerProvider ?? plan.contract.analysis.executionTiming.provider,
          planName: plan.planName,
        },
        executionTiming: {
          ...(profilerStartResult ? { start: profilerStartResult } : {}),
          status: "profiler_stop_failed",
          detail: errorDetail(error),
        },
      });
    }
    const profilerOutputPath = path.join(runDirAbs, `${plan.planName}.execution-timing.${plan.contract.analysis.executionTiming.outputFormat ?? "jfr"}`);
    try {
      profilerStopResult.download = await profiler(probeBaseUrl, {
        action: "download",
        sessionId: plan._profilerSessionId,
        outputPath: profilerOutputPath,
      });
      const download = profilerStopResult.download;
      if (download?.status !== "downloaded" || !(download.bytesWritten > 0) ||
          !fileExists(profilerOutputPath) || fs.statSync(profilerOutputPath).size !== download.bytesWritten) {
        throw new Error("profiler_download_unverified");
      }
    } catch (error) {
      return blockedPlanResult("performance_profiler_download_failed", errorDetail(error), {
        failedStep: "profiler_download",
        reasonMeta: {
          probeBaseUrl,
          sessionId: plan._profilerSessionId,
          outputPath: profilerOutputPath,
          provider: selectedProfilerProvider ?? plan.contract.analysis.executionTiming.provider,
          planName: plan.planName,
        },
        executionTiming: {
          ...(profilerStartResult ? { start: profilerStartResult } : {}),
          ...(profilerStopResult ? { stop: profilerStopResult } : {}),
          status: "profiler_download_failed",
          detail: errorDetail(error),
        },
      });
    }
  }

  const requiredLineHits = [];
  let strictLineBlockedReasonCode;
  for (const key of plan.contract.observationTargets.requiredLineHits) {
    let waitResult;
    try {
      waitResult = await probeWaitForHit(probeBaseUrl, key, Math.max(1000, plan.contract.loadModel.durationSeconds * 1000));
    } catch (error) {
      waitResult = {
        hit: false,
        reasonCode: "performance_probe_wait_failed",
        detail: errorDetail(error),
        failedStep: "probe_wait",
        reasonMeta: {
          probeBaseUrl,
          strictLineKey: key,
          planName: plan.planName,
        },
      };
    }
    requiredLineHits.push({
      key,
      hit: waitResult.hit === true,
      ...(waitResult.reasonCode ? { reasonCode: waitResult.reasonCode } : {}),
      ...(waitResult.failedStep ? { failedStep: waitResult.failedStep } : {}),
      ...(waitResult.reasonMeta ? { reasonMeta: waitResult.reasonMeta } : {}),
    });
    if (waitResult.hit !== true && waitResult.reasonCode && waitResult.reasonCode !== "timeout_no_inline_hit" && !strictLineBlockedReasonCode) {
      strictLineBlockedReasonCode = waitResult.reasonCode;
    }
  }

  const totalDurationMs = Math.max(1, plan.contract.loadModel.durationSeconds * 1000);
  const throughputPerSec = totalRequests / (totalDurationMs / 1000);
  const errorRatePct = totalRequests > 0 ? (failedRequests / totalRequests) * 100 : 100;
  const p95LatencyMs = percentile(latencies, 0.95);
  const thresholdResults = {
    maxErrorRatePct: {
      actual: Number(errorRatePct.toFixed(3)),
      expectedMax: plan.contract.successCriteria.maxErrorRatePct,
      pass: errorRatePct <= plan.contract.successCriteria.maxErrorRatePct,
    },
    minThroughputPerSec: {
      actual: Number(throughputPerSec.toFixed(3)),
      expectedMin: plan.contract.successCriteria.minThroughputPerSec,
      pass: throughputPerSec >= plan.contract.successCriteria.minThroughputPerSec,
    },
    p95LatencyMs: {
      actual: p95LatencyMs,
      expectedMax: plan.contract.successCriteria.p95LatencyMs,
      pass: p95LatencyMs <= plan.contract.successCriteria.p95LatencyMs,
    },
  };
  const requiredLineHitsPass = !strictLineBlockedReasonCode && requiredLineHits.every((entry) => entry.hit === true);
  const runStatus =
    blockedReasonCode || strictLineBlockedReasonCode
      ? "blocked"
      : thresholdResults.maxErrorRatePct.pass &&
          thresholdResults.minThroughputPerSec.pass &&
          thresholdResults.p95LatencyMs.pass &&
          requiredLineHitsPass
        ? "pass"
        : "fail";
  return {
    status: "executed",
    runStatus,
    metrics: {
      totalRequests,
      failedRequests,
      errorRatePct: Number(errorRatePct.toFixed(3)),
      throughputPerSec: Number(throughputPerSec.toFixed(3)),
      p95LatencyMs,
      durationMs: totalDurationMs,
    },
    thresholdResults,
    requiredLineHits,
    ...(blockedReasonCode ? { reasonCode: blockedReasonCode } : {}),
    ...(strictLineBlockedReasonCode ? { strictLineBlockedReasonCode } : {}),
    ...(blockedReasonCode
      ? {
          failedStep: "workload_execution",
          reasonMeta: {
            entrypointBaseUrl: entrypoint.transport.baseUrl,
            requestPath: entrypoint.request.path,
            planName: plan.planName,
          },
        }
      : {}),
    ...(strictLineBlockedReasonCode
      ? {
          failedStep: "strict_line_verification",
          reasonMeta: {
            probeBaseUrl,
            strictLineKeys: plan.contract.observationTargets.requiredLineHits,
            planName: plan.planName,
          },
        }
      : {}),
    ...(profilerStartResult || profilerStopResult
      ? {
          executionTiming: {
            ...(profilerStartResult ? { start: profilerStartResult } : {}),
            ...(profilerStopResult ? { stop: profilerStopResult } : {}),
            ...(profilerStopResult?.download ? { download: profilerStopResult.download } : {}),
          },
        }
      : {}),
    ...(plan.contract.workloadProvider ? { workloadProvider: plan.contract.workloadProvider } : {}),
    ...(workloadProviderArtifacts ? { workloadProviderArtifacts } : {}),
  };
  } finally {
    const sessionId = plan._profilerCandidateSessionId;
    if (sessionId) {
      const status = await profiler(plan.probeBaseUrl, { action: "status" });
      if (status.sessionId === sessionId && status.status === "running") {
        const stopped = await profiler(plan.probeBaseUrl, { action: "stop", sessionId, outputFormat: "jfr" });
        if (stopped.sessionId !== sessionId || !["completed", "stopped"].includes(stopped.status)) {
          throw new Error("performance_profiler_cleanup_failed");
        }
      }
    }
  }
}

function prePlanScripts(scripts, exportDir) {
  const root = path.resolve(exportDir);
  for (const script of scripts ?? []) {
    const args = script.args.map(value => {
      if (value === "__MCPJVM_PROJECT_ENV__") return path.join(root, "project.env");
      if (value.startsWith("__MCPJVM_SCRIPT__")) return path.join(root, value.slice("__MCPJVM_SCRIPT__".length));
      return value;
    });
    const cwd = path.resolve(root, script.appdir || ".");
    const relative = path.relative(root, cwd);
    if (relative.startsWith("..") || path.isAbsolute(relative)) throw new Error("script_directory_invalid");
    fs.mkdirSync(cwd, { recursive: true });
    const result = require("node:child_process").spawnSync(script.command, args, {
      cwd, env: { ...process.env, ...script.env }, timeout: 30000, stdio: "inherit", shell: false, windowsHide: true,
    });
    if (result.error || result.status !== 0) throw new Error("export_script_failed");
  }
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (!args.bundlePath || !args.envFilePath || !args.exportDir) {
    fail("performance_export_args_invalid", "bundlePath, envFilePath, and exportDir are required");
  }
  const bundle = JSON.parse(fs.readFileSync(args.bundlePath, "utf8"));
  const originalInputs = parseEnvFile(args.envFilePath);
  const runId = new Date().toISOString().replace(/[:.]/g, "-");
  const runDirAbs = path.join(args.exportDir, "runs", runId);
  fs.mkdirSync(runDirAbs, { recursive: true });

  const planRuns = [];
  let hasFail = false;
  let hasBlocked = false;
  let stopped = false;
  for (const plan of [...bundle.plans].sort((a, b) => a.order - b.order)) {
    let result;
    try {
      prePlanScripts(bundle.runtimeConfig?.prePlanScripts, args.exportDir);
      const env = { ...originalInputs, ...process.env };
      const currentInputs = parseEnvFile(args.envFilePath);
      for (const key of new Set([...Object.keys(originalInputs), ...Object.keys(currentInputs)])) {
        if (currentInputs[key] !== originalInputs[key]) {
          if (key in currentInputs) env[key] = currentInputs[key];
          else delete env[key];
        }
      }
      for (const [key, binding] of Object.entries(bundle.runtimeConfig?.contextBindings ?? {})) {
        if (typeof env[binding] === "string") bindContext(env, key, env[binding]);
      }
      result = await runPlan(plan, env, bundle.runtimeConfig, runDirAbs);
    } catch (error) {
      result = blockedPlanResult("performance_plan_execution_failed", errorDetail(error));
    }
    planRuns.push({
      order: plan.order,
      planName: plan.planName,
      status: result.status === "executed" ? "executed" : "blocked",
      ...(result.runStatus ? { runStatus: result.runStatus } : {}),
      ...(result.reasonCode ? { blockedReasonCode: result.reasonCode } : {}),
      ...(result.failedStep ? { failedStep: result.failedStep } : {}),
      ...(result.reasonMeta ? { reasonMeta: result.reasonMeta } : {}),
    });
    fs.writeFileSync(path.join(runDirAbs, `${plan.planName}.execution.result.json`), `${JSON.stringify(result, null, 2)}\n`, "utf8");
    if (result.runStatus === "fail") hasFail = true;
    if (result.runStatus === "blocked" || result.status !== "executed") hasBlocked = true;
    if ((result.runStatus !== "pass" || result.status !== "executed") &&
        (plan.onFail === "stop" || ((plan.onFail ?? "inherit") === "inherit" && bundle.executionPolicy === "stop_on_fail"))) {
      stopped = true;
      break;
    }
  }

  const suiteStatus =
    !stopped
      ? hasFail || hasBlocked
        ? "partial_fail"
        : "pass"
      : hasBlocked
        ? "blocked"
        : hasFail
          ? "fail"
          : "pass";
  const summary = {
    resultType: "performance_execution_profile_export_run",
    exportId: bundle.exportId,
    suiteType: "performance",
    executionProfile: bundle.executionProfile,
    executionPolicy: bundle.executionPolicy,
    status: suiteStatus,
    planRuns,
  };
  fs.writeFileSync(path.join(runDirAbs, "execution_orchestration.result.json"), `${JSON.stringify(summary, null, 2)}\n`, "utf8");
  process.stdout.write(`${JSON.stringify(summary, null, 2)}\n`);
  if (suiteStatus !== "pass") {
    process.exitCode = 1;
  }
}

main().catch((error) => {
  fail("performance_export_runtime_failed", error instanceof Error ? error.message : String(error));
});
