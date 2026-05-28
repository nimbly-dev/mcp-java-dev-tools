import assert from "node:assert/strict";
import * as http from "node:http";
import test from "node:test";

import { startMcpClient } from "@test/integrations/support/spring/social_platform/shared.fixture";

type ToolResult = {
  content?: Array<{ type?: string; text?: string }>;
  structuredContent?: Record<string, unknown>;
};

async function callTool(
  mcp: Awaited<ReturnType<typeof startMcpClient>>,
  name: string,
  args: Record<string, unknown>,
): Promise<ToolResult> {
  return (await mcp.client.callTool({
    name,
    arguments: args,
  })) as ToolResult;
}

async function listenEphemeralServer(
  handler: (req: http.IncomingMessage, res: http.ServerResponse) => void,
): Promise<{ server: http.Server; url: string; close: () => Promise<void> }> {
  const server = http.createServer(handler);
  await new Promise<void>((resolve) => {
    server.listen(0, "127.0.0.1", () => resolve());
  });
  const address = server.address();
  if (!address || typeof address === "string") {
    await new Promise<void>((resolve) => server.close(() => resolve()));
    throw new Error("Failed to bind local HTTP server.");
  }
  return {
    server,
    url: `http://127.0.0.1:${address.port}`,
    close: async () => {
      await new Promise<void>((resolve) => server.close(() => resolve()));
    },
  };
}

test("mcp IT: transport_execute preserves large Authorization header values", async () => {
  const token = `eyJ.${"A".repeat(1536)}.sig`;
  const authorization = `Bearer ${token}`;
  const expectedLength = authorization.length;

  const echo = await listenEphemeralServer((req, res) => {
    const observed = String(req.headers.authorization ?? "");
    const payload = JSON.stringify({
      observedLength: observed.length,
      expectedLength,
      exactMatch: observed === authorization,
    });
    res.writeHead(200, { "content-type": "application/json" });
    res.end(payload);
  });

  let mcp: Awaited<ReturnType<typeof startMcpClient>> | undefined;
  try {
    mcp = await startMcpClient({
      workspaceRootAbs: process.cwd(),
      probeBaseUrl: "http://127.0.0.1:9191",
    });

    const out = await callTool(mcp, "transport_execute", {
      protocol: "http",
      request: {
        method: "POST",
        url: `${echo.url}/auth-check`,
        headers: {
          Authorization: authorization,
          "Content-Type": "application/json",
          "X-Tenant-Id": "TENANT1_TST",
        },
        body: { id: "HIERARCHY", structure: null },
      },
      options: {
        wrappedOnly: true,
      },
    });

    assert.equal(out.structuredContent?.status, "pass");
    assert.equal(out.structuredContent?.statusCode, 200);
    assert.equal(typeof out.structuredContent?.bodyPreview, "string");

    const payload = JSON.parse(String(out.structuredContent?.bodyPreview ?? "{}")) as {
      observedLength?: number;
      expectedLength?: number;
      exactMatch?: boolean;
    };
    assert.equal(payload.expectedLength, expectedLength);
    assert.equal(payload.observedLength, expectedLength);
    assert.equal(payload.exactMatch, true);
  } finally {
    await mcp?.close();
    await echo.close();
  }
});
