package com.nimbly.mcpjavadevtools.server.configuration;

import com.nimbly.mcpjavadevtools.server.core.operation.CatalogQuery;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bounded Application configuration for public operation catalog page sizes. */
@ConfigurationProperties(prefix = "mcp.operation-catalog")
public class OperationCatalogConfigurationProperties {

    private int defaultLimit = CatalogQuery.DEFAULT_LIMIT;
    private int maximumLimit = CatalogQuery.MAX_LIMIT;

    public int getDefaultLimit() {
        return defaultLimit;
    }

    public void setDefaultLimit(int defaultLimit) {
        this.defaultLimit = defaultLimit;
    }

    public int getMaximumLimit() {
        return maximumLimit;
    }

    public void setMaximumLimit(int maximumLimit) {
        this.maximumLimit = maximumLimit;
    }
}
