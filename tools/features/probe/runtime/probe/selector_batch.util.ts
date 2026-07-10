export function normalizeOptionalString(value: string | undefined): string | undefined {
  const trimmed = value?.trim();
  return trimmed && trimmed.length > 0 ? trimmed : undefined;
}

export function normalizeOptionalStringArray(values: string[] | undefined): string[] | undefined {
  if (!Array.isArray(values)) return undefined;
  const out: string[] = [];
  const seen = new Set<string>();
  for (const raw of values) {
    if (typeof raw !== "string") continue;
    const trimmed = raw.trim();
    if (!trimmed) continue;
    if (seen.has(trimmed)) continue;
    seen.add(trimmed);
    out.push(trimmed);
  }
  return out.length > 0 ? out : undefined;
}

export function validateSelectorCount(
  selectorName: "probe_get_status" | "probe_reset",
  selectors: Array<{ enabled: boolean; name: string }>,
): void {
  const active = selectors.filter((s) => s.enabled).map((s) => s.name);
  if (active.length === 1) return;
  if (active.length === 0) {
    throw new Error(
      `${selectorName} requires exactly one selector: ` +
        (selectorName === "probe_get_status" ? "`key` or `keys`." : "`key`, `keys`, or `className`."),
    );
  }
  throw new Error(
    `${selectorName} received conflicting selectors (${active.join(", ")}). ` +
      "Provide exactly one selector.",
  );
}
