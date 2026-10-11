package com.nimbly.mcpjavadevtools.server.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbly.mcpjavadevtools.server.core.operation.CatalogQueryPolicy;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class OperationCatalogConfigurationPropertiesTest {

    @Test
    void bindsApplicationLimitsIntoCorePolicy() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of(
                "mcp.operation-catalog.default-limit", "7",
                "mcp.operation-catalog.maximum-limit", "12")));
        OperationCatalogConfigurationProperties properties = binder.bind(
                "mcp.operation-catalog", Bindable.of(OperationCatalogConfigurationProperties.class)).get();

        CatalogQueryPolicy policy = new OperationDirectoryConfiguration().catalogQueryPolicy(properties);

        assertThat(policy.defaultLimit()).isEqualTo(7);
        assertThat(policy.maximumLimit()).isEqualTo(12);
    }
}
