package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactOperationException;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/** Bundles the repository-owned Sidecar attach tools for dynamic-attach exports. */
public final class ExecutionExportPortableSidecar {

    private ExecutionExportPortableSidecar() {
    }

    public static void writeIfRequired(
            Path export,
            Path workspaceRoot,
            JsonNode workspace,
            JsonNode profile,
            ObjectMapper mapper) {
        JsonNode context = findContext(workspace, profile == null ? NullNode.getInstance() : profile);
        JsonNode policy = context.path("sidecarLifecycle");
        if (!"dynamic_attach_local".equals(policy.path("activation").asText())) {
            return;
        }
        validateContext(context, policy);
        String probeId = required(policy, "probeId", "portable_dynamic_attach_runtime_invalid");
        JsonNode probe = findProbe(workspaceRoot, probeId, mapper);
        String helper = copyArtifact(export, "MCP_JAVA_ATTACH_HELPER_JAR",
                workspaceRoot, "java-agent/core/core-jvm-attach/target", "mcp-java-dev-tools-core-jvm-attach-");
        String agent = copyArtifact(export, "MCP_JAVA_AGENT_JAR",
                workspaceRoot, "java-agent/core/core-probe/target", "mcp-java-dev-tools-agent-");
        ObjectNode config = mapper.createObjectNode();
        config.put("version", 1);
        config.put("runtimeContextName", required(context, "name", "portable_dynamic_attach_runtime_invalid"));
        config.put("startupName", required(policy, "targetStartupName", "portable_dynamic_attach_runtime_invalid"));
        config.put("probeId", probeId);
        config.put("probeHost", probe.path("host").asText());
        config.put("probePort", probe.path("port").asInt());
        config.put("helperJarRel", helper);
        config.put("agentJarRel", agent);
        config.put("statusPath", "/__probe/status");
        config.put("resetPath", "/__probe/reset");
        if (probe.has("include")) {
            config.set("include", probe.get("include"));
        }
        if (probe.has("exclude")) {
            config.set("exclude", probe.get("exclude"));
        }
        write(export.resolve("portable-sidecar-attach.config.json"), config.toPrettyString() + "\n");
        write(export.resolve("run-portable-sidecar-lifecycle.js"), runner());
    }

    private static void validateContext(JsonNode context, JsonNode policy) {
        if (!"terminal".equals(context.path("mode").asText())
                || !context.path("autoStart").asBoolean(false)
                || !context.path("autoStopOnFinish").asBoolean(false)
                || !policy.path("verifyProbeAfterAttach").asBoolean(false)) {
            throw new ArtifactOperationException("portable_dynamic_attach_runtime_invalid",
                    "Dynamic attach export context is invalid");
        }
        JsonNode startups = context.path("startups");
        if (!startups.isArray() || startups.size() != 1
                || !"java".equalsIgnoreCase(startups.path(0).path("command").asText())) {
            throw new ArtifactOperationException("portable_dynamic_attach_startup_not_direct_java",
                    "Dynamic attach export requires a direct Java startup");
        }
    }

    private static JsonNode findContext(JsonNode workspace, JsonNode profile) {
        String requested = profile.path("runtimeContextName").asText("");
        JsonNode fallback = null;
        for (JsonNode context : workspace.path("runtimeContexts")) {
            if (requested.equals(context.path("name").asText())) {
                return context;
            }
            if (fallback == null || context.path("autoStart").asBoolean(false)) {
                fallback = context;
            }
        }
        return fallback == null ? NullNode.getInstance() : fallback;
    }

    private static JsonNode findProbe(Path root, String probeId, ObjectMapper mapper) {
        Path path = root.resolve(".mcpjvm").resolve("probe-config.json");
        try {
            JsonNode probes = mapper.readTree(Files.readString(path));
            JsonNode entry = probes.path("probes").isObject()
                    ? probes.path("probes").path(probeId) : findProbeArray(probes.path("probes"), probeId);
            String baseUrl = entry.path("baseUrl").asText("");
            URI uri = URI.create(baseUrl);
            if (entry.isMissingNode() || uri.getHost() == null || uri.getPort() <= 0) {
                throw new IllegalArgumentException();
            }
            ObjectNode normalized = mapper.createObjectNode();
            normalized.put("host", uri.getHost());
            normalized.put("port", uri.getPort());
            if (entry.has("include")) {
                normalized.set("include", entry.get("include"));
            }
            if (entry.has("exclude")) {
                normalized.set("exclude", entry.get("exclude"));
            }
            return normalized;
        } catch (Exception exception) {
            throw new ArtifactOperationException("probe_registry_unavailable",
                    "Portable Sidecar export requires a readable Probe registry");
        }
    }

    private static JsonNode findProbeArray(JsonNode probes, String probeId) {
        if (!probes.isArray()) {
            return MissingNode.getInstance();
        }
        for (JsonNode probe : probes) {
            if (probeId.equals(probe.path("id").asText())) {
                return probe;
            }
        }
        return MissingNode.getInstance();
    }

    private static String copyArtifact(
            Path export, String environment, Path root, String relativeTarget, String prefix) {
        Path configured = configuredPath(environment);
        Path source = configured != null ? configured : discover(root, relativeTarget, prefix);
        if (source == null || !Files.isRegularFile(source)) {
            throw new ArtifactOperationException("portable_dynamic_attach_artifact_unavailable",
                    "Portable Sidecar artifact is unavailable");
        }
        String relative = "sidecar/" + (environment.contains("ATTACH")
                ? "jvm-attach-helper.jar" : "sidecar-agent.jar");
        try {
            Path target = export.resolve(relative.replace('/', File.separatorChar));
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            return relative;
        } catch (IOException exception) {
            throw new ArtifactOperationException("execution_export_write_failed",
                    "Portable Sidecar artifact could not be copied");
        }
    }

    private static Path configuredPath(String environment) {
        String value = System.getenv(environment);
        return value == null || value.isBlank() ? null : Path.of(value).toAbsolutePath().normalize();
    }

    private static Path discover(Path root, String relativeTarget, String prefix) {
        Path current = root.toAbsolutePath().normalize();
        for (int index = 0; index < 8 && current != null; index++) {
            Path target = current.resolve(relativeTarget);
            try (var entries = Files.list(target)) {
                return entries.filter(Files::isRegularFile).filter(path -> path.getFileName().toString().startsWith(prefix))
                        .filter(path -> !path.getFileName().toString().endsWith("-sources.jar"))
                        .filter(path -> !path.getFileName().toString().endsWith("-javadoc.jar"))
                        .sorted().findFirst().orElse(null);
            } catch (IOException ignored) {
                current = current.getParent();
            }
        }
        return null;
    }

    private static String required(JsonNode node, String field, String reason) {
        String value = node.path(field).asText("").trim();
        if (value.isBlank()) {
            throw new ArtifactOperationException(reason, "Portable Sidecar configuration is incomplete");
        }
        return value;
    }

    private static void write(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new ArtifactOperationException("execution_export_write_failed",
                    "Execution export could not be persisted");
        }
    }

    private static String runner() {
        return String.join("\n", List.of(
                "#!/usr/bin/env node",
                "const fs=require('node:fs');",
                "const path=require('node:path');",
                "const cp=require('node:child_process');",
                "const a=process.argv.slice(2),v={};",
                "for(let i=0;i<a.length;i+=1){if(a[i].startsWith('--'))v[a[i].slice(2)]=a[++i];}",
                "if(!v.config||!fs.existsSync(v.config))process.exit(2);",
                "const c=JSON.parse(fs.readFileSync(v.config,'utf8'));",
                "const action=v.action||'attach';",
                "const args=[action,'--pid',v.pid||'',",
                "  '--agent-jar',path.resolve(path.dirname(v.config),c.agentJarRel),'--confirm','true'];",
                "if(action==='attach')args.push('--agent-args',`host=${c.probeHost};port=${c.probePort}`);",
                "const helper=path.resolve(path.dirname(v.config),c.helperJarRel);",
                "const r=cp.spawnSync('java',['-jar',helper,...args],{stdio:'inherit'});",
                "if(r.error||r.status!==0)process.exit(r.status||1);",
                "if(v.evidence)fs.writeFileSync(v.evidence,",
                "  JSON.stringify({status:'ok',action,probeId:c.probeId},null,2)+'\\n');",
                ""));
    }
}
