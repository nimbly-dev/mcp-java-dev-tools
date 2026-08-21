package com.nimbly.mcpjavadevtools.server.mcp.tools.transportexecute;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.TransportExecutionFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportResult;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TransportExecuteMcpToolTest {

    @Test
    void preservesPublicShapeAndDefaultsWrappedOnlyToTrue() {
        AtomicReference<ExecuteTransportRequest> captured = new AtomicReference<>();
        TransportExecutionFeature feature = request -> {
            captured.set((ExecuteTransportRequest) request);
            return ExecuteTransportResult.httpResponse(
                    "pass", "http", 200, Map.of("Content-Type", "text/plain"), "ok", 1);
        };
        TransportExecuteMcpTool tool = new TransportExecuteMcpTool(feature);

        var response = tool.invokeMcpRequest(new TransportExecuteMcpInput(
                "http", Map.of("method", "GET", "url", "http://127.0.0.1"), null));

        assertThat(captured.get().protocol().value()).isEqualTo("http");
        assertThat(captured.get().wrappedOnly()).isTrue();
        assertThat(response.status()).isEqualTo("pass");
        assertThat(response.details()).containsEntry("protocol", "http");
        assertThat(response.details()).containsEntry("statusCode", 200);
    }

    @Test
    void mapsInvalidProtocolWithoutInvokingCore() {
        AtomicReference<Boolean> invoked = new AtomicReference<>(false);
        TransportExecuteMcpTool tool = new TransportExecuteMcpTool(request -> {
            invoked.set(true);
            return ExecuteTransportResult.httpResponse("pass", "http", 200, Map.of(), null, 1);
        });

        var response = tool.invokeMcpRequest(new TransportExecuteMcpInput(
                "udp", Map.of("method", "GET", "url", "http://127.0.0.1"), null));

        assertThat(invoked.get()).isFalse();
        assertThat(response.status()).isEqualTo("blocked_invalid");
        assertThat(response.reasonCode()).isEqualTo("transport_request_invalid");
    }

    @Test
    void rejectsProtocolCaseAndWhitespaceThatTypeScriptEnumRejects() {
        AtomicReference<Boolean> invoked = new AtomicReference<>(false);
        TransportExecuteMcpTool tool = new TransportExecuteMcpTool(request -> {
            invoked.set(true);
            return ExecuteTransportResult.httpResponse("pass", "http", 200, Map.of(), null, 1);
        });

        for (String protocol : new String[] {"HTTP", " http", "http "}) {
            var response = tool.invokeMcpRequest(new TransportExecuteMcpInput(
                    protocol, Map.of("method", "GET", "url", "http://127.0.0.1"), null));
            assertThat(response.reasonCode()).as(protocol).isEqualTo("transport_request_invalid");
        }
        assertThat(invoked.get()).isFalse();
    }

    @Test
    void rawCallAcceptsTheTopLevelTransportShape() {
        TransportExecuteMcpTool tool = new TransportExecuteMcpTool(request ->
                ExecuteTransportResult.httpResponse("pass", "http", 200, Map.of(), "ok", 1));

        String output = tool.call("""
                {"protocol":"http","request":{"method":"GET","url":"http://127.0.0.1"},
                 "options":{"wrappedOnly":false}}
                """);

        assertThat(output).contains("\"status\":\"pass\"").contains("\"protocol\":\"http\"");
        assertThat(output).doesNotContain("\"action\"");
    }
}
