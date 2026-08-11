package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.key;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import org.junit.jupiter.api.Test;

class StrictLineKeyTest {

    @Test
    void normalizesAndParsesAValidStrictLineKey() {
        assertThat(StrictLineKey.parse("  com.example.PostController#updatePost:122  "))
                .hasValue(new StrictLineKey("com.example.PostController#updatePost:122"));
    }

    @Test
    void resolvesAMethodKeyWithALineHint() {
        ProbeKeySelector selector = new ProbeKeySelector(
                "com.example.PostController#updatePost",
                122);

        assertThat(selector.resolve())
                .hasValue(new StrictLineKey("com.example.PostController#updatePost:122"));
    }

    @Test
    void rejectsInvalidKeysWithoutThrowing() {
        assertThat(StrictLineKey.parse("com.example.PostController#updatePost")).isEmpty();
        assertThat(StrictLineKey.parse("com.example.PostController#updatePost:0")).isEmpty();
        assertThat(StrictLineKey.resolve("com.example.PostController#updatePost", null)).isEmpty();
        assertThat(StrictLineKey.resolve("com.example.PostController#updatePost", 0)).isEmpty();
        assertThatIllegalArgumentException().isThrownBy(() -> new StrictLineKey("invalid-key"));
    }

    @Test
    void rejectsMalformedClassAndMethodSegments() {
        assertThat(StrictLineKey.parse("http://host#method:1")).isEmpty();
        assertThat(StrictLineKey.parse("not/a/Class#method#extra:1")).isEmpty();
        assertThat(StrictLineKey.parse("com.example.1Invalid#method:1")).isEmpty();
        assertThat(StrictLineKey.parse("com.example.Valid#invalid-method:1")).isEmpty();
    }

    @Test
    void batchSelectorFailsClosedWhenAnyKeyIsInvalid() {
        ProbeKeyBatchSelector selector = new ProbeKeyBatchSelector(List.of(
                "com.example.PostController#updatePost:122",
                "com.example.CommentController#createComment"));

        assertThat(selector.resolveAll()).isEmpty();
    }
}
