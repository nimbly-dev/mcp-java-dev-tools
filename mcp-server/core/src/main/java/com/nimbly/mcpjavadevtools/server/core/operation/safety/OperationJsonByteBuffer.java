package com.nimbly.mcpjavadevtools.server.core.operation.safety;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

/** Dynamically stores encoded JSON without growing beyond a configured byte ceiling. */
public class OperationJsonByteBuffer extends OutputStream {

    private static final int INITIAL_CAPACITY = 1_024;

    private final int maximum;
    private byte[] bytes = new byte[0];
    private int count;
    private boolean exceeded;

    public OperationJsonByteBuffer(int maximum) {
        if (maximum < 1) {
            throw new IllegalArgumentException("maximum JSON bytes must be positive");
        }
        this.maximum = maximum;
    }

    @Override
    public void write(int value) throws IOException {
        ensureCapacity(1);
        bytes[count++] = (byte) value;
    }

    @Override
    public void write(byte[] source, int offset, int length) throws IOException {
        if (source == null) {
            throw new NullPointerException("JSON bytes must not be null");
        }
        if (offset < 0 || length < 0 || offset > source.length - length) {
            throw new IndexOutOfBoundsException("JSON byte range is invalid");
        }
        ensureCapacity(length);
        System.arraycopy(source, offset, bytes, count, length);
        count += length;
    }

    public byte[] bytes() {
        return Arrays.copyOf(bytes, count);
    }

    public boolean exceeded() {
        return exceeded;
    }

    private void ensureCapacity(int additional) throws IOException {
        if (additional > maximum - count) {
            exceeded = true;
            throw new IOException("operation JSON byte ceiling exceeded");
        }
        int required = count + additional;
        if (required <= bytes.length) {
            return;
        }
        int capacity = bytes.length == 0 ? Math.min(INITIAL_CAPACITY, maximum) : bytes.length;
        while (capacity < required) {
            capacity = Math.min(maximum, Math.max(required, capacity * 2));
        }
        bytes = Arrays.copyOf(bytes, capacity);
    }
}
