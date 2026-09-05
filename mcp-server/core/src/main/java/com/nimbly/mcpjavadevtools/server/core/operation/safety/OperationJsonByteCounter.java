package com.nimbly.mcpjavadevtools.server.core.operation.safety;

import java.io.IOException;
import java.io.OutputStream;

/** Counts encoded JSON bytes and stops serialization at a configured ceiling. */
public class OperationJsonByteCounter extends OutputStream {

    private final int maximum;
    private int count;
    private boolean exceeded;

    public OperationJsonByteCounter(int maximum) {
        if (maximum < 1) {
            throw new IllegalArgumentException("maximum JSON bytes must be positive");
        }
        this.maximum = maximum;
    }

    @Override
    public void write(int value) throws IOException {
        writeLength(1);
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
        if (bytes == null) {
            throw new NullPointerException("bytes must not be null");
        }
        if (offset < 0 || length < 0 || offset > bytes.length - length) {
            throw new IndexOutOfBoundsException("JSON byte range is invalid");
        }
        writeLength(length);
    }

    public int count() {
        return count;
    }

    public boolean exceeded() {
        return exceeded;
    }

    private void writeLength(int length) throws IOException {
        if (length > maximum - count) {
            exceeded = true;
            throw new IOException("operation JSON byte ceiling exceeded");
        }
        count += length;
    }
}
