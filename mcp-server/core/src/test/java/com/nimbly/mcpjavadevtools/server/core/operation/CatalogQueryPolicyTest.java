package com.nimbly.mcpjavadevtools.server.core.operation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.nimbly.mcpjavadevtools.server.core.operation.execution.OperationDirectoryException;
import org.junit.jupiter.api.Test;

class CatalogQueryPolicyTest {

    @Test
    void appliesConfiguredDefaultAndMaximumLimits() {
        CatalogQueryPolicy policy = new CatalogQueryPolicy(7, 12);

        assertThat(policy.query(null, null, null, null, null).limit()).isEqualTo(7);
        assertThat(policy.query(null, null, null, 12, null).limit()).isEqualTo(12);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> policy.query(null, null, null, 13, null))
                .isInstanceOf(OperationDirectoryException.class)
                .withMessage("The catalog limit must be between 1 and 12.");
    }

    @Test
    void rejectsConfigurationOutsideCoreBounds() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CatalogQueryPolicy(0, 10))
                .withMessageContaining("default limit");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CatalogQueryPolicy(11, 10))
                .withMessageContaining("maximum limit");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CatalogQueryPolicy(10, CatalogQuery.MAX_LIMIT + 1))
                .withMessageContaining("maximum limit");
    }
}
