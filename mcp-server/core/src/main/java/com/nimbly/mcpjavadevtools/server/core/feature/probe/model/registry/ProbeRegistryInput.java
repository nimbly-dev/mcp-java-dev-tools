package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.registry;

/**
 * Raw host-provided Probe registry input before Core registration validation.
 *
 * @param id configured Probe identifier
 * @param baseUrl configured Probe endpoint base URL
 */
public record ProbeRegistryInput(String id, String baseUrl) {
}
