import { promises as fs } from "node:fs";

type ParsedJtlRow = Record<string, string>;

function parseCsvLine(line: string): string[] {
  const values: string[] = [];
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

export async function collectJmeterJtlMetrics(input: {
  jtlPathAbs: string;
}): Promise<{ totalRequests: number; failedRequests: number; latenciesMs: number[] } | null> {
  const text = await fs.readFile(input.jtlPathAbs, "utf8");
  const lines = text
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line.length > 0);
  if (lines.length < 2) return null;
  const firstLine = lines[0];
  if (typeof firstLine !== "string") return null;
  const headers = parseCsvLine(firstLine);
  const rows: ParsedJtlRow[] = lines.slice(1).map((line) => {
    const values = parseCsvLine(line);
    const row: ParsedJtlRow = {};
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
  if (latenciesMs.length === 0) return null;
  const failedRequests = rows.reduce((count, row) => count + (row.success === "true" ? 0 : 1), 0);
  return {
    totalRequests: rows.length,
    failedRequests,
    latenciesMs,
  };
}
