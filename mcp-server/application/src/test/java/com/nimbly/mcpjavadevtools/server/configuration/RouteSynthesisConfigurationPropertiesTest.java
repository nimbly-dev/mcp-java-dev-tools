package com.nimbly.mcpjavadevtools.server.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RouteSynthesisConfigurationPropertiesTest {

    @Test
    void countsConfiguredExternalModulesWithoutExposingTheirValues() {
        RouteSynthesisConfigurationProperties properties = new RouteSynthesisConfigurationProperties();
        properties.setExternalModules("first-module; second-module\nthird-module");

        assertThat(properties.configuredExternalModuleCount()).isEqualTo(3);
    }

    @Test
    void treatsBlankExternalModuleConfigurationAsUnconfigured() {
        RouteSynthesisConfigurationProperties properties = new RouteSynthesisConfigurationProperties();
        properties.setExternalModules(" ; \n");

        assertThat(properties.configuredExternalModuleCount()).isZero();
    }
}
