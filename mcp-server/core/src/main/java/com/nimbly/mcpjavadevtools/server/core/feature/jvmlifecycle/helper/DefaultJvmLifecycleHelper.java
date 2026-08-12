package com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.helper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.artifact.JvmLifecycleArtifactKind;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.artifact.JvmLifecycleArtifactResolution;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.artifact.JvmLifecycleArtifactResolver;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.policy.JvmLifecycleExecutionPolicy;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Runs the existing helper with bounded lifetime and output capture.
 */
public final class DefaultJvmLifecycleHelper implements JvmLifecycleHelper {

    private final JvmLifecycleArtifactResolver artifactResolver;
    private final JvmLifecycleExecutionPolicy policy;
    private final JvmLifecycleProcessLauncher processLauncher;
    private final ObjectMapper mapper;

    /** Creates the production helper boundary. */
    public DefaultJvmLifecycleHelper(
            JvmLifecycleArtifactResolver artifactResolver,
            JvmLifecycleExecutionPolicy policy) {
        this(artifactResolver, policy, new DefaultJvmLifecycleProcessLauncher(), new ObjectMapper());
    }

    /** Creates a helper with replaceable process and protocol boundaries. */
    public DefaultJvmLifecycleHelper(
            JvmLifecycleArtifactResolver artifactResolver,
            JvmLifecycleExecutionPolicy policy,
            JvmLifecycleProcessLauncher processLauncher,
            ObjectMapper mapper) {
        this.artifactResolver = artifactResolver;
        this.policy = policy;
        this.processLauncher = processLauncher;
        this.mapper = mapper;
    }

    @Override
    public JvmLifecycleHelperResult execute(JvmLifecycleHelperRequest request) {
        JvmLifecycleArtifactResolution artifact = artifactResolver.resolve(JvmLifecycleArtifactKind.HELPER);
        if (!artifact.isResolved()) {
            return JvmLifecycleHelperResult.failure(request.operation(), artifact.reasonCode());
        }
        try {
            Process process = processLauncher.start(
                    policy.javaBinary(), artifact.path(), request.arguments());
            return normalize(request.operation(), process);
        } catch (IOException | RuntimeException exception) {
            return JvmLifecycleHelperResult.failure(request.operation(), "attach_helper_spawn_failed");
        }
    }

    private JvmLifecycleHelperResult normalize(String operation, Process process) {
        CompletableFuture<CapturedOutput> output = CompletableFuture.supplyAsync(
                () -> capture(process.getInputStream()));
        ProcessExecution execution = await(operation, process, output);
        if (execution.reasonCode() != null) {
            return JvmLifecycleHelperResult.failure(operation, execution.reasonCode());
        }
        if (execution.output().overflow()) {
            return JvmLifecycleHelperResult.failure(operation, "attach_helper_output_invalid");
        }
        Optional<JvmLifecycleHelperResult> parsed = JvmLifecycleHelperProtocol.parse(
                execution.output().text(), mapper);
        if (parsed.isPresent()) {
            return parsed.get();
        }
        String reason = execution.exitCode() == 0
                ? "attach_helper_output_invalid" : "attach_helper_failed";
        return JvmLifecycleHelperResult.failure(operation, reason);
    }

    private ProcessExecution await(
            String operation,
            Process process,
            CompletableFuture<CapturedOutput> output) {
        try {
            boolean finished = waitFor(process, policy.initialTimeout());
            if (!finished && "attach".equals(operation)) {
                finished = waitFor(process, policy.attachReconciliationTimeout());
            }
            if (!finished) {
                destroy(process);
                output.cancel(true);
                return ProcessExecution.failure("attach_helper_timeout");
            }
            CapturedOutput captured = output.get(2, TimeUnit.SECONDS);
            return new ProcessExecution(process.exitValue(), captured, null);
        } catch (InterruptedException exception) {
            destroy(process);
            output.cancel(true);
            Thread.currentThread().interrupt();
            return ProcessExecution.failure("attach_helper_timeout");
        } catch (Exception exception) {
            destroy(process);
            output.cancel(true);
            return ProcessExecution.failure("attach_helper_output_invalid");
        }
    }

    private static boolean waitFor(Process process, java.time.Duration timeout)
            throws InterruptedException {
        return process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static void destroy(Process process) {
        try {
            process.getInputStream().close();
        } catch (IOException ignored) {
            // The process is already being terminated; no further action is possible.
        }
        if (process.isAlive()) {
            process.destroyForcibly();
        }
    }

    private CapturedOutput capture(InputStream stream) {
        StringBuilder output = new StringBuilder();
        boolean overflow = false;
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            char[] buffer = new char[2048];
            int count;
            while ((count = reader.read(buffer)) >= 0) {
                int remaining = policy.maximumOutputCharacters() - output.length();
                if (remaining > 0) {
                    output.append(buffer, 0, Math.min(count, remaining));
                }
                overflow = overflow || count > Math.max(remaining, 0);
            }
        } catch (IOException exception) {
            return new CapturedOutput(output.toString(), true);
        }
        return new CapturedOutput(output.toString(), overflow);
    }

    private record CapturedOutput(String text, boolean overflow) {
    }

    private record ProcessExecution(int exitCode, CapturedOutput output, String reasonCode) {

        private static ProcessExecution failure(String reasonCode) {
            return new ProcessExecution(-1, new CapturedOutput("", false), reasonCode);
        }
    }
}
