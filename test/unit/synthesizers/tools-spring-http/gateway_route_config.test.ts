const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const { resolveGatewayRouteConfig } = require("@tools-spring-http/gateway_route_config.util");

function createTestTempDir(prefix: string): string {
  const base = path.join(process.cwd(), "test", ".tmp");
  fs.mkdirSync(base, { recursive: true });
  return fs.mkdtempSync(path.join(base, `${prefix}-`));
}

test("[UT][spring-http][gateway_route_config] resolveGatewayRouteConfig parses application.yml Path predicate", async () => {
  const root = createTestTempDir("gateway-route-yml");
  try {
    const appYml = path.join(root, "src", "main", "resources", "application.yml");
    fs.mkdirSync(path.dirname(appYml), { recursive: true });
    fs.writeFileSync(
      appYml,
      [
        "spring:",
        "  cloud:",
        "    gateway:",
        "      routes:",
        "      - id: course-service",
        "        uri: lb://course-service",
        "        predicates:",
        "        - Path=/courses/**",
      ].join("\n"),
      "utf8",
    );

    const out = await resolveGatewayRouteConfig({ projectRootAbs: root });
    assert.equal(out.status, "ok");
    assert.equal(out.requestCandidate.method, "GET");
    assert.equal(out.requestCandidate.path, "/courses/**");
    assert.equal(out.evidence.includes("mapping_source=spring_gateway_route_config"), true);
  } finally {
    fs.rmSync(root, { recursive: true, force: true, maxRetries: 50, retryDelay: 100 });
  }
});

test("[UT][spring-http][gateway_route_config] resolveGatewayRouteConfig parses application.properties Path predicate", async () => {
  const root = createTestTempDir("gateway-route-properties");
  try {
    const appProps = path.join(root, "src", "main", "resources", "application.properties");
    fs.mkdirSync(path.dirname(appProps), { recursive: true });
    fs.writeFileSync(
      appProps,
      "spring.cloud.gateway.routes[0].predicates[0]=Path=/reviews/**\n",
      "utf8",
    );

    const out = await resolveGatewayRouteConfig({ projectRootAbs: root });
    assert.equal(out.status, "ok");
    assert.equal(out.requestCandidate.path, "/reviews/**");
  } finally {
    fs.rmSync(root, { recursive: true, force: true, maxRetries: 50, retryDelay: 100 });
  }
});

test("[UT][spring-http][gateway_route_config] resolveGatewayRouteConfig fails closed when no gateway route config exists", async () => {
  const root = createTestTempDir("gateway-route-missing");
  try {
    const out = await resolveGatewayRouteConfig({ projectRootAbs: root });
    assert.equal(out.status, "report");
    assert.equal(out.reasonCode, "spring_gateway_route_not_found");
    assert.equal(out.failedStep, "gateway_route_config_resolution");
  } finally {
    fs.rmSync(root, { recursive: true, force: true, maxRetries: 50, retryDelay: 100 });
  }
});
