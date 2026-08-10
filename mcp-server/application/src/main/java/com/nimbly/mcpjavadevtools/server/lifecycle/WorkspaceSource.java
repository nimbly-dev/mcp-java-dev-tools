package com.nimbly.mcpjavadevtools.server.lifecycle;

public enum WorkspaceSource {
    ROOTS("roots"),
    ARG("arg"),
    ENV("env"),
    SESSION("session"),
    CWD("cwd"),
    MISSING("missing"),
    AMBIGUOUS("ambiguous");

    private final String wireValue;

    WorkspaceSource(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
