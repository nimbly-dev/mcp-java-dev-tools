package com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.workload.jmeter;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JmeterJtlCollectorTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void collectsSuccessfulAndFailedRequestMetrics() throws Exception {
        Path jtl = temporaryDirectory.resolve("workload.jtl");
        Files.writeString(jtl, "elapsed,success\n20,true\n0,false\n");

        JmeterJtlCollector.Metrics metrics = new JmeterJtlCollector().collect(jtl);

        assertThat(metrics.totalRequests()).isEqualTo(2);
        assertThat(metrics.failedRequests()).isEqualTo(1);
        assertThat(metrics.latenciesMs()).containsExactly(20L, 1L);
    }
}
