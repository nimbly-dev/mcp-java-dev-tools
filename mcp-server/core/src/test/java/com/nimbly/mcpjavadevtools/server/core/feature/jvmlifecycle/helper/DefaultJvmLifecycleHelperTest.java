package com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.helper;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.artifact.JvmLifecycleArtifactResolution;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.policy.JvmLifecycleExecutionPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.policy.ProbeHostPolicy;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class DefaultJvmLifecycleHelperTest {

    @Test
    void boundsDiscoveryAndDeactivationAtFifteenSeconds() {
        FakeProcess process = new FakeProcess("", 0, false, false);
        DefaultJvmLifecycleHelper helper = new DefaultJvmLifecycleHelper(
                kind -> JvmLifecycleArtifactResolution.resolved(Path.of("helper.jar")),
                new JvmLifecycleExecutionPolicy(
                        "java", Duration.ofMillis(5), Duration.ofMillis(5), 65536,
                        new ProbeHostPolicy(Set.of())),
                (java, jar, arguments) -> process,
                new ObjectMapper());

        JvmLifecycleHelperResult result = helper.execute(
                new JvmLifecycleHelperRequest("discover", List.of("discover")));

        assertThat(result.reasonCode()).isEqualTo("attach_helper_timeout");
        assertThat(process.destroyed).isTrue();
    }

    @Test
    void acceptsSuccessfulHelperOutput() {
        FakeProcess process = new FakeProcess(
                "{\"operation\":\"attach\",\"outcome\":\"active\","
                        + "\"reasonCode\":\"active\",\"pids\":[],"
                        + "\"candidates\":[],\"nonRestorableClasses\":[]}",
                0, true, false);

        JvmLifecycleHelperResult result = helper(process).execute(
                new JvmLifecycleHelperRequest("attach", List.of("attach")));

        assertThat(result.operation()).isEqualTo("attach");
        assertThat(result.outcome()).isEqualTo("active");
        assertThat(result.reasonCode()).isEqualTo("active");
    }

    @Test
    void mapsMalformedOutputAndNonZeroExitSeparately() {
        JvmLifecycleHelperResult malformed = helper(new FakeProcess("not-json", 0, true, false))
                .execute(new JvmLifecycleHelperRequest("discover", List.of("discover")));
        JvmLifecycleHelperResult failed = helper(new FakeProcess("not-json", 1, true, false))
                .execute(new JvmLifecycleHelperRequest("discover", List.of("discover")));

        assertThat(malformed.reasonCode()).isEqualTo("attach_helper_output_invalid");
        assertThat(failed.reasonCode()).isEqualTo("attach_helper_failed");
    }

    @Test
    void rejectsOutputAboveTheFrozenCharacterCeiling() {
        String output = "x".repeat(65537);
        JvmLifecycleHelperResult result = helper(new FakeProcess(output, 0, true, false))
                .execute(new JvmLifecycleHelperRequest("discover", List.of("discover")));

        assertThat(result.reasonCode()).isEqualTo("attach_helper_output_invalid");
    }

    @Test
    void givesAttachTheSecondReconciliationWindow() {
        FakeProcess process = new FakeProcess(successfulAttachOutput(), 0, false, true);
        JvmLifecycleHelperResult result = helper(process).execute(
                new JvmLifecycleHelperRequest("attach", List.of("attach")));

        assertThat(result.reasonCode()).isEqualTo("active");
        assertThat(process.waitCalls).isEqualTo(2);
    }

    @Test
    void timesOutAfterBothAttachWindows() {
        FakeProcess process = new FakeProcess("", 0, false, false);
        JvmLifecycleHelperResult result = helper(process).execute(
                new JvmLifecycleHelperRequest("attach", List.of("attach")));

        assertThat(result.reasonCode()).isEqualTo("attach_helper_timeout");
        assertThat(process.waitCalls).isEqualTo(2);
        assertThat(process.destroyed).isTrue();
    }

    private static DefaultJvmLifecycleHelper helper(FakeProcess process) {
        return new DefaultJvmLifecycleHelper(
                kind -> JvmLifecycleArtifactResolution.resolved(Path.of("helper.jar")),
                new JvmLifecycleExecutionPolicy(
                        "java", Duration.ofMillis(5), Duration.ofMillis(5), 65536,
                        new ProbeHostPolicy(Set.of())),
                (java, jar, arguments) -> process,
                new ObjectMapper());
    }

    private static String successfulAttachOutput() {
        return "{\"operation\":\"attach\",\"outcome\":\"active\","
                + "\"reasonCode\":\"active\",\"pids\":[],"
                + "\"candidates\":[],\"nonRestorableClasses\":[]}";
    }

    private static final class FakeProcess extends Process {

        private final InputStream input;
        private final int exitCode;
        private final boolean firstWaitFinished;
        private final boolean secondWaitFinished;
        private int waitCalls;
        private boolean completed;
        private boolean destroyed;

        private FakeProcess(
                String output,
                int exitCode,
                boolean firstWaitFinished,
                boolean secondWaitFinished) {
            input = new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8));
            this.exitCode = exitCode;
            this.firstWaitFinished = firstWaitFinished;
            this.secondWaitFinished = secondWaitFinished;
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return input;
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            completed = true;
            return exitCode;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            waitCalls++;
            boolean finished = waitCalls == 1 ? firstWaitFinished : secondWaitFinished;
            completed = completed || finished;
            return finished;
        }

        @Override
        public int exitValue() {
            return exitCode;
        }

        @Override
        public void destroy() {
            destroyed = true;
        }

        @Override
        public Process destroyForcibly() {
            destroyed = true;
            return this;
        }

        @Override
        public boolean isAlive() {
            return !destroyed && !completed;
        }
    }
}
