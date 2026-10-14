package com.nimbly.mcpjavadevtools.server.core.feature.suite.security.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Input/phase policies are unit tests; actual subprocess effects belong to Application ITs. */
class SecurityCredentialRefreshTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void missingDeclaredPrePlanScriptIsAFailedPrerequisite(@TempDir Path workspace) {
        ObjectNode source = mapper.createObjectNode().put("workspaceRoot", workspace.toString());
        source.putArray("scripts");
        source.putArray("profileScriptRefs").addObject().put("name", "missing").put("phase", "prePlan");
        var result = new SecurityCredentialRefresh().refresh(source, Map.of());
        assertThat(result.successful()).isFalse();
        assertThat(result.reasonCode()).isEqualTo("security_credential_refresh_failed");
    }

    @Test
    void rejectsUnsupportedCommandsAndEscapingRefreshPathsWithoutLaunchingTools(@TempDir Path workspace) {
        List<Map<String, Object>> invalid = List.of(
                Map.of("command", "unsupported"),
                Map.of("command", "node", "appdir", "../outside"),
                Map.of("command", "node", "args", List.of("../outside.js")),
                Map.of("command", "node", "envFileArg", "--env-file"));
        for (Map<String, Object> script : invalid) {
            ObjectNode source = source(workspace, script, "prePlan");
            source.put("envFile", "../outside.env");
            var result = new SecurityCredentialRefresh().refresh(source, Map.of());
            assertThat(result.successful()).isFalse();
            assertThat(result.reasonCode()).isEqualTo("security_credential_refresh_failed");
        }
    }

    @Test
    void leavesOtherLifecyclePhasesToTheirOwnersWithoutLaunchingTools(@TempDir Path workspace) {
        for (String phase : List.of("preRuntime", "postRuntime", "postHealthcheck")) {
            var result = new SecurityCredentialRefresh().refresh(source(workspace,
                    Map.of("command", "unsupported"), phase), Map.of());
            assertThat(result.successful()).isTrue();
            assertThat(result.reasonCode()).isNull();
        }
    }

    private ObjectNode source(Path workspace, Map<String, Object> definition, String phase) {
        ObjectNode source = mapper.createObjectNode().put("workspaceRoot", workspace.toString());
        ObjectNode script = mapper.valueToTree(definition);
        script.put("name", "refresh");
        source.putArray("scripts").add(script);
        source.putArray("profileScriptRefs").addObject().put("name", "refresh").put("phase", phase);
        return source;
    }
}
