package com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol;

import java.util.Optional;

/** Recognized transport protocol discriminators. */
public enum TransportProtocol {

    HTTP("http"),
    GRPC("grpc"),
    KAFKA("kafka"),
    CUSTOM("custom");

    private final String value;

    TransportProtocol(String value) {
        this.value = value;
    }

    /** @return stable public protocol value */
    public String value() {
        return value;
    }

    /** @return matching protocol for the exact lowercase public value */
    public static Optional<TransportProtocol> fromValue(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (TransportProtocol protocol : values()) {
            if (protocol.value.equals(value)) {
                return Optional.of(protocol);
            }
        }
        return Optional.empty();
    }
}
