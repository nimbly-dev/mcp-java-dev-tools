const assert = require("node:assert/strict");
const test = require("node:test");

const { synthesizeSpringRecipe } = require("@tools-spring-http/synthesis.util");

test("[UT][spring-http][synthesis] spring synthesizer maps AST resolver success into a request recipe", async () => {
  const result = await synthesizeSpringRecipe(
    {
      rootAbs: "C:\\repo\\service",
      workspaceRootAbs: "C:\\repo",
      searchRootsAbs: ["C:\\repo\\service"],
      classHint: "HealthController",
      methodHint: "health",
      intentMode: "regression",
      inferredTargetFileAbs: "C:\\repo\\service\\src\\main\\java\\HealthController.java",
    },
    {
      resolveRequestMappingFn: async () => ({
        status: "ok",
        contractVersion: "0.1.0",
        framework: "spring_mvc",
        requestSource: "spring_mvc",
        requestCandidate: {
          method: "GET",
          path: "/v1/health",
          queryTemplate: "",
          fullUrlHint: "/v1/health",
          rationale: ["ast"],
        },
        matchedTypeFile: "C:\\repo\\service\\src\\main\\java\\HealthController.java",
        matchedRootAbs: "C:\\repo\\service",
        evidence: ["resolvedType=com.example.HealthController"],
        attemptedStrategies: ["java_ast_index_lookup", "java_ast_spring_mvc_resolver"],
      }),
    },
  );

  assert.equal(result.status, "recipe");
  assert.equal(result.synthesizerUsed, "spring");
  assert.equal(result.requestCandidate.method, "GET");
  assert.equal(result.requestSource, "spring_mvc");
  assert.deepEqual(result.attemptedStrategies, [
    "java_ast_index_lookup",
    "java_ast_spring_mvc_resolver",
  ]);
  assert.equal(
    result.evidence.some((entry: string) => entry.startsWith("ast_context_path_hint=")),
    false,
  );
});

test("[UT][spring-http][synthesis] spring synthesizer consumes optional resolver extensions without changing contract", async () => {
  const result = await synthesizeSpringRecipe(
    {
      rootAbs: "C:\\repo\\service",
      workspaceRootAbs: "C:\\repo",
      searchRootsAbs: ["C:\\repo\\service"],
      classHint: "HealthController",
      methodHint: "health",
      intentMode: "regression",
    },
    {
      resolveRequestMappingFn: async () => ({
        status: "ok",
        contractVersion: "0.1.0",
        framework: "spring_mvc",
        requestSource: "spring_mvc",
        requestCandidate: {
          method: "GET",
          path: "/v1/health",
          queryTemplate: "",
          fullUrlHint: "/v1/health",
          rationale: ["ast"],
        },
        matchedTypeFile: "C:\\repo\\service\\src\\main\\java\\HealthController.java",
        matchedRootAbs: "C:\\repo\\service",
        evidence: ["resolvedType=com.example.HealthController"],
        attemptedStrategies: ["java_ast_index_lookup", "java_ast_spring_mvc_resolver"],
        extensions: {
          contextPathHint: "/api/v1",
          ignoredNonBreakingField: "value",
        },
      }),
    },
  );

  assert.equal(result.status, "recipe");
  assert.equal(result.synthesizerUsed, "spring");
  assert.equal(result.requestCandidate.path, "/v1/health");
  assert.equal(result.evidence.includes("ast_context_path_hint=/api/v1"), true);
});

test("[UT][spring-http][synthesis] spring synthesizer surfaces AST resolver bootstrap failures distinctly", async () => {
  const result = await synthesizeSpringRecipe(
    {
      rootAbs: "C:\\repo\\service",
      workspaceRootAbs: "C:\\repo",
      searchRootsAbs: ["C:\\repo\\service"],
      classHint: "HealthController",
      methodHint: "health",
      intentMode: "regression",
    },
    {
      resolveRequestMappingFn: async () => ({
        status: "report",
        contractVersion: "0.1.0",
        reasonCode: "ast_resolver_unavailable",
        failedStep: "request_mapping_resolver_bootstrap",
        nextAction: "Build resolver JAR.",
        evidence: ["resolver_jar_missing=true"],
        attemptedStrategies: ["java_ast_resolver_bootstrap"],
      }),
    },
  );

  assert.equal(result.status, "report");
  assert.equal(result.reasonCode, "ast_resolver_unavailable");
  assert.equal(result.failedStep, "request_mapping_resolver_bootstrap");
});

test("[UT][spring-http][synthesis] spring synthesizer preserves resolver-specific target ambiguity failures", async () => {
  const result = await synthesizeSpringRecipe(
    {
      rootAbs: "C:\\repo\\service",
      workspaceRootAbs: "C:\\repo",
      searchRootsAbs: ["C:\\repo\\service"],
      classHint: "CatalogController",
      methodHint: "getCatalog",
      intentMode: "regression",
    },
    {
      resolveRequestMappingFn: async () => ({
        status: "report",
        contractVersion: "0.1.0",
        reasonCode: "target_type_ambiguous",
        failedStep: "request_mapping_resolution",
        nextAction: "Narrow target type scope.",
        evidence: ["matched_types=2"],
        attemptedStrategies: ["java_ast_index_lookup", "java_ast_framework_resolution"],
      }),
    },
  );

  assert.equal(result.status, "report");
  assert.equal(result.reasonCode, "target_type_ambiguous");
  assert.equal(result.failedStep, "request_mapping_resolution");
  assert.equal(result.nextAction, "Narrow target type scope.");
  assert.deepEqual(result.evidence, ["matched_types=2"]);
});

test("[UT][spring-http][synthesis] spring synthesizer maps generic AST mapping failures to spring entrypoint failures", async () => {
  const result = await synthesizeSpringRecipe(
    {
      rootAbs: "C:\\repo\\service",
      workspaceRootAbs: "C:\\repo",
      searchRootsAbs: ["C:\\repo\\service"],
      classHint: "CatalogController",
      methodHint: "getCatalog",
      lineHint: 41,
      intentMode: "regression_plus_line_probe",
    },
    {
      resolveRequestMappingFn: async () => ({
        status: "report",
        contractVersion: "0.1.0",
        reasonCode: "request_mapping_not_proven",
        failedStep: "request_mapping_resolution",
        nextAction: "Refine hints.",
        evidence: ["resolvedType=com.example.CatalogController"],
        attemptedStrategies: ["java_ast_index_lookup", "java_ast_framework_resolution"],
      }),
    },
  );

  assert.equal(result.status, "report");
  assert.equal(result.reasonCode, "spring_entrypoint_not_proven");
  assert.equal(result.failedStep, "spring_entrypoint_resolution");
  assert.match(result.nextAction, /AST-backed request mapping resolution/);
});

test("[UT][spring-http][synthesis] spring synthesizer passes computed search roots into AST resolver input", async () => {
  let capturedInput;
  await synthesizeSpringRecipe(
    {
      rootAbs: "C:\\repo\\service",
      workspaceRootAbs: "C:\\repo",
      searchRootsAbs: ["C:\\repo\\service", "C:\\repo\\workspace"],
      classHint: "com.example.social.post.app.controller.PostController",
      methodHint: "updatePost",
      lineHint: 40,
      intentMode: "regression_plus_line_probe",
    },
    {
      resolveRequestMappingFn: async (input: any) => {
        capturedInput = input;
        return {
          status: "report",
          contractVersion: "0.1.0",
          reasonCode: "request_mapping_not_proven",
          failedStep: "request_mapping_resolution",
          nextAction: "Refine hints.",
          evidence: ["resolvedType=com.example.social.post.app.controller.PostController"],
          attemptedStrategies: ["java_ast_index_lookup", "java_ast_framework_resolution"],
        };
      },
    },
  );

  assert.ok(capturedInput);
  const actualInput = capturedInput as any;
  assert.deepEqual(actualInput.searchRootsAbs, ["C:\\repo\\service", "C:\\repo\\workspace"]);
});

test("[UT][spring-http][synthesis] spring synthesizer runtime_first uses actuator mappings before AST", async () => {
  let astCalls = 0;
  const result = await synthesizeSpringRecipe(
    {
      rootAbs: "C:\\repo\\service",
      workspaceRootAbs: "C:\\repo",
      searchRootsAbs: ["C:\\repo\\service"],
      classHint: "com.example.HealthController",
      methodHint: "health",
      intentMode: "regression",
      discoveryPreference: "runtime_first",
      mappingsBaseUrl: "http://127.0.0.1:8080/actuator/mappings",
    },
    {
      resolveRuntimeMappingsFn: async () => ({
        status: "ok",
        requestCandidate: {
          method: "GET",
          path: "/v2/health",
          queryTemplate: "",
          fullUrlHint: "/v2/health",
          rationale: ["runtime"],
        },
        evidence: ["mapping_source=runtime_actuator"],
        attemptedStrategies: ["spring_runtime_actuator_mappings"],
      }),
      resolveRequestMappingFn: async () => {
        astCalls += 1;
        throw new Error("AST resolver should not run when runtime mappings succeed");
      },
    },
  );

  assert.equal(astCalls, 0);
  assert.equal(result.status, "recipe");
  assert.equal(result.requestCandidate.path, "/v2/health");
  assert.equal(result.evidence.includes("mapping_source=runtime_actuator"), true);
});

test("[UT][spring-http][synthesis] spring synthesizer runtime_first falls back to AST when runtime mappings fail", async () => {
  const result = await synthesizeSpringRecipe(
    {
      rootAbs: "C:\\repo\\service",
      workspaceRootAbs: "C:\\repo",
      searchRootsAbs: ["C:\\repo\\service"],
      classHint: "com.example.HealthController",
      methodHint: "health",
      intentMode: "regression",
      discoveryPreference: "runtime_first",
      mappingsBaseUrl: "http://127.0.0.1:8080/actuator/mappings",
    },
    {
      resolveRuntimeMappingsFn: async () => ({
        status: "report",
        reasonCode: "runtime_mappings_unreachable",
        failedStep: "runtime_mapping_fetch",
        nextAction: "Fix endpoint",
        evidence: ["httpStatus=503"],
        attemptedStrategies: ["spring_runtime_actuator_mappings"],
      }),
      resolveRequestMappingFn: async () => ({
        status: "ok",
        contractVersion: "0.1.0",
        framework: "spring_mvc",
        requestSource: "spring_mvc",
        requestCandidate: {
          method: "GET",
          path: "/v1/health",
          queryTemplate: "",
          fullUrlHint: "/v1/health",
          rationale: ["ast"],
        },
        matchedTypeFile: "C:\\repo\\service\\src\\main\\java\\HealthController.java",
        matchedRootAbs: "C:\\repo\\service",
        evidence: ["resolvedType=com.example.HealthController"],
        attemptedStrategies: ["java_ast_index_lookup"],
      }),
    },
  );

  assert.equal(result.status, "recipe");
  assert.equal(result.requestCandidate.path, "/v1/health");
  assert.equal(
    result.evidence.includes("runtime_mappings_fallback_reason=runtime_mappings_unreachable"),
    true,
  );
  assert.equal(result.attemptedStrategies.includes("spring_runtime_actuator_mappings"), true);
});

test("[UT][spring-http][synthesis] spring synthesizer runtime_only fails closed when runtime mappings are unreachable", async () => {
  const result = await synthesizeSpringRecipe(
    {
      rootAbs: "C:\\repo\\service",
      workspaceRootAbs: "C:\\repo",
      searchRootsAbs: ["C:\\repo\\service"],
      classHint: "com.example.HealthController",
      methodHint: "health",
      intentMode: "regression",
      discoveryPreference: "runtime_only",
      mappingsBaseUrl: "http://127.0.0.1:8080/actuator/mappings",
    },
    {
      resolveRuntimeMappingsFn: async () => ({
        status: "report",
        reasonCode: "runtime_mappings_unreachable",
        failedStep: "runtime_mapping_fetch",
        nextAction: "Fix endpoint",
        evidence: ["httpStatus=503"],
        attemptedStrategies: ["spring_runtime_actuator_mappings"],
      }),
      resolveRequestMappingFn: async () => {
        throw new Error("AST resolver should not run in runtime_only mode");
      },
    },
  );

  assert.equal(result.status, "report");
  assert.equal(result.reasonCode, "runtime_mappings_unreachable");
  assert.equal(result.failedStep, "runtime_mapping_fetch");
});

test("[UT][spring-http][synthesis] spring synthesizer resolves gateway entrypoint via gateway route config fallback", async () => {
  const result = await synthesizeSpringRecipe(
    {
      rootAbs: "C:\\repo\\gateway-service",
      workspaceRootAbs: "C:\\repo",
      searchRootsAbs: ["C:\\repo\\gateway-service"],
      classHint: "com.example.springcloud.gateway.GatewayServiceApplication",
      methodHint: "main",
      intentMode: "regression",
    },
    {
      resolveRequestMappingFn: async () => ({
        status: "report",
        contractVersion: "0.1.0",
        reasonCode: "request_mapping_not_proven",
        failedStep: "request_mapping_resolution",
        nextAction: "Refine hints.",
        evidence: ["resolvedType=com.example.springcloud.gateway.GatewayServiceApplication"],
        attemptedStrategies: ["java_ast_index_lookup", "java_ast_framework_resolution"],
      }),
      resolveGatewayRouteConfigFn: async () => ({
        status: "ok",
        requestCandidate: {
          method: "GET",
          path: "/courses/**",
          queryTemplate: "",
          fullUrlHint: "/courses/**",
          rationale: ["gateway route config"],
        },
        evidence: ["mapping_source=spring_gateway_route_config", "gateway_path=/courses/**"],
        attemptedStrategies: ["spring_gateway_route_config"],
      }),
    },
  );

  assert.equal(result.status, "recipe");
  assert.equal(result.requestCandidate.path, "/courses/**");
  assert.equal(result.evidence.includes("request_source=spring_gateway_route_config"), true);
  assert.equal(result.attemptedStrategies.includes("spring_gateway_route_config"), true);
});

test("[UT][spring-http][synthesis] spring synthesizer returns gateway-specific fail-closed reason when entrypoint fallback has no route", async () => {
  const result = await synthesizeSpringRecipe(
    {
      rootAbs: "C:\\repo\\gateway-service",
      workspaceRootAbs: "C:\\repo",
      searchRootsAbs: ["C:\\repo\\gateway-service"],
      classHint: "com.example.springcloud.gateway.GatewayServiceApplication",
      methodHint: "main",
      intentMode: "regression",
    },
    {
      resolveRequestMappingFn: async () => ({
        status: "report",
        contractVersion: "0.1.0",
        reasonCode: "request_mapping_not_proven",
        failedStep: "request_mapping_resolution",
        nextAction: "Refine hints.",
        evidence: ["resolvedType=com.example.springcloud.gateway.GatewayServiceApplication"],
        attemptedStrategies: ["java_ast_index_lookup", "java_ast_framework_resolution"],
      }),
      resolveGatewayRouteConfigFn: async () => ({
        status: "report",
        reasonCode: "spring_gateway_route_not_found",
        failedStep: "gateway_route_config_resolution",
        nextAction: "Provide explicit route context.",
        evidence: ["scannedConfigFiles=(none)"],
        attemptedStrategies: ["spring_gateway_route_config"],
      }),
    },
  );

  assert.equal(result.status, "report");
  assert.equal(result.reasonCode, "spring_gateway_route_not_found");
  assert.equal(result.failedStep, "gateway_route_config_resolution");
  assert.equal(result.attemptedStrategies.includes("spring_gateway_route_config"), true);
});
