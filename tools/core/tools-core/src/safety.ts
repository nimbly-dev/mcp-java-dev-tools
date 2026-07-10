export const DEFAULT_PROBE_TIMEOUT_MS = 15_000;
export const HARD_MAX_PROBE_TIMEOUT_MS = 60_000;
export const DEFAULT_PROBE_POLL_INTERVAL_MS = 500;
export const HARD_MAX_PROBE_POLL_INTERVAL_MS = 5_000;
export const DEFAULT_PROBE_WAIT_MAX_RETRIES = 1;
export const HARD_MAX_PROBE_WAIT_MAX_RETRIES = 10;

export function clampInt(n: number, min: number, max: number): number {
  if (!Number.isFinite(n)) return min;
  n = Math.trunc(n);
  if (n < min) return min;
  if (n > max) return max;
  return n;
}
