package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactManagementAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactType;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Verifies the closed Artifact family/action contract. */
class ArtifactManagementActionTest {

    @Test
    void publicAllowlistContainsEveryApprovedFamilyActionPair() {
        assertThat(ArtifactManagementAction.values()).hasSize(30);
        assertThat(ArtifactManagementAction.allowedActions(ArtifactType.PROBE_CONFIG))
                .containsExactly("read", "validate", "upsert", "reload");
        assertThat(ArtifactManagementAction.allowedActions(ArtifactType.RUN_RESULT))
                .containsExactly("read", "list", "rebuild", "backfill", "cutover", "query", "cleanup");
    }

    @Test
    void unsupportedFamilyActionPairDoesNotResolve() {
        assertThat(ArtifactManagementAction.resolve(ArtifactType.PROBE_CONFIG, ArtifactAction.CLEANUP))
                .isEmpty();
        assertThat(Arrays.stream(ArtifactManagementAction.values())
                .map(ArtifactManagementAction::action)
                .distinct())
                .containsExactlyInAnyOrder(ArtifactAction.values());
    }
}
