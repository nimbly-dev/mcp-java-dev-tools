package com.nimbly.mcpjavadevtools.requestmapping.api;

import java.util.List;

public final class HandlerInventoryResponse extends ResolverResponse {
    public String framework;
    public String controllerFqcn;
    public String matchedTypeFile;
    public String matchedRootAbs;
    public List<HandlerInventoryEntry> handlers;
    public List<String> evidence;
    public List<String> attemptedStrategies;
}
