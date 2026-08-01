package com.nimbly.mcpjavadevtools.requestmapping.api;

import java.util.List;

public interface TransportMaterializer {
    ResolvedMapping materialize(
            String framework,
            String httpMethod,
            String classPath,
            String methodPath,
            MethodContext context,
            List<ResolvedParameter> parameters
    );
}





