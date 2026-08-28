package com.nimbly.mcpjavadevtools.server.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.lifecycle.ExecutionRuntimeLifecycle;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.JvmLifecycleFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.action.attach.AttachRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.action.deactivate.DeactivateRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.action.listjvms.JvmListResult;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.action.listjvms.ListJvmsRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.candidate.JvmCandidate;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.result.JvmLifecycleResultStatus;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.ProbeFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.check.ProbeCheckRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResultStatus;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.ProbeTargetSelector;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** Application-owned direct-Java dynamic attach lifecycle using the existing Core Features. */
public final class ApplicationExecutionRuntimeLifecycle implements ExecutionRuntimeLifecycle {

    private static final int DISCOVERY_ATTEMPTS = 30;
    private static final long DISCOVERY_DELAY_MILLIS = 200;
    private static final long CLEANUP_TIMEOUT_MILLIS = 2_000;
    private final JvmLifecycleFeature jvms;
    private final ProbeFeature probe;
    private final ObjectMapper mapper;

    /** Creates the Application lifecycle adapter from existing public Core Feature boundaries. */
    public ApplicationExecutionRuntimeLifecycle(JvmLifecycleFeature jvms, ProbeFeature probe, ObjectMapper mapper) {
        this.jvms = jvms;
        this.probe = probe;
        this.mapper = mapper;
    }

    @Override
    public RuntimeLifecycleResult prepare(RuntimeLifecycleRequest request) {
        Selection selection = selection(request);
        if (selection == null) {
            if (dynamicConfigured(request)) {
                return RuntimeLifecycleResult.blocked("sidecar_lifecycle_invalid", Map.of("status", "blocked"));
            }
            return RuntimeLifecycleResult.notRequired();
        }
        if (!request.persistedEvidence().isEmpty()) {
            return resume(request, selection);
        }
        return startAndAttach(request, selection);
    }

    @Override
    public RuntimeLifecycleResult cleanup(RuntimeLifecycleRequest request) {
        if (request.persistedEvidence().isEmpty()) {
            return RuntimeLifecycleResult.notRequired();
        }
        return deactivateAndStop(request.persistedEvidence());
    }

    private RuntimeLifecycleResult resume(RuntimeLifecycleRequest request, Selection selection) {
        String pid = text(request.persistedEvidence().get("pid"));
        Long started = number(request.persistedEvidence().get("processStartEpochMs"));
        if (pid == null || started == null || !matches(pid, started)) {
            return RuntimeLifecycleResult.blocked("owned_runtime_missing", request.persistedEvidence());
        }
        Map<String, Object> evidence = new LinkedHashMap<>(request.persistedEvidence());
        evidence.put("status", "attached");
        evidence.put("runtimeContextName", selection.contextName());
        return new RuntimeLifecycleResult(true, true, "runtime_lifecycle_resumed", evidence);
    }

    private RuntimeLifecycleResult startAndAttach(RuntimeLifecycleRequest request, Selection selection) {
        Process process = start(selection);
        if (process == null) {
            return RuntimeLifecycleResult.blocked("runtime_start_failed", baseEvidence(request, selection));
        }
        Optional<JvmCandidate> candidate = awaitCandidate(Long.toString(process.pid()));
        if (candidate.isEmpty() || candidate.get().processStartEpochMs() == null) {
            return cleanupAfterPrepare("process_start_fence_unavailable", baseEvidence(request, selection), process);
        }
        Map<String, Object> evidence = attachedEvidence(request, selection, candidate.get());
        var attach = jvms.execute(new AttachRequest(candidate.get().pid(), candidate.get().processStartEpochMs(), true,
                selection.probeHost(), selection.probePort(), selection.include(), selection.exclude()));
        if (attach.status() != JvmLifecycleResultStatus.OK) {
            return cleanupAfterPrepare(attach.reasonCode(), evidence, process);
        }
        if (!probeAvailable(selection.probeId())) {
            return cleanupAfterPrepare("probe_verification_failed", evidence, process);
        }
        evidence.put("status", "attached");
        evidence.put("attachOutcome", "active");
        evidence.put("probeVerification", "ok");
        return new RuntimeLifecycleResult(true, true, "runtime_lifecycle_attached", evidence);
    }

    private RuntimeLifecycleResult cleanupAfterPrepare(String reasonCode, Map<String, Object> evidence, Process process) {
        stop(process);
        evidence.put("status", "cleanup_unverified");
        evidence.put("reasonCode", reasonCode);
        return RuntimeLifecycleResult.blocked(reasonCode, evidence);
    }

    private Process start(Selection selection) {
        if (!"java".equalsIgnoreCase(selection.command()) && !"java.exe".equalsIgnoreCase(selection.command())) {
            return null;
        }
        if (selection.arguments().stream().anyMatch(value -> value.startsWith("-javaagent"))) {
            return null;
        }
        try {
            return new ProcessBuilder(command(selection)).directory(selection.directory().toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException exception) {
            return null;
        }
    }

    private static List<String> command(Selection selection) {
        List<String> command = new java.util.ArrayList<>();
        command.add(selection.command());
        command.addAll(selection.arguments());
        return List.copyOf(command);
    }

    private Optional<JvmCandidate> awaitCandidate(String pid) {
        for (int attempt = 0; attempt < DISCOVERY_ATTEMPTS; attempt++) {
            Optional<JvmCandidate> candidate = candidate(pid);
            if (candidate.isPresent() && candidate.get().processStartEpochMs() != null) {
                return candidate;
            }
            pause();
        }
        return Optional.empty();
    }

    private Optional<JvmCandidate> candidate(String pid) {
        var result = jvms.execute(new ListJvmsRequest());
        if (result.status() != JvmLifecycleResultStatus.OK || result.actionResult().isEmpty()) {
            return Optional.empty();
        }
        if (result.actionResult().get() instanceof JvmListResult listed) {
            return listed.jvms().stream().filter(candidate -> pid.equals(candidate.pid())).findFirst();
        }
        return Optional.empty();
    }

    private boolean probeAvailable(String probeId) {
        return probe.execute(new ProbeCheckRequest(new ProbeTargetSelector(probeId, null), Map.of(), Duration.ofSeconds(5)))
                .status() == ProbeResultStatus.SUCCESS;
    }

    private RuntimeLifecycleResult deactivateAndStop(Map<String, Object> prior) {
        String pid = text(prior.get("pid"));
        Long started = number(prior.get("processStartEpochMs"));
        if (pid == null || started == null || !matches(pid, started)) {
            return RuntimeLifecycleResult.blocked("cleanup_unverified", cleanupEvidence(prior, "unverified"));
        }
        var result = jvms.execute(new DeactivateRequest(pid, started, true));
        if (result.status() != JvmLifecycleResultStatus.OK) {
            return RuntimeLifecycleResult.blocked(result.reasonCode(), cleanupEvidence(prior, "blocked"));
        }
        if (!stop(ProcessHandle.of(Long.parseLong(pid)).orElse(null))) {
            return RuntimeLifecycleResult.blocked("cleanup_unverified", cleanupEvidence(prior, "unverified"));
        }
        return new RuntimeLifecycleResult(true, true, "runtime_lifecycle_cleaned", cleanupEvidence(prior, "ok"));
    }

    private boolean matches(String pid, long started) {
        return candidate(pid).map(value -> started == value.processStartEpochMs()).orElse(false);
    }

    private static void pause() {
        try {
            Thread.sleep(DISCOVERY_DELAY_MILLIS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static void stop(Process process) {
        process.destroy();
        if (process.isAlive()) {
            process.destroyForcibly();
        }
    }

    private static boolean stop(ProcessHandle handle) {
        if (handle == null || !handle.isAlive()) {
            return true;
        }
        handle.destroy();
        try {
            handle.onExit().get(CLEANUP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            return true;
        } catch (Exception exception) {
            handle.destroyForcibly();
            return !handle.isAlive();
        }
    }

    private Map<String, Object> baseEvidence(RuntimeLifecycleRequest request, Selection selection) {
        return Map.of("version", 1, "suiteRunId", request.suiteRunId(), "runtimeContextName", selection.contextName(),
                "startupName", selection.startupName(), "status", "in_progress", "probeId", selection.probeId());
    }

    private Map<String, Object> attachedEvidence(RuntimeLifecycleRequest request, Selection selection, JvmCandidate candidate) {
        Map<String, Object> evidence = new LinkedHashMap<>(baseEvidence(request, selection));
        evidence.put("pid", candidate.pid());
        evidence.put("processStartEpochMs", candidate.processStartEpochMs());
        return evidence;
    }

    private static Map<String, Object> cleanupEvidence(Map<String, Object> prior, String cleanupStatus) {
        Map<String, Object> evidence = new LinkedHashMap<>(prior);
        evidence.put("status", "terminal");
        evidence.put("cleanupStatus", cleanupStatus);
        return evidence;
    }

    private Selection selection(RuntimeLifecycleRequest request) {
        JsonNode context = context(request.workspace(), request.profile());
        JsonNode policy = context.path("sidecarLifecycle");
        if (!"dynamic_attach_local".equals(policy.path("activation").asText())) {
            return null;
        }
        return selection(request.workspace(), context, policy);
    }

    private static boolean dynamicConfigured(RuntimeLifecycleRequest request) {
        return "dynamic_attach_local".equals(context(request.workspace(), request.profile())
                .path("sidecarLifecycle").path("activation").asText());
    }

    private Selection selection(JsonNode workspace, JsonNode context, JsonNode policy) {
        JsonNode startup = startup(context, policy.path("targetStartupName").asText());
        Path root = Path.of(workspace.path("projectRoot").asText()).toAbsolutePath().normalize();
        ProbeEndpoint endpoint = probeEndpoint(root, policy.path("probeId").asText());
        if (!valid(context, startup, policy, root, endpoint)) {
            return null;
        }
        Path directory = directory(root, startup.path("appdir").asText());
        return new Selection(context.path("name").asText(), startup.path("name").asText(), startup.path("command").asText(),
                mapper.convertValue(startup.path("args"), mapper.getTypeFactory().constructCollectionType(List.class, String.class)),
                directory, policy.path("probeId").asText(), endpoint.host(), endpoint.port(), endpoint.include(), endpoint.exclude());
    }

    private static JsonNode context(JsonNode workspace, JsonNode profile) {
        String requested = profile.path("runtimeContextName").asText();
        for (JsonNode value : workspace.path("runtimeContexts")) {
            if (requested.equals(value.path("name").asText()) || requested.isBlank() && value.path("autoStart").asBoolean()) {
                return value;
            }
        }
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    private static JsonNode startup(JsonNode context, String name) {
        for (JsonNode value : context.path("startups")) {
            if (name.equals(value.path("name").asText())) {
                return value;
            }
        }
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    private ProbeEndpoint probeEndpoint(Path root, String probeId) {
        try {
            JsonNode config = mapper.readTree(Files.readString(root.resolve(".mcpjvm/probe-config.json")));
            JsonNode probe = config.path("probes").path(probeId);
            URI uri = URI.create(probe.path("baseUrl").asText());
            return new ProbeEndpoint(uri.getHost(), uri.getPort(), probe.path("include").asText(null), probe.path("exclude").asText(null));
        } catch (Exception exception) {
            return null;
        }
    }

    private static boolean valid(JsonNode context, JsonNode startup, JsonNode policy, Path root, ProbeEndpoint endpoint) {
        return "terminal".equals(context.path("mode").asText()) && context.path("autoStart").asBoolean()
                && context.path("autoStopOnFinish").asBoolean() && context.path("startups").size() == 1
                && startup != null && !startup.isMissingNode() && policy.path("verifyProbeAfterAttach").asBoolean()
                && endpoint != null && endpoint.host() != null && endpoint.port() > 0 && directory(root, startup.path("appdir").asText()) != null;
    }

    private static Path directory(Path root, String appdir) {
        Path directory = appdir == null || appdir.isBlank() ? root : root.resolve(appdir).normalize();
        return directory.startsWith(root) && Files.isDirectory(directory) ? directory : null;
    }

    private static String text(Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private static Long number(Object value) {
        return value instanceof Number number && number.longValue() > 0 ? number.longValue() : null;
    }

    private record ProbeEndpoint(String host, int port, String include, String exclude) { }

    private record Selection(String contextName, String startupName, String command, List<String> arguments, Path directory,
            String probeId, String probeHost, int probePort, String include, String exclude) { }
}
