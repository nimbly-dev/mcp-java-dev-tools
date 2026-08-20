package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactJsonStore;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactOperationException;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactPathPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves a selected project execution profile into concrete HTTP workload requests. */
final class ExecutionExportWorkload {

    private static final List<String> SUITE_TYPES = List.of("regression", "performance", "security");
    private static final Pattern TEMPLATE = Pattern.compile("\\$\\{([^}]+)}");

    private ExecutionExportWorkload() {
    }

    static Workload resolve(
            ArtifactJsonStore store,
            ArtifactPathPolicy policy,
            String projectName,
            JsonNode projectArtifact,
            WorkloadSelection context) {
        JsonNode profile = selectProfile(projectArtifact, context.requestedProfile(), context.requestedPlan());
        String profileName = profile == null
                ? context.requestedProfile() : text(profile, "executionProfile").orElse(null);
        String profileSuite = profile == null ? null : text(profile, "suiteType").orElse(null);
        List<PlanSelection> selections = selectPlans(profile, context.requestedPlan());
        if (selections.isEmpty() && context.requestedPlan() != null) {
            selections = List.of(new PlanSelection(1, context.requestedPlan(), profileSuite));
        }
        if (selections.isEmpty()) {
            throw new ArtifactOperationException(
                    "execution_export_workload_required", "A plan or execution profile workload is required");
        }

        List<PlanWorkload> plans = new ArrayList<>();
        for (PlanSelection planSelection : selections) {
            String suiteType = resolveSuiteType(policy, projectName, planSelection, profileSuite);
            Path contractPath = policy.resolve(
                    ".mcpjvm", projectName, "plans", suiteType, planSelection.planName(), "contract.json");
            if (!Files.isRegularFile(contractPath)) {
                throw new ArtifactOperationException(
                        "execution_export_workload_missing", "Selected plan contract is unavailable");
            }
            JsonNode contract = store.read(contractPath);
            List<WorkloadRequest> requests = workloadRequests(
                    contract, planSelection.planName(), context.contextBindings());
            if (requests.isEmpty()) {
                throw new ArtifactOperationException(
                        "execution_export_workload_empty", "Selected plan contains no executable workload steps");
            }
            plans.add(new PlanWorkload(planSelection.order(), suiteType, planSelection.planName(), requests));
        }
        plans.sort(Comparator.comparingInt(PlanWorkload::order));
        return new Workload(profileName, plans);
    }

    private static JsonNode selectProfile(JsonNode projectArtifact, String requestedProfile, String requestedPlan) {
        List<JsonNode> profiles = new ArrayList<>();
        for (JsonNode workspace : projectArtifact.path("workspaces")) {
            for (JsonNode profile : workspace.path("executionProfiles")) {
                profiles.add(profile);
            }
        }
        if (requestedProfile != null) {
            return profiles.stream()
                    .filter(profile -> requestedProfile.equals(text(profile, "executionProfile").orElse(null)))
                    .findFirst()
                    .orElseThrow(() -> new ArtifactOperationException(
                            "execution_profile_not_found", "Selected execution profile is unavailable"));
        }
        if (requestedPlan != null) {
            List<JsonNode> matching = profiles.stream()
                    .filter(profile -> profileHasPlan(profile, requestedPlan))
                    .toList();
            if (matching.size() == 1) {
                return matching.getFirst();
            }
        }
        if (profiles.size() == 1) {
            return profiles.getFirst();
        }
        return null;
    }

    private static boolean profileHasPlan(JsonNode profile, String requestedPlan) {
        for (JsonNode plan : profile.path("plans")) {
            if (requestedPlan.equals(text(plan, "planName").orElse(null))) {
                return true;
            }
        }
        return false;
    }

    private static List<PlanSelection> selectPlans(JsonNode profile, String requestedPlan) {
        if (profile == null) {
            return List.of();
        }
        List<PlanSelection> plans = new ArrayList<>();
        for (JsonNode plan : profile.path("plans")) {
            String planName = text(plan, "planName").orElse(null);
            if (planName == null || (requestedPlan != null && !requestedPlan.equals(planName))) {
                continue;
            }
            int order = plan.path("order").canConvertToInt() ? plan.path("order").intValue() : plans.size() + 1;
            plans.add(new PlanSelection(order, planName, text(plan, "suiteType").orElse(null)));
        }
        if (requestedPlan != null && plans.isEmpty()) {
            return List.of();
        }
        plans.sort(Comparator.comparingInt(PlanSelection::order));
        return plans;
    }

    private static String resolveSuiteType(
            ArtifactPathPolicy policy,
            String projectName,
            PlanSelection selection,
            String profileSuite) {
        List<String> candidates;
        if (selection.suiteType() != null) {
            candidates = List.of(selection.suiteType());
        } else if (profileSuite != null) {
            candidates = List.of(profileSuite);
        } else {
            candidates = SUITE_TYPES;
        }
        List<String> existing = candidates.stream()
                .filter(SUITE_TYPES::contains)
                .filter(suite -> Files.isRegularFile(policy.resolve(
                        ".mcpjvm", projectName, "plans", suite, selection.planName(), "contract.json")))
                .toList();
        if (existing.size() != 1) {
            throw new ArtifactOperationException(
                    existing.isEmpty() ? "execution_export_workload_missing" : "execution_export_plan_ambiguous",
                    existing.isEmpty()
                            ? "Selected plan contract is unavailable"
                            : "Selected plan exists in multiple suite families");
        }
        return existing.getFirst();
    }

    private static List<WorkloadRequest> workloadRequests(
            JsonNode contract, String planName, Map<String, String> contextBindings) {
        List<WorkloadRequest> requests = new ArrayList<>();
        if (contract.path("steps").isArray()) {
            for (JsonNode step : contract.path("steps")) {
                String protocol = text(step, "protocol").orElse("http");
                if (!"http".equalsIgnoreCase(protocol)) {
                    throw unsupported();
                }
                JsonNode http = step.path("transport").path("http");
                requests.add(toRequest(http, step.path("providedContext"),
                        text(step, "id").orElse("step-" + (requests.size() + 1)), planName, contextBindings));
            }
        } else if (contract.path("entrypoints").isArray()) {
            for (JsonNode entrypoint : contract.path("entrypoints")) {
                JsonNode transport = entrypoint.path("transport");
                String protocol = text(transport, "protocol").orElse("http");
                if (!"http".equalsIgnoreCase(protocol)) {
                    throw unsupported();
                }
                requests.add(toEntrypointRequest(entrypoint, planName, requests.size() + 1, contextBindings));
            }
        }
        return requests;
    }

    private static WorkloadRequest toEntrypointRequest(
            JsonNode entrypoint, String planName, int index, Map<String, String> contextBindings) {
        JsonNode transport = entrypoint.path("transport");
        JsonNode request = entrypoint.path("request");
        com.fasterxml.jackson.databind.node.ObjectNode http =
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                .put("method", text(request, "method").orElse("GET"))
                .put("pathTemplate", text(request, "path").orElse(text(request, "pathTemplate").orElse("")))
                .put("baseUrl", text(transport, "baseUrl").orElse(""));
        if (request.has("body")) {
            http.set("body", request.get("body"));
        }
        if (request.has("headers")) {
            http.set("headers", request.get("headers"));
        }
        return toRequest(http, entrypoint.path("providedContext"),
                text(entrypoint, "id").orElse("entrypoint-" + index), planName, contextBindings);
    }

    private static WorkloadRequest toRequest(
            JsonNode http,
            JsonNode context,
            String stepId,
            String planName,
            Map<String, String> contextBindings) {
        String directUrl = text(http, "url").orElse(null);
        String path = text(http, "pathTemplate").orElse(text(http, "path").orElse(null));
        String baseUrl = text(http, "baseUrl").orElse(text(context, "apiBaseUrl").orElse(null));
        String url = directUrl == null ? joinUrl(baseUrl, path, contextBindings)
                : normalizeTemplate(directUrl, contextBindings);
        if (url == null) {
            throw new ArtifactOperationException(
                    "execution_export_workload_unresolved", "Workload URL could not be resolved");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        JsonNode headerNode = http.path("headers");
        if (headerNode.isObject()) {
            headerNode.fields().forEachRemaining(entry -> headers.put(
                    entry.getKey(), headerValue(entry.getKey(), entry.getValue().asText(""), contextBindings)));
        }
        String body = null;
        if (http.has("body") && !http.get("body").isNull()) {
            body = http.get("body").isTextual()
                    ? normalizeTemplate(http.get("body").textValue(), contextBindings)
                    : http.get("body").toString();
        }
        String method = text(http, "method").orElse("GET").toUpperCase(java.util.Locale.ROOT);
        return new WorkloadRequest(planName, stepId, method, url, headers, body);
    }

    private static String joinUrl(String baseUrl, String path, Map<String, String> contextBindings) {
        if (path == null || path.isBlank()) {
            return baseUrl == null ? null : normalizeTemplate(baseUrl, contextBindings);
        }
        String normalizedPath = normalizeTemplate(path, contextBindings);
        if (normalizedPath.startsWith("http://") || normalizedPath.startsWith("https://")
                || normalizedPath.startsWith("{{")) {
            return normalizedPath;
        }
        String base;
        if (baseUrl == null || baseUrl.isBlank()) {
            base = "{{API_BASE_URL}}";
        } else {
            base = normalizeTemplate(baseUrl, contextBindings);
        }
        return base.replaceAll("/$", "") + (normalizedPath.startsWith("/") ? "" : "/") + normalizedPath;
    }

    private static String headerValue(String name, String value, Map<String, String> contextBindings) {
        String normalizedName = name.toLowerCase(java.util.Locale.ROOT);
        if (normalizedName.contains("authorization") || normalizedName.contains("cookie")
                || normalizedName.contains("token") || normalizedName.contains("secret")
                || normalizedName.contains("password") || normalizedName.contains("credential")) {
            return TEMPLATE.matcher(value).find()
                    ? normalizeTemplate(value, contextBindings)
                    : "{{" + environmentKey(name, contextBindings) + "}}";
        }
        return normalizeTemplate(value, contextBindings);
    }

    private static String environmentKey(String value, Map<String, String> contextBindings) {
        String bound = contextBindings.get(value);
        if (bound != null && !bound.isBlank()) {
            return bound;
        }
        return value.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
    }

    private static String normalizeTemplate(String value, Map<String, String> contextBindings) {
        Matcher matcher = TEMPLATE.matcher(value);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(output, "{{" + environmentKey(matcher.group(1), contextBindings) + "}}");
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private static ArtifactOperationException unsupported() {
        return new ArtifactOperationException(
                "execution_export_transport_unsupported", "Selected plan contains an unsupported transport");
    }

    private static Optional<String> text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() && !value.asText().isBlank()
                ? Optional.of(value.asText().trim()) : Optional.empty();
    }

    record Workload(String executionProfile, List<PlanWorkload> plans) {
    }

    record PlanWorkload(int order, String suiteType, String planName, List<WorkloadRequest> requests) {
    }

    record WorkloadRequest(
            String planName,
            String stepId,
            String method,
            String url,
            Map<String, String> headers,
            String body) {
    }

    record WorkloadSelection(
            String requestedProfile,
            String requestedPlan,
            Map<String, String> contextBindings) {
    }

    private record PlanSelection(int order, String planName, String suiteType) {
    }
}
