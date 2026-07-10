export type InferredTarget = {
  file: string;
  className?: string;
  methodName?: string;
  line?: number | null;
  declarationLine?: number;
  endLine?: number;
  firstExecutableLine?: number | null;
  lineSelectionStatus?: "validated" | "unresolved";
  lineSelectionSource?: "runtime_probe_validation";
  signature?: string;
  returnsBoolean?: boolean;
  fqcn?: string;
  key?: string;
  reasons: string[];
};

export type ClassMethodSpan = {
  methodName: string;
  signature: string;
  startLine: number;
  endLine: number;
  firstExecutableLine?: number | null;
  lineSelectionStatus?: "validated" | "unresolved";
  lineSelectionSource?: "runtime_probe_validation";
  probeKey?: string;
};

export type ClassDiscoveryCandidate = {
  file: string;
  className: string;
  fqcn?: string;
  methods: ClassMethodSpan[];
};
