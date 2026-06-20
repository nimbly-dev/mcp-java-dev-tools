package com.nimbly.mcpjavadevtools.agent.profiler;

import com.nimbly.mcpjavadevtools.agent.profiler.model.ProfilerStartRequest;
import com.nimbly.mcpjavadevtools.agent.profiler.model.ProfilerStateSnapshot;
import com.nimbly.mcpjavadevtools.agent.profiler.model.ProfilerStopRequest;
import com.nimbly.mcpjavadevtools.agent.profiler.model.ProfilerStopResult;

public interface ProbeProfiler {
  ProfilerStateSnapshot state();

  ProfilerStateSnapshot start(ProfilerStartRequest request);

  ProfilerStopResult stop(ProfilerStopRequest request);

  ProfilerStateSnapshot reset();
}
