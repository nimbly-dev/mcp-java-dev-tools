import { promises as fs } from "node:fs";
import path from "node:path";

import { fetchJson } from "@/lib/http";
import { clampInt, DEFAULT_PROBE_TIMEOUT_MS, HARD_MAX_PROBE_TIMEOUT_MS } from "@/lib/safety";
import { probeUnreachableMessage, joinUrl } from "@/utils/probe.util";
import type { ToolTextResponse } from "@/models/tool_response.model";
import { buildTextResponse } from "@/utils/probe/response_builders.util";

export async function probeProfiler(args: {
  action: "start" | "stop" | "reset" | "status" | "download";
  baseUrl: string;
  profilerPath: string;
  sessionId?: string;
  event?: string;
  intervalNanos?: number;
  outputPath?: string;
  outputFormat?: "jfr";
  timeoutMs?: number;
}): Promise<ToolTextResponse> {
  const timeoutMs = clampInt(args.timeoutMs ?? DEFAULT_PROBE_TIMEOUT_MS, 1_000, HARD_MAX_PROBE_TIMEOUT_MS);
  const url = joinUrl(args.baseUrl, args.profilerPath);

  if (args.action === "download") {
    if (typeof args.outputPath !== "string" || args.outputPath.trim().length === 0) {
      throw new Error("probe profiler download requires outputPath");
    }
    const downloadUrl = new URL(url);
    downloadUrl.searchParams.set("action", "download");
    if (typeof args.sessionId === "string") downloadUrl.searchParams.set("sessionId", args.sessionId);
    let response: Response;
    try {
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), timeoutMs);
      try {
        response = await fetch(downloadUrl.toString(), { method: "GET", signal: controller.signal });
      } finally {
        clearTimeout(timer);
      }
    } catch (err) {
      throw new Error(probeUnreachableMessage(downloadUrl.toString(), err));
    }
    if (!response.ok) {
      const text = await response.text();
      let json: unknown = null;
      try {
        json = text ? JSON.parse(text) : null;
      } catch {
        json = null;
      }
      const structuredContent: Record<string, unknown> = {
        request: {
          action: args.action,
          url: downloadUrl.toString(),
          timeoutMs,
          ...(typeof args.sessionId === "string" ? { sessionId: args.sessionId } : {}),
          outputPath: args.outputPath,
        },
        response: { status: response.status, json, text },
        result: { status: "profiler_download_failed" },
      };
      return buildTextResponse(structuredContent, JSON.stringify(structuredContent, null, 2));
    }
    const outputPathAbs = path.resolve(args.outputPath.trim());
    await fs.mkdir(path.dirname(outputPathAbs), { recursive: true });
    const bytes = Buffer.from(await response.arrayBuffer());
    await fs.writeFile(outputPathAbs, bytes);
    const structuredContent: Record<string, unknown> = {
      request: {
        action: args.action,
        url: downloadUrl.toString(),
        timeoutMs,
        ...(typeof args.sessionId === "string" ? { sessionId: args.sessionId } : {}),
        outputPath: outputPathAbs,
      },
      response: { status: response.status, bytesWritten: bytes.length },
      result: {
        status: "downloaded",
        outputPath: outputPathAbs,
        bytesWritten: bytes.length,
      },
    };
    return buildTextResponse(structuredContent, JSON.stringify(structuredContent, null, 2));
  }

  let res;
  try {
    if (args.action === "status") {
      res = await fetchJson(url, { method: "GET", timeoutMs });
    } else {
      const payload: Record<string, unknown> = { action: args.action };
      if (typeof args.sessionId === "string") payload.sessionId = args.sessionId;
      if (typeof args.event === "string") payload.event = args.event;
      if (typeof args.intervalNanos === "number") payload.intervalNanos = args.intervalNanos;
      if (typeof args.outputPath === "string") payload.outputPath = args.outputPath;
      if (typeof args.outputFormat === "string") payload.outputFormat = args.outputFormat;
      res = await fetchJson(url, {
        method: "POST",
        timeoutMs,
        headers: { "content-type": "application/json" },
        body: JSON.stringify(payload),
      });
    }
  } catch (err) {
    throw new Error(probeUnreachableMessage(url, err));
  }

  const json = (res.json as Record<string, unknown> | null) ?? null;
  const profiler =
    json && typeof json.profiler === "object" && json.profiler !== null
      ? (json.profiler as Record<string, unknown>)
      : null;
  const structuredContent: Record<string, unknown> = {
    request: {
      action: args.action,
      url,
      timeoutMs,
      ...(typeof args.sessionId === "string" ? { sessionId: args.sessionId } : {}),
    },
    response: { status: res.status, json },
    result: profiler ?? { status: "profiler_response_invalid" },
  };
  return buildTextResponse(structuredContent, JSON.stringify(structuredContent, null, 2));
}
