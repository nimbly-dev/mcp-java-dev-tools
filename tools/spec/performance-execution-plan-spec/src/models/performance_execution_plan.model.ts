export type BuiltinWorkloadProvider = {
  type: "builtin";
};

export type JmeterWorkloadProvider = {
  type: "jmeter";
  mode: "generated_http";
  options?: {
    installationPath?: string;
    emitJmx?: boolean;
    emitJtl?: boolean;
    emitLog?: boolean;
  };
};

export type PerformanceWorkloadProvider = BuiltinWorkloadProvider | JmeterWorkloadProvider;

export type PerformancePlanMetadata = {
  specVersion?: string;
  suiteType: "performance";
  execution: { intent: "performance" };
};

export type PerformanceEntrypoint = {
  transport: {
    protocol: "http";
    baseUrl: string;
    healthCheckPath?: string;
    wrappedOnly?: boolean;
    defaultHeaders?: Record<string, string>;
  };
  request: {
    method: string;
    path: string;
    queryTemplate?: Record<string, unknown>;
    headers?: Record<string, string>;
    body?: unknown;
  };
};

export type PerformancePlanContract = {
  entrypoints: PerformanceEntrypoint[];
  workloadProvider: PerformanceWorkloadProvider;
  observationTargets: {
    requiredLineHits: string[];
    optionalLineHits?: string[];
    probeId?: string;
  };
  loadModel: {
    mode: "concurrency";
    concurrency: number;
    rampUpSeconds: number;
    durationSeconds: number;
  };
  successCriteria: {
    maxErrorRatePct: number;
    minThroughputPerSec: number;
    p95LatencyMs: number;
  };
  analysis?: {
    executionTiming?: {
      enabled: true;
      provider: "async-profiler";
      event?: string;
      intervalNanos?: number;
      outputFormat?: "jfr";
    };
    msta?: {
      enabled: true;
      mode?: "method_targets" | "target_plus_path";
      methodTargets?: Array<{ methodRef: string }>;
      anchorSelection?: {
        source: "verified_required_line_hits";
        strategy: "all";
      };
      includePackages?: string[];
      allowThirdPartyFrames?: boolean;
    };
    correlation?: {
      enabled: true;
      kind: "sampled_attribution";
      anchorSource: "msta_resolved_anchors";
      requireLineHit: boolean;
      requireMsta: boolean;
    };
  };
};

export type PerformanceMstaMethodStep = {
  stepOrder: number;
  methodRef: string;
  target: boolean;
  samples: number;
  estimatedTimePct: number;
  estimatedTimeMs: number;
};

export type PerformanceMstaTargetSummary = {
  strictLineKey: string;
  anchorMethod: string;
  anchoredSampleCount: number;
  dominantPathSampleCount: number;
  dominantPathSamplePct: number;
  dominantPathApproxTimeMs: number;
  steps: PerformanceMstaMethodStep[];
};

export type PerformanceMstaMethodSummary = {
  methodRef: string;
  estimatedTimeMs: number;
  estimatedTimePct: number;
  samples: number;
  pathSteps: PerformanceMstaMethodStep[];
  strictLineKey?: string;
};

export type PerformanceMstaSummary =
  | {
      status: "available";
      unit: "ms";
      jfrPath: string;
      sourceEventTypes: string[];
      durationMs: number;
      provider?: { name: string; event?: string; outputFormat?: string };
      mode: "required_line_hits" | "method_targets" | "target_plus_path";
      methods: PerformanceMstaMethodSummary[];
      targets: PerformanceMstaTargetSummary[];
    }
  | {
      status: "jfr_missing" | "jfr_parse_failed" | "no_anchor_samples";
      jfrPath?: string;
      detail: string;
      unit: "ms";
    };

export type PersistedPerformanceMstaSummary =
  | PerformanceMstaSummary
  | { status: "not_configured" | "disabled" };

export type PerformanceMstaConfigState = PersistedPerformanceMstaSummary["status"];
