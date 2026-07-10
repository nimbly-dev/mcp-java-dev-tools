export function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

export function readProbeUnreachableErrorMessage(err: unknown): string | null {
  const raw = err instanceof Error ? err.message : String(err);
  return raw.includes("Probe endpoint unreachable:") ? raw : null;
}
