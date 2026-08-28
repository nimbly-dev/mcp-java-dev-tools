package com.nimbly.mcpjavadevtools.server.core.feature.suite.security.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecurityCredentialRefreshTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void discardsVerboseOutputWithoutBlockingTheRefreshProcess(@TempDir Path workspace) throws Exception {
        var result = new SecurityCredentialRefresh(2_000).refresh(source(workspace,
                "process.stdout.write('x'.repeat(1048576))"), Map.of());

        assertThat(result.successful()).isTrue();
    }

    @Test
    void terminatesATimedOutRefreshProcess(@TempDir Path workspace) throws Exception {
        var result = new SecurityCredentialRefresh(100).refresh(source(workspace,
                "setInterval(() => {}, 1000)"), Map.of());

        assertThat(result.successful()).isFalse();
        assertThat(result.reasonCode()).isEqualTo("security_credential_refresh_failed");
    }

    private com.fasterxml.jackson.databind.JsonNode source(Path workspace, String program) throws Exception {
        return mapper.readTree("""
                {"workspaceRoot":"%s","scripts":[{"name":"refresh","command":"node",
                "args":["-e","%s"]}],"profileScriptRefs":[{"name":"refresh","phase":"prePlan"}]}
                """.formatted(workspace.toString().replace("\\", "\\\\"), program));
    }
}
