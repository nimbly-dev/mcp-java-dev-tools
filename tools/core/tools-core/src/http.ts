export async function fetchJson(
  url: string,
  init: RequestInit & { timeoutMs?: number } = {},
): Promise<{ status: number; json: any; text: string }> {
  const { timeoutMs, ...rest } = init;
  const controller = new AbortController();
  const t = timeoutMs && timeoutMs > 0 ? setTimeout(() => controller.abort(), timeoutMs) : null;

  try {
    const res = await fetch(url, { ...rest, signal: controller.signal });
    const text = await res.text();
    let json: any = null;
    try {
      json = text ? JSON.parse(text) : null;
    } catch {
      json = null;
    }
    return { status: res.status, json, text };
  } finally {
    if (t) clearTimeout(t);
  }
}
