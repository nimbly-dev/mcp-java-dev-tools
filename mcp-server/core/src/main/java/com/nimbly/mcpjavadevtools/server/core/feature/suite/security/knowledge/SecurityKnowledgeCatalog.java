package com.nimbly.mcpjavadevtools.server.core.feature.suite.security.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/** Resolves packaged reviewed Security knowledge content and immutable provenance snapshots. */
public final class SecurityKnowledgeCatalog {

    private static final String RESOURCE = "security-knowledge-packs.json";
    private static final List<JsonNode> DEFAULT_PACKS = loadPacks();

    /** Resolves a deterministic knowledge snapshot or rejects an explicit unavailable pack. */
    public Selection select(List<String> requestedPacks) {
        List<String> requested = requestedPacks == null || requestedPacks.isEmpty() ? packRefs(DEFAULT_PACKS)
                : requestedPacks.stream().distinct().sorted().toList();
        List<JsonNode> selected = selected(requested);
        List<String> unavailable = requested.stream().filter(id -> !contains(selected, id)).toList();
        if (!unavailable.isEmpty()) {
            return new Selection(null, unavailable);
        }
        return new Selection(new KnowledgeSnapshot(packRefs(selected), digest(selected)), List.of());
    }

    /** Expands selected reviewed rules into bounded external mutation templates. */
    public List<GeneratedCase> generatedCases(Selection selection) {
        if (selection == null || !selection.available()) {
            return List.of();
        }
        List<GeneratedCase> cases = new ArrayList<>();
        for (JsonNode pack : selected(selection.snapshot().packs())) {
            for (JsonNode rule : pack.path("rules")) {
                cases.add(new GeneratedCase(pack.path("ref").asText(), rule.path("id").asText(),
                        rule.path("mutationBoundary").asText(), rule.path("category").asText(),
                        rule.path("severity").asText(), rule.path("title").asText(), rule.path("payload").asText(),
                        textValues(rule.path("requiredFixtureContextKeys")), integerValues(rule.path("expectedDenyStatusCodes")),
                        rule.path("requiredAuthentication").asBoolean(false), rule.path("removeCredential").asBoolean(false)));
            }
        }
        return List.copyOf(cases);
    }

    private static List<JsonNode> loadPacks() {
        try (InputStream stream = SecurityKnowledgeCatalog.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            JsonNode document = new ObjectMapper().readTree(requireResource(stream));
            if (!document.isArray() || document.isEmpty()) {
                throw new IllegalStateException("Security knowledge resource must contain packs");
            }
            List<JsonNode> packs = new ArrayList<>();
            document.forEach(pack -> packs.add(validate(pack)));
            return List.copyOf(packs);
        } catch (IOException exception) {
            throw new IllegalStateException("Security knowledge resource is unreadable", exception);
        }
    }

    private static InputStream requireResource(InputStream stream) {
        if (stream == null) {
            throw new IllegalStateException("Security knowledge resource is missing");
        }
        return stream;
    }

    private static JsonNode validate(JsonNode pack) {
        if (!pack.path("id").isTextual() || !pack.path("ref").isTextual() || !pack.path("provenance").isObject()
                || !pack.path("rules").isArray() || pack.path("rules").isEmpty()) {
            throw new IllegalStateException("Security knowledge resource contains an invalid pack");
        }
        return pack;
    }

    private static List<String> packRefs(List<JsonNode> packs) {
        return packs.stream().map(pack -> pack.path("ref").asText()).sorted().toList();
    }

    private static List<String> textValues(JsonNode values) {
        List<String> output = new ArrayList<>();
        values.forEach(value -> {
            if (value.isTextual() && !value.asText().isBlank()) {
                output.add(value.asText());
            }
        });
        return List.copyOf(output);
    }

    private static List<Integer> integerValues(JsonNode values) {
        List<Integer> output = new ArrayList<>();
        values.forEach(value -> {
            if (value.canConvertToInt()) {
                output.add(value.asInt());
            }
        });
        return List.copyOf(output);
    }

    private static List<JsonNode> selected(List<String> requested) {
        return DEFAULT_PACKS.stream().filter(pack -> requested.stream().anyMatch(request -> matches(pack, request)))
                .sorted(java.util.Comparator.comparing(pack -> pack.path("id").asText())).toList();
    }

    private static boolean contains(List<JsonNode> packs, String id) {
        return packs.stream().anyMatch(pack -> matches(pack, id));
    }

    private static boolean matches(JsonNode pack, String requested) {
        return requested.equals(pack.path("id").asText()) || requested.equals(pack.path("ref").asText());
    }

    private static String digest(List<JsonNode> packs) {
        String content = packs.stream().map(JsonNode::toString).reduce("", (left, right) -> left + "\n" + right);
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                output.append(String.format("%02x", value));
            }
            return output.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    /** Immutable selected-pack identity and content/provenance digest. */
    public record KnowledgeSnapshot(List<String> packs, String digest) {
        /** Defensively copies selected pack names. */
        public KnowledgeSnapshot {
            packs = List.copyOf(packs);
        }
    }

    /** Bounded reviewed Security mutation template selected from a packaged rule. */
    public record GeneratedCase(
            String packRef,
            String ruleId,
            String mutationBoundary,
            String category,
            String severity,
            String title,
            String payloadTemplate,
            List<String> requiredFixtureContextKeys,
            List<Integer> expectedDenyStatusCodes,
            boolean requiredAuthentication,
            boolean removeCredential) {
        /** Defensively copies bounded immutable rule values. */
        public GeneratedCase {
            requiredFixtureContextKeys = List.copyOf(requiredFixtureContextKeys);
            expectedDenyStatusCodes = List.copyOf(expectedDenyStatusCodes);
        }
    }

    /** Result of explicit knowledge-pack selection. */
    public record Selection(KnowledgeSnapshot snapshot, List<String> unavailablePacks) {
        /** Defensively copies unavailable pack names. */
        public Selection {
            unavailablePacks = List.copyOf(unavailablePacks);
        }

        /** Indicates that all explicitly selected packs are available. */
        public boolean available() {
            return unavailablePacks.isEmpty();
        }
    }
}
