package com.nimbly.mcpjavadevtools.requestmapping.transport;

import com.nimbly.mcpjavadevtools.requestmapping.ast.MethodContext;
import com.nimbly.mcpjavadevtools.requestmapping.ast.ResolvedParameter;
import com.nimbly.mcpjavadevtools.requestmapping.resolution.ResolvedMapping;
import java.util.List;

/** Binary-compatible materializer contract retained for the pre-api SPI. */
@Deprecated(forRemoval = false)
public interface TransportMaterializer {
    ResolvedMapping materialize(
            String framework, String httpMethod, String classPath, String methodPath,
            MethodContext context,
            List<ResolvedParameter> parameters);
}
