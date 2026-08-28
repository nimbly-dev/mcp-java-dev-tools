package com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.lease;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileExecutionRunLeaseTest {

    @TempDir
    java.nio.file.Path workspace;

    @Test
    void preventsAnotherProcessLeaseUntilTheOwnerReleasesIt() throws Exception {
        var first = new FileExecutionRunLease(workspace);
        var second = new FileExecutionRunLease(workspace);

        assertThat(first.acquire("demo", "run-1")).isTrue();
        assertThat(second.acquire("demo", "run-1")).isFalse();
        assertThat(Files.exists(workspace.resolve(".mcpjvm/demo/suite-runs/run-1/execution.lock"))).isTrue();

        first.release("demo", "run-1");

        assertThat(second.acquire("demo", "run-1")).isTrue();
    }

    @Test
    void recoversALeaseOwnedByADefunctProcess() throws Exception {
        var lease = new FileExecutionRunLease(workspace);
        var lock = workspace.resolve(".mcpjvm/demo/suite-runs/crashed/execution.lock");
        Files.createDirectories(lock.getParent());
        Files.writeString(lock, "999999999");

        assertThat(lease.acquire("demo", "crashed")).isTrue();
    }
}
