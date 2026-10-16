package com.nimbly.mcpjavadevtools.server.core.feature.suite.security.execution;

import static com.nimbly.mcpjavadevtools.server.core.feature.suite.security.execution.SecurityExecutionResults.blocked;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.ProbeFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.SecuritySuiteFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.knowledge.SecurityKnowledgeCatalog;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.model.execution.SecurityPlanValidation;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.model.result.SecuritySuiteResult;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.TransportExecutionFeature;
import java.util.Objects;

/** Coordinates bounded Security preflight and the declared execution mode. */
public class SecurityPlanExecutor implements SecuritySuiteFeature {

    private final SecurityKnowledgeCatalog knowledge;
    private final SecurityBlackboxExecution blackbox;
    private final SecuritySidecarExecution sidecar;

    /** Creates the executor from its public Core collaborators. */
    public SecurityPlanExecutor(TransportExecutionFeature transport, SecurityKnowledgeCatalog knowledge) {
        this(transport, knowledge, null);
    }

    /** Creates the executor with the public Probe Feature required for Sidecar mode. */
    public SecurityPlanExecutor(TransportExecutionFeature transport, SecurityKnowledgeCatalog knowledge, ProbeFeature probe) {
        Objects.requireNonNull(transport, "transport must not be null");
        this.knowledge = Objects.requireNonNull(knowledge, "knowledge must not be null");
        SecurityRequestExecution requests = new SecurityRequestExecution(transport);
        this.blackbox = new SecurityBlackboxExecution(knowledge, requests);
        this.sidecar = new SecuritySidecarExecution(probe, knowledge, requests);
    }

    /** Validates the Security contract and executes its finite declared request matrix. */
    @Override
    public SecuritySuiteResult executePlan(JsonNode input) {
        JsonNode contract = input.path("contract").isObject() ? input.path("contract") : input;
        SecurityPlanValidation validation = SecurityContractPreflight.from(contract);
        if (validation.reasonCode() != null) {
            return blocked(validation.reasonCode(), validation.nextAction());
        }
        SecurityRequestContext credentials = SecurityRequestContext.from(input, contract);
        if (credentials.reasonCode() != null) {
            return blocked(credentials.reasonCode(), "bind each credentialRef to an environment key in project context");
        }
        if (!"blackbox".equals(validation.mode())) {
            SecurityPlanValidation sidecarValidation = SecurityContractPreflight.sidecar(contract);
            if (sidecarValidation.reasonCode() != null) {
                return blocked(sidecarValidation.reasonCode(), sidecarValidation.nextAction());
            }
            return sidecar.runSidecar(contract, validation.baseUrl(), credentials);
        }
        SecurityKnowledgeCatalog.Selection selected = knowledge.select(SecurityContractPreflight.packRefs(contract));
        if (!selected.available()) {
            return blocked("security_knowledge_pack_unavailable", "select only installed Security knowledge packs");
        }
        return blackbox.runBlackbox(contract, validation.baseUrl(), selected, credentials);
    }
}
