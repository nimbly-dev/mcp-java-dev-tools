package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.request;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.ProbeAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.actuate.ProbeActuateCommand;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.actuate.ProbeActuateRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.capture.ProbeCaptureRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.check.ProbeCheckRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.profiler.ProbeProfilerCommand;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.profiler.ProbeProfilerProvider;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.profiler.ProbeProfilerRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.reset.ProbeBatchResetRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.reset.ProbeClassResetRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.reset.ProbeSingleResetRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.status.ProbeBatchStatusRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.status.ProbeSingleStatusRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.waitforhit.ProbeWaitForHitRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.key.ProbeKeyBatchSelector;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.key.ProbeKeySelector;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.ProbeTargetSelector;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/**
 * Constructs and validates typed Probe requests for every host boundary.
 */
public final class ProbeRequestFactory {

    /**
     * Creates the typed request for one already-parsed public action.
     *
     * @param action selected Probe action
     * @param input host-neutral scalar input
     * @return typed Core request
     */
    public ProbeRequest create(ProbeAction action, ProbeRequestInput input) {
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(input, "input must not be null");
        ProbeTargetSelector target = target(input);
        Duration timeout = timeout(input.timeoutMs(), "timeoutMs");
        return switch (action) {
            case CHECK -> new ProbeCheckRequest(target, input.headers(), timeout);
            case STATUS -> status(target, input, timeout);
            case RESET -> reset(target, input, timeout);
            case WAIT_FOR_HIT -> new ProbeWaitForHitRequest(
                    target,
                    new ProbeKeySelector(input.key(), input.lineHint()),
                    timeout,
                    timeout(input.pollIntervalMs(), "pollIntervalMs"),
                    input.maxRetries());
            case CAPTURE -> new ProbeCaptureRequest(target, input.captureId(), timeout);
            case ACTUATE -> new ProbeActuateRequest(
                    target,
                    actuateCommand(input.action()),
                    input.sessionId(),
                    input.actuatorId(),
                    input.targetKey(),
                    input.returnBoolean(),
                    input.ttlMs(),
                    timeout);
            case PROFILER -> new ProbeProfilerRequest(
                    target,
                    profilerCommand(input.action()),
                    input.sessionId(),
                    profilerProvider(input.provider()),
                    input.event(),
                    input.intervalNanos(),
                    input.outputPath(),
                    input.outputFormat(),
                    timeout);
        };
    }

    private static ProbeRequest status(
            ProbeTargetSelector target,
            ProbeRequestInput input,
            Duration timeout) {
        if (!input.keys().isEmpty()) {
            return new ProbeBatchStatusRequest(target, new ProbeKeyBatchSelector(input.keys()), timeout);
        }
        return new ProbeSingleStatusRequest(
                target,
                new ProbeKeySelector(input.key(), input.lineHint()),
                timeout);
    }

    private static ProbeRequest reset(
            ProbeTargetSelector target,
            ProbeRequestInput input,
            Duration timeout) {
        boolean hasKey = hasText(input.key());
        boolean hasKeys = !input.keys().isEmpty();
        boolean hasClassName = hasText(input.className());
        int selectorCount = 0;
        if (hasKey) {
            selectorCount++;
        }
        if (hasKeys) {
            selectorCount++;
        }
        if (hasClassName) {
            selectorCount++;
        }
        if (selectorCount != 1) {
            throw new IllegalArgumentException("Probe reset requires exactly one selector");
        }
        if (hasClassName) {
            return new ProbeClassResetRequest(target, input.className(), timeout);
        }
        if (hasKeys) {
            return new ProbeBatchResetRequest(target, new ProbeKeyBatchSelector(input.keys()), timeout);
        }
        return new ProbeSingleResetRequest(
                target,
                new ProbeKeySelector(input.key(), input.lineHint()),
                timeout);
    }

    private static ProbeTargetSelector target(ProbeRequestInput input) {
        return new ProbeTargetSelector(input.probeId(), input.baseUrl());
    }

    private static Duration timeout(Integer milliseconds, String fieldName) {
        if (milliseconds == null) {
            return null;
        }
        if (milliseconds <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return Duration.ofMillis(milliseconds.longValue());
    }

    private static ProbeActuateCommand actuateCommand(String value) {
        return value == null || value.trim().isEmpty()
                ? null
                : ProbeActuateCommand.valueOf(normalizedEnumValue(value));
    }

    private static ProbeProfilerCommand profilerCommand(String value) {
        return value == null || value.trim().isEmpty()
                ? null
                : ProbeProfilerCommand.valueOf(normalizedEnumValue(value));
    }

    private static ProbeProfilerProvider profilerProvider(String value) {
        return value == null || value.trim().isEmpty()
                ? null
                : ProbeProfilerProvider.valueOf(normalizedEnumValue(value));
    }

    private static String normalizedEnumValue(String value) {
        return value.trim().replace('-', '_').toUpperCase(Locale.ROOT);
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
