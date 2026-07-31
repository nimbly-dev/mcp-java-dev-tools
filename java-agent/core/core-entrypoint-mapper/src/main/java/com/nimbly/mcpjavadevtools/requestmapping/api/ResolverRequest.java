package com.nimbly.mcpjavadevtools.requestmapping.api;

import java.util.List;

public final class ResolverRequest {
    public String action;
    public String projectRootAbs;
    public List<String> searchRootsAbs;
    public String classHint;
    public String methodHint;
    public Integer lineHint;
    public String inferredTargetFileAbs;
}


