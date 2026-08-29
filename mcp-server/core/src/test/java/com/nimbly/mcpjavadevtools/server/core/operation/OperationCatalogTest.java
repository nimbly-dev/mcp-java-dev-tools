package com.nimbly.mcpjavadevtools.server.core.operation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OperationCatalogTest {

    private static final OperationTraceMetadata TRACE = new OperationTraceMetadata(
            "Adapter",
            "RequestMapper",
            "Feature",
            "ResponseMapper",
            "OperationCatalogTest",
            "none",
            Map.of("collaborator", "Collaborator"));

    @Test
    void executesAndDescribesTheCompleteClosedCatalog() {
        OperationCatalog<TestAction, String, String> catalog = completeCatalog();

        assertThat(catalog.execute(TestAction.FIRST, "input")).isEqualTo("FIRST:input");
        assertThat(catalog.describe(TestAction.SECOND).action()).isEqualTo("second");
        assertThat(catalog.catalog()).extracting(OperationDescriptor::action)
                .containsExactly("first", "second");
        assertThat(catalog.traceInventory()).extracting(OperationTraceEntry::action)
                .containsExactly("first", "second");
        assertThat(catalog.traceInventory()).extracting(OperationTraceEntry::toolName)
                .containsExactly("tool", "tool");
        assertThat(catalog.traceInventory()).extracting(OperationTraceEntry::mcpAdapter)
                .containsExactly("Adapter", "Adapter");
        assertThat(catalog.traceInventory()).extracting(OperationTraceEntry::operationCatalog)
                .containsExactly(OperationCatalogTest.class.getName(), OperationCatalogTest.class.getName());
    }

    @Test
    void rejectsMissingOperationOwnership() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new OperationCatalog<>(
                        TestAction.class,
                        exposure("tool", "first", "second"),
                        OperationCatalogTest.class,
                        List.of(new TestOperation(TestAction.FIRST, "tool", "first"))))
                .withMessage("missing operation: SECOND");
    }

    @Test
    void rejectsDuplicateOperationOwnership() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new OperationCatalog<>(
                        TestAction.class,
                        exposure("tool-one", "first", "second", "third"),
                        OperationCatalogTest.class,
                        List.of(
                                new TestOperation(TestAction.FIRST, "tool-one", "first"),
                                new TestOperation(TestAction.FIRST, "tool-two", "second"),
                                new TestOperation(TestAction.SECOND, "tool-three", "third"))))
                .withMessage("duplicate operation: FIRST");
    }

    @Test
    void rejectsAmbiguousToolActionOwnership() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new OperationCatalog<>(
                        TestAction.class,
                        exposure("tool", "same"),
                        OperationCatalogTest.class,
                        List.of(
                                new TestOperation(TestAction.FIRST, "tool", "same"),
                                new TestOperation(TestAction.SECOND, "tool", "same"))))
                .withMessage("ambiguous operation ownership: tool/same");
    }

    @Test
    void rejectsDescriptorOwnershipThatDoesNotNameTheConcreteExecutor() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new OperationCatalog<>(
                        TestAction.class,
                        exposure("tool", "first", "second"),
                        OperationCatalogTest.class,
                        List.of(
                                new TestOperation(TestAction.FIRST, "tool", "first", String.class.getName()),
                                new TestOperation(TestAction.SECOND, "tool", "second"))))
                .withMessage("descriptor executable owner does not match operation: FIRST");
    }

    @Test
    void rejectsMissingDescriptorOwnershipBeforeBuildCanSucceed() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new OperationCatalog<>(
                        TestAction.class,
                        exposure("tool", "first", "second"),
                        OperationCatalogTest.class,
                        List.of(
                                new MissingDescriptorOperation(TestAction.FIRST),
                                new TestOperation(TestAction.SECOND, "tool", "second"))))
                .withMessage("operation descriptor must not be null");
    }

    @Test
    void rejectsCatalogDescriptorsThatAreNotAdvertisedByTheMcpExposure() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new OperationCatalog<>(
                        TestAction.class,
                        exposure("other-tool", "first", "second"),
                        OperationCatalogTest.class,
                        List.of(
                                new TestOperation(TestAction.FIRST, "tool", "first"),
                                new TestOperation(TestAction.SECOND, "tool", "second"))))
                .withMessage("descriptor Tool does not match MCP exposure: FIRST");
    }

    @Test
    void rejectsUnsupportedExecutionAndDescription() {
        OperationCatalog<TestAction, String, String> catalog = completeCatalog();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> catalog.describe(null))
                .withMessage("unsupported operation: null");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> catalog.execute(null, "input"))
                .withMessage("unsupported operation: null");
    }

    private OperationCatalog<TestAction, String, String> completeCatalog() {
        return new OperationCatalog<>(
                TestAction.class,
                exposure("tool", "first", "second"),
                OperationCatalogTest.class,
                List.of(
                        new TestOperation(TestAction.FIRST, "tool", "first"),
                        new TestOperation(TestAction.SECOND, "tool", "second")));
    }

    private OperationExposure exposure(String toolName, String... actions) {
        return new OperationExposure(toolName, "Adapter", List.of(actions));
    }

    private enum TestAction {
        FIRST,
        SECOND
    }

    public static class TestOperation implements Operation<TestAction, String, String> {

        private final TestAction action;
        private final String executableOwner;
        private final OperationDescriptor descriptor;

        private TestOperation(TestAction action, String toolName, String operationAction) {
            this(action, toolName, operationAction, TestOperation.class.getName(),
                    TestOperation.class.getName());
        }

        private TestOperation(
                TestAction action, String toolName, String operationAction, String executableOwner) {
            this(action, toolName, operationAction, TestOperation.class.getName(), executableOwner);
        }

        private TestOperation(
                TestAction action,
                String toolName,
                String operationAction,
                String actualOwner,
                String descriptorOwner) {
            this.action = action;
            this.executableOwner = actualOwner;
            descriptor = new OperationDescriptor(
                    toolName,
                    operationAction,
                    String.class.getName(),
                    String.class.getName(),
                    descriptorOwner,
                    TRACE);
        }

        @Override
        public TestAction operationId() {
            return action;
        }

        @Override
        public OperationDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public String executableOwner() {
            return executableOwner;
        }

        @Override
        public String execute(String input) {
            return action.name() + ":" + input;
        }
    }

    public static class MissingDescriptorOperation implements Operation<TestAction, String, String> {

        private final TestAction action;

        private MissingDescriptorOperation(TestAction action) {
            this.action = action;
        }

        @Override
        public TestAction operationId() {
            return action;
        }

        @Override
        public OperationDescriptor descriptor() {
            return null;
        }

        @Override
        public String executableOwner() {
            return getClass().getName();
        }

        @Override
        public String execute(String input) {
            return input;
        }
    }
}
