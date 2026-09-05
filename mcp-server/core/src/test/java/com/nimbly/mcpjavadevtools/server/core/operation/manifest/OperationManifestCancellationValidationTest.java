package com.nimbly.mcpjavadevtools.server.core.operation.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationId;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.BoundedNonCancellableOperationExecutor;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.BoundedDelegateOperationExecutor;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.ContextAwareOperationExecutor;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationExecutor;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRegistration;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRegistrationContract;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationResultEncoders;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRequestDecoders;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationCancellationState;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationCancellationGuarantee;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationSafetyPolicy;
import com.nimbly.mcpjavadevtools.server.core.operation.schema.OperationSchema;
import com.nimbly.mcpjavadevtools.server.core.operation.trace.OperationTraceMetadata;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Verifies strict aggregate assembly rejects unproven legacy cancellation. */
public class OperationManifestCancellationValidationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void rejectsLegacyCancellationOutsideMigrationCompatibilityMode() {
        OperationSafetyPolicy policy = OperationSafetyPolicy.legacy("none");
        OperationId id = OperationId.of("demo.echo");
        OperationDescriptor descriptor = new OperationDescriptor(
                "demo", "echo", Request.class.getName(), Result.class.getName(),
                OperationManifestCancellationValidationTest.class.getName(),
                new OperationTraceMetadata(
                        "adapter", "mapper", "feature", "response", "test", "none",
                        Map.of("owner", "test")));
        OperationRegistration<Request, Result> registration = new OperationRegistration<>(
                descriptor, Request.class, Result.class,
                new OperationRegistrationContract(
                        OperationSchema.empty(), OperationSchema.empty(), policy),
                OperationRequestDecoders.typed(JSON, Request.class), request -> new Result(),
                OperationResultEncoders.typed(JSON, Result.class));
        OperationDocumentation documentation = new OperationDocumentation(
                "Echo", "Echoes a request.", "demo", List.of(),
                List.of(JSON.createObjectNode()), List.of("demo"),
                List.of(new OperationAlias("demo", "echo")), "1", false, null, policy);
        OperationManifestDocument document = new OperationManifestDocument(
                1, Map.of(id, documentation));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> OperationManifestAssembler.assembleStrict(
                        List.of(registration), document))
                .withMessageContaining("migration compatibility mode");
    }

    @Test
    void rejectsMutatingContextAwareOwnerWithoutCancellationGuarantee() {
        OperationSafetyPolicy policy = new OperationSafetyPolicy(
                "filesystem_write", false, "caller_must_not_supply_credentials", "none", 100, true,
                1_048_576, 4_194_304);
        ContextAwareOperationExecutor<Request, Result> executor =
                (request, context) -> new Result();
        OperationRegistration<Request, Result> registration = registration(policy, executor);
        OperationManifestDocument document = document(policy);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> OperationManifestAssembler.assembleStrict(
                        List.of(registration), document))
                .withMessageContaining("cancellation guarantee");
    }

    @Test
    void rejectsPlainNonCancellableReadOnlyOwner() {
        OperationSafetyPolicy policy = new OperationSafetyPolicy(
                "none", false, "caller_must_not_supply_credentials", "none", 100, false,
                1_048_576, 4_194_304);
        OperationRegistration<Request, Result> registration = registration(
                policy, request -> new Result());

        assertThatIllegalArgumentException()
                .isThrownBy(() -> OperationManifestAssembler.assembleStrict(
                        List.of(registration), document(policy)))
                .withMessageContaining("bounded continuation semantics");
    }

    @Test
    void acceptsExplicitBoundedNonCancellableOwnerInStrictAssembly() {
        OperationSafetyPolicy policy = new OperationSafetyPolicy(
                "none", false, "caller_must_not_supply_credentials", "none", 100, false,
                1_048_576, 4_194_304);
        BoundedNonCancellableOperationExecutor<Request, Result> executor =
                boundedNonCancellable(request -> new Result());
        OperationRegistration<Request, Result> registration = registration(policy, executor);

        OperationManifest manifest = OperationManifestAssembler.assembleStrict(
                List.of(registration), document(policy));

        assertThat(manifest.registration(OperationId.of("demo.echo")).compatibility())
                .containsEntry("cancellationState", OperationCancellationState.NOT_CANCELLABLE.name());
    }

    @Test
    void acceptsContextAwareReadOnlyOwnerInStrictAssembly() {
        OperationSafetyPolicy policy = new OperationSafetyPolicy(
                "none", false, "caller_must_not_supply_credentials", "none", 100, true,
                1_048_576, 4_194_304);
        ContextAwareOperationExecutor<Request, Result> executor =
                (request, context) -> new Result();
        OperationRegistration<Request, Result> registration = registration(policy, executor);

        OperationManifest manifest = OperationManifestAssembler.assembleStrict(
                List.of(registration), document(policy));

        assertThat(manifest.registration(OperationId.of("demo.echo")).compatibility())
                .containsEntry("cancellationState", OperationCancellationState
                        .CONTEXT_AWARE_CANCELLATION.name());
    }

    @Test
    void acceptsBoundedDelegateOwnerForMutatingStrictAssembly() {
        OperationSafetyPolicy policy = new OperationSafetyPolicy(
                "filesystem_write", false, "caller_must_not_supply_credentials", "none", 100, true,
                1_048_576, 4_194_304);
        BoundedDelegateOperationExecutor<Request, Result> executor =
                (request, context) -> new Result();
        OperationRegistration<Request, Result> registration = registration(policy, executor);

        OperationManifest manifest = OperationManifestAssembler.assembleStrict(
                List.of(registration), document(policy));

        assertThat(manifest.registration(OperationId.of("demo.echo")).compatibility())
                .containsEntry("cancellationState", OperationCancellationState
                        .BOUNDED_DELEGATE_CANCELLATION.name());
    }

    @Test
    void rejectsNonCancellableMarkerWithCancellablePolicyInEveryAssemblyMode() {
        OperationSafetyPolicy policy = new OperationSafetyPolicy(
                "none", false, "caller_must_not_supply_credentials", "none", 100, true,
                1_048_576, 4_194_304);
        BoundedNonCancellableOperationExecutor<Request, Result> executor =
                boundedNonCancellable(request -> new Result());
        OperationRegistration<Request, Result> registration = registration(policy, executor);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> OperationManifestAssembler.assemble(
                        List.of(registration), document(policy)))
                .withMessageContaining("must disable cancellation support");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> OperationManifestAssembler.assembleStrict(
                        List.of(registration), document(policy)))
                .withMessageContaining("must disable cancellation support");
    }

    private OperationRegistration<Request, Result> registration(
            OperationSafetyPolicy policy,
            com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationExecutor<Request, Result>
                    executor) {
        OperationDescriptor descriptor = new OperationDescriptor(
                "demo", "echo", Request.class.getName(), Result.class.getName(),
                OperationManifestCancellationValidationTest.class.getName(),
                new OperationTraceMetadata(
                        "adapter", "mapper", "feature", "response", "test",
                        policy.sideEffect(), Map.of("owner", "test")));
        return new OperationRegistration<>(
                descriptor, Request.class, Result.class,
                new OperationRegistrationContract(
                        OperationSchema.empty(), OperationSchema.empty(), policy),
                OperationRequestDecoders.typed(JSON, Request.class), executor,
                OperationResultEncoders.typed(JSON, Result.class));
    }

    private OperationManifestDocument document(OperationSafetyPolicy policy) {
        return new OperationManifestDocument(1, Map.of(
                OperationId.of("demo.echo"), new OperationDocumentation(
                        "Echo", "Echoes a request.", "demo", List.of(),
                        List.of(JSON.createObjectNode()), List.of("demo"),
                        List.of(new OperationAlias("demo", "echo")), "1", false, null, policy)));
    }

    private BoundedNonCancellableOperationExecutor<Request, Result> boundedNonCancellable(
            OperationExecutor<Request, Result> delegate) {
        return new BoundedNonCancellableOperationExecutor<>() {
            @Override
            public Result execute(Request request) {
                return delegate.execute(request);
            }

            @Override
            public OperationCancellationGuarantee cancellationGuarantee() {
                return OperationCancellationGuarantee.DETERMINISTIC_CONTINUATION;
            }
        };
    }

    private record Request() {
    }

    private record Result() {
    }
}
