package com.nimbly.mcpjavadevtools.poc.core.jvm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class JvmLifecycleResult {

    private final String resultType;
    private final String status;
    private final String reasonCode;
    private final List<JvmDescriptor> jvms;

    private JvmLifecycleResult(String resultType, String status, String reasonCode,
            List<JvmDescriptor> jvms) {
        this.resultType = resultType;
        this.status = status;
        this.reasonCode = reasonCode;
        this.jvms = List.copyOf(jvms);
    }

    public static JvmLifecycleResult ok(List<JvmDescriptor> jvms) {
        return new JvmLifecycleResult("jvm_list", "ok", "ok", jvms);
    }

    public static JvmLifecycleResult blocked(String reasonCode) {
        return new JvmLifecycleResult("report", "blocked", reasonCode, List.of());
    }

    public Map<String, Object> structuredContent() {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("resultType", resultType);
        content.put("status", status);
        content.put("reasonCode", reasonCode);
        content.put("jvms", jvms.stream().map(JvmDescriptor::asMap).toList());
        return content;
    }
}

record JvmDescriptor(
        String pid,
        String identityHint,
        String identitySource,
        String frameworkHint,
        List<String> frameworkEvidence,
        Long processStartEpochMs) {

    Map<String, Object> asMap() {
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("pid", pid);
        descriptor.put("identityHint", identityHint);
        descriptor.put("identitySource", identitySource);
        descriptor.put("frameworkHint", frameworkHint);
        descriptor.put("frameworkEvidence", new ArrayList<>(frameworkEvidence));
        descriptor.put("processStartEpochMs", processStartEpochMs);
        descriptor.put("attachmentState", "unverified");
        descriptor.put("probeState", "unverified");
        return descriptor;
    }
}
