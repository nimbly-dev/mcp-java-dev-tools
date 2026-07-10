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

export type BuiltinWorkloadProvider = {
  type: "builtin";
};

export type PerformanceWorkloadProvider = BuiltinWorkloadProvider | JmeterWorkloadProvider;

export type JmeterGeneratedHttpRequest = {
  method: string;
  url: string;
  headers?: Record<string, string>;
  body?: unknown;
  timeoutMs?: number;
};

export type JmeterGeneratedHttpLoadModel = {
  concurrency: number;
  rampUpSeconds: number;
  durationSeconds: number;
};

export type JmeterWorkloadRunResult =
  | {
      status: "completed";
      metrics: {
        totalRequests: number;
        failedRequests: number;
        latenciesMs: number[];
      };
    artifacts: {
        jmxPathAbs?: string;
        jtlPathAbs?: string;
        logPathAbs?: string;
      };
    }
  | {
      status: "blocked";
      reasonCode: string;
      requiredUserAction: string[];
      artifacts?: {
        jmxPathAbs?: string;
        jtlPathAbs?: string;
        logPathAbs?: string;
    };
  };

export type RunJmeterGeneratedHttpWorkloadArgs = {
  provider: JmeterWorkloadProvider;
  request: JmeterGeneratedHttpRequest;
  loadModel: JmeterGeneratedHttpLoadModel;
  runDirAbs: string;
  planName: string;
};

