import { spawn } from "node:child_process";
import { promises as fs } from "node:fs";
import path from "node:path";
import type { PerformanceMstaSummary } from "./models/performance_suite.model";
export type { PerformanceMstaSummary } from "./models/performance_suite.model";

type MstaStatus = "available" | "jfr_missing" | "jfr_parse_failed" | "no_anchor_samples";
const MAX_REDUCED_EVENT_LINE_LENGTH = 128 * 1024;
const MAX_PATH_VARIANTS_PER_ANCHOR = 20_000;

type MstaMethodStep = {
  stepOrder: number;
  methodRef: string;
  target: boolean;
  samples: number;
  estimatedTimePct: number;
  estimatedTimeMs: number;
};

type MstaTargetSummary = {
  strictLineKey: string;
  anchorMethod: string;
  anchoredSampleCount: number;
  dominantPathSampleCount: number;
  dominantPathSamplePct: number;
  dominantPathApproxTimeMs: number;
  steps: MstaMethodStep[];
};

type MstaMethodSummary = {
  methodRef: string;
  estimatedTimeMs: number;
  estimatedTimePct: number;
  samples: number;
  pathSteps: MstaMethodStep[];
  strictLineKey?: string;
};

type ReducedExecutionSampleEvent = {
  type: string;
  samples: number;
  frames: string[];
};

type ReducedJfrInvocation = {
  command: string;
  args: string[];
  shell: boolean;
};

type AnchorAggregation = {
  pathCounts: Map<string, number>;
  methodCounts: Map<string, number>;
  anchoredSampleCount: number;
};

function normalizeLineKeyToAnchorMethod(lineKey: string): string {
  const hashIndex = lineKey.indexOf("#");
  const colonIndex = lineKey.lastIndexOf(":");
  if (hashIndex <= 0 || colonIndex <= hashIndex) return lineKey.trim();
  return lineKey.slice(0, colonIndex).trim();
}

function toPercent(count: number, total: number): number {
  if (total <= 0) return 0;
  return Number(((count / total) * 100).toFixed(3));
}

function toApproxTimeMs(count: number, total: number, durationMs: number): number {
  if (total <= 0 || durationMs <= 0) return 0;
  return Number(((count / total) * durationMs).toFixed(3));
}

function compressAdjacent(values: string[]): string[] {
  const out: string[] = [];
  for (const value of values) {
    if (out[out.length - 1] === value) continue;
    out.push(value);
  }
  return out;
}

function extractAnchorPathSample(args: { methods: string[]; anchorMethod: string }): string[] | null {
  const anchorIndex = args.methods.findIndex((method) => method === args.anchorMethod);
  if (anchorIndex < 0) return null;
  const pathFromAnchorToLeaf = compressAdjacent(args.methods.slice(0, anchorIndex + 1).reverse());
  return pathFromAnchorToLeaf.length > 0 ? pathFromAnchorToLeaf : null;
}

function parseReducedExecutionSampleEvent(rawLine: string): ReducedExecutionSampleEvent {
  const parsed = JSON.parse(rawLine) as {
    type?: unknown;
    samples?: unknown;
    frames?: unknown;
  };
  if (typeof parsed.type !== "string" || parsed.type.trim().length === 0) {
    throw new Error("jfr_reduced_event_invalid:type");
  }
  const sampleWeight =
    typeof parsed.samples === "number" && Number.isFinite(parsed.samples) && parsed.samples > 0
      ? Math.max(1, Math.floor(parsed.samples))
      : 1;
  const frames = Array.isArray(parsed.frames)
    ? parsed.frames
        .filter((frame): frame is string => typeof frame === "string" && frame.trim().length > 0)
        .map((frame) => frame.trim())
    : [];
  if (frames.length === 0) {
    throw new Error("jfr_reduced_event_invalid:frames");
  }
  return {
    type: parsed.type.trim(),
    samples: sampleWeight,
    frames,
  };
}

function resolveRepoRootCandidates(): string[] {
  return [
    path.resolve(__dirname, "../../../"),
    path.resolve(process.cwd()),
    path.resolve(__dirname, "../../../../"),
    path.resolve(__dirname, "../../../../../"),
    path.resolve(__dirname, "../../../.."),
  ];
}

async function resolveReducedJfrInvocation(jfrPath: string): Promise<ReducedJfrInvocation | { detail: string }> {
  const override = process.env.MCP_JAVA_DEV_TOOLS_JFR_EXTRACTOR?.trim();
  if (override) {
    return {
      command: override,
      args: [jfrPath],
      shell: process.platform === "win32" && /\.cmd$/i.test(override),
    };
  }

  for (const repoRootAbs of resolveRepoRootCandidates()) {
    const sourceFileAbs = path.join(
      repoRootAbs,
      "java-agent",
      "core",
      "core-probe-profiler",
      "src",
      "main",
      "java",
      "com",
      "nimbly",
      "mcpjavadevtools",
      "agent",
      "profiler",
      "JfrSampleStreamCli.java",
    );
    try {
      const stat = await fs.stat(sourceFileAbs);
      if (!stat.isFile()) {
        continue;
      }
      return {
        command: "java",
        args: [sourceFileAbs, jfrPath],
        shell: false,
      };
    } catch {
      // Try next repo-root candidate.
    }
  }

  return {
    detail:
      "Reduced JFR extractor source is unavailable. Ensure repo sources are present or set MCP_JAVA_DEV_TOOLS_JFR_EXTRACTOR.",
  };
}

async function streamReducedJfrSamples(args: {
  jfrPath: string;
  onEvent: (event: ReducedExecutionSampleEvent) => void;
}): Promise<{ ok: true; sourceEventTypes: string[] } | { ok: false; detail: string }> {
  const invocation = await resolveReducedJfrInvocation(args.jfrPath);
  if ("detail" in invocation) {
    return { ok: false, detail: invocation.detail };
  }

  return await new Promise((resolve) => {
    const child = spawn(invocation.command, invocation.args, {
      windowsHide: true,
      shell: invocation.shell,
    });
    let stderr = "";
    let lineBuffer = "";
    let settled = false;
    let failureDetail: string | null = null;
    const sourceEventTypes = new Set<string>();

    const settle = (result: { ok: true; sourceEventTypes: string[] } | { ok: false; detail: string }) => {
      if (settled) return;
      settled = true;
      resolve(result);
    };

    const abortWithDetail = (detail: string) => {
      if (failureDetail) return;
      failureDetail = detail;
      child.kill();
    };

    const processLine = (line: string) => {
      const trimmed = line.trim();
      if (trimmed.length === 0) return;
      if (trimmed.length > MAX_REDUCED_EVENT_LINE_LENGTH) {
        abortWithDetail(`reduced_jfr_line_too_large:length=${trimmed.length}`);
        return;
      }
      try {
        const event = parseReducedExecutionSampleEvent(trimmed);
        sourceEventTypes.add(event.type);
        args.onEvent(event);
      } catch (error) {
        abortWithDetail(error instanceof Error ? error.message : String(error));
      }
    };

    child.stdout.setEncoding("utf8");
    child.stdout.on("data", (chunk) => {
      if (failureDetail) return;
      lineBuffer += String(chunk ?? "");
      let newlineIndex = lineBuffer.indexOf("\n");
      while (newlineIndex >= 0) {
        const line = lineBuffer.slice(0, newlineIndex);
        lineBuffer = lineBuffer.slice(newlineIndex + 1);
        processLine(line);
        if (failureDetail) return;
        newlineIndex = lineBuffer.indexOf("\n");
      }
      if (lineBuffer.length > MAX_REDUCED_EVENT_LINE_LENGTH) {
        abortWithDetail(`reduced_jfr_line_too_large:length>${MAX_REDUCED_EVENT_LINE_LENGTH}`);
      }
    });
    child.stderr.on("data", (chunk) => {
      stderr += String(chunk ?? "");
    });
    child.on("error", (error) => {
      settle({ ok: false, detail: error instanceof Error ? error.message : String(error) });
    });
    child.on("close", (code) => {
      if (failureDetail) {
        settle({ ok: false, detail: failureDetail });
        return;
      }
      if (lineBuffer.trim().length > 0) {
        processLine(lineBuffer);
        lineBuffer = "";
      }
      if (failureDetail) {
        settle({ ok: false, detail: failureDetail });
        return;
      }
      if (code === 0) {
        settle({ ok: true, sourceEventTypes: [...sourceEventTypes].sort() });
        return;
      }
      settle({ ok: false, detail: stderr.trim() || `reduced_jfr_exit_code=${String(code ?? 1)}` });
    });
  });
}

async function resolveReadableJfrPath(rawPath: string | undefined, runDirAbs: string): Promise<string | null> {
  const candidates: string[] = [];
  if (typeof rawPath === "string" && rawPath.trim().length > 0) {
    const trimmed = rawPath.trim();
    candidates.push(trimmed);
    if (trimmed.startsWith("/application/")) {
      candidates.push(trimmed.slice("/application/".length));
    }
    if (trimmed.startsWith("/workspace/")) {
      candidates.push(trimmed.slice("/workspace/".length));
    }
    const normalizedSlashes = trimmed.replaceAll("/", path.sep);
    if (normalizedSlashes !== trimmed) candidates.push(normalizedSlashes);
  }
  candidates.push(path.join(runDirAbs, "execution-timing.jfr"));

  for (const candidate of candidates) {
    if (!candidate) continue;
    const normalized = path.normalize(candidate);
    try {
      const stat = await fs.stat(normalized);
      if (stat.isFile()) return normalized;
    } catch {
      // Try next candidate.
    }
  }
  return null;
}

function extractProfilerOutputPath(profilerStopResult: Record<string, unknown> | undefined): string | undefined {
  if (!profilerStopResult) return undefined;
  const result =
    typeof profilerStopResult.result === "object" && profilerStopResult.result !== null
      ? (profilerStopResult.result as Record<string, unknown>)
      : undefined;
  return typeof result?.outputPath === "string" ? result.outputPath : undefined;
}

function createAggregationByAnchor(anchorMethods: string[]): Map<string, AnchorAggregation> {
  return new Map(
    anchorMethods.map((anchorMethod) => [
      anchorMethod,
      {
        pathCounts: new Map<string, number>(),
        methodCounts: new Map<string, number>(),
        anchoredSampleCount: 0,
      },
    ]),
  );
}

export async function buildPerformanceMstaSummary(args: {
  requiredLineHits: string[];
  methodTargets?: string[];
  mode?: "method_targets" | "target_plus_path";
  provider?: {
    name: string;
    event?: string;
    outputFormat?: string;
  };
  durationMs: number;
  profilerStopResult?: Record<string, unknown>;
  runDirAbs: string;
}): Promise<PerformanceMstaSummary> {
  const rawOutputPath = extractProfilerOutputPath(args.profilerStopResult);
  const resolvedJfrPath = await resolveReadableJfrPath(rawOutputPath, args.runDirAbs);
  if (!resolvedJfrPath) {
    return {
      status: "jfr_missing",
      ...(typeof rawOutputPath === "string" ? { jfrPath: rawOutputPath } : {}),
      detail: "Profiler capture file is not readable from the current workspace.",
      unit: "ms",
    };
  }

  const explicitMethodTargets = Array.isArray(args.methodTargets)
    ? args.methodTargets
        .filter((entry): entry is string => typeof entry === "string" && entry.trim().length > 0)
        .map((entry) => entry.trim())
    : [];
  const fallbackTargets = args.requiredLineHits.map((lineKey) => normalizeLineKeyToAnchorMethod(lineKey));
  const anchorMethods = Array.from(new Set(explicitMethodTargets.length > 0 ? explicitMethodTargets : fallbackTargets));
  const strictLineKeyByMethod = new Map<string, string>();
  for (const lineKey of args.requiredLineHits) {
    const methodRef = normalizeLineKeyToAnchorMethod(lineKey);
    if (!strictLineKeyByMethod.has(methodRef)) {
      strictLineKeyByMethod.set(methodRef, lineKey);
    }
  }

  const aggregationByAnchor = createAggregationByAnchor(anchorMethods);
  const streamed = await streamReducedJfrSamples({
    jfrPath: resolvedJfrPath,
    onEvent: (event) => {
      for (const anchorMethod of anchorMethods) {
        const aggregation = aggregationByAnchor.get(anchorMethod);
        if (!aggregation) continue;
        const pathSample = extractAnchorPathSample({ methods: event.frames, anchorMethod });
        if (!pathSample) continue;
        aggregation.anchoredSampleCount += event.samples;
        const pathKey = pathSample.join(" -> ");
        if (!aggregation.pathCounts.has(pathKey) && aggregation.pathCounts.size >= MAX_PATH_VARIANTS_PER_ANCHOR) {
          throw new Error(`msta_path_variant_limit_exceeded:anchor=${anchorMethod}`);
        }
        aggregation.pathCounts.set(pathKey, (aggregation.pathCounts.get(pathKey) ?? 0) + event.samples);
        for (const method of pathSample) {
          aggregation.methodCounts.set(method, (aggregation.methodCounts.get(method) ?? 0) + event.samples);
        }
      }
    },
  });
  if (!streamed.ok) {
    return {
      status: "jfr_parse_failed",
      jfrPath: resolvedJfrPath,
      detail: streamed.detail,
      unit: "ms",
    };
  }

  const targets: MstaTargetSummary[] = [];
  const methods: MstaMethodSummary[] = [];
  for (const anchorMethod of anchorMethods) {
    const aggregation = aggregationByAnchor.get(anchorMethod);
    if (!aggregation || aggregation.anchoredSampleCount === 0) {
      continue;
    }

    const dominantPathEntry = [...aggregation.pathCounts.entries()].sort((left, right) => right[1] - left[1])[0];
    if (!dominantPathEntry) continue;
    const [dominantPathKey, dominantPathCount] = dominantPathEntry;
    const dominantPath = dominantPathKey.split(" -> ");
    const steps: MstaMethodStep[] = dominantPath.map((method, index) => {
      const samples = aggregation.methodCounts.get(method) ?? 0;
      return {
        stepOrder: index + 1,
        methodRef: method,
        target: method === anchorMethod,
        samples,
        estimatedTimePct: toPercent(samples, aggregation.anchoredSampleCount),
        estimatedTimeMs: toApproxTimeMs(samples, aggregation.anchoredSampleCount, args.durationMs),
      };
    });

    const strictLineKey = strictLineKeyByMethod.get(anchorMethod);
    if (strictLineKey) {
      targets.push({
        strictLineKey,
        anchorMethod,
        anchoredSampleCount: aggregation.anchoredSampleCount,
        dominantPathSampleCount: dominantPathCount,
        dominantPathSamplePct: toPercent(dominantPathCount, aggregation.anchoredSampleCount),
        dominantPathApproxTimeMs: toApproxTimeMs(dominantPathCount, aggregation.anchoredSampleCount, args.durationMs),
        steps,
      });
    }

    methods.push({
      methodRef: anchorMethod,
      estimatedTimeMs: toApproxTimeMs(dominantPathCount, aggregation.anchoredSampleCount, args.durationMs),
      estimatedTimePct: toPercent(dominantPathCount, aggregation.anchoredSampleCount),
      samples: aggregation.anchoredSampleCount,
      pathSteps: steps,
      ...(strictLineKey ? { strictLineKey } : {}),
    });
  }

  if (methods.length === 0) {
    return {
      status: "no_anchor_samples",
      jfrPath: resolvedJfrPath,
      detail: "No sampled profiler events contained the requested anchor method.",
      unit: "ms",
    };
  }

  return {
    status: "available",
    unit: "ms",
    jfrPath: resolvedJfrPath,
    sourceEventTypes: streamed.sourceEventTypes,
    ...(args.provider ? { provider: args.provider } : {}),
    durationMs: args.durationMs,
    mode: explicitMethodTargets.length > 0 ? args.mode ?? "method_targets" : "required_line_hits",
    methods,
    targets,
  };
}
