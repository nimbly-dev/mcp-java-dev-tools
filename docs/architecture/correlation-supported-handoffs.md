# Correlation supported asynchronous handoffs

Correlation context propagation is framework- and transport-neutral. The current
supported handoff contract is deliberately narrow:

- `java.util.concurrent.AbstractExecutorService.submit(...)`
- `java.util.concurrent.ThreadPoolExecutor.execute(...)`
- `java.util.concurrent.ScheduledThreadPoolExecutor.schedule(...)`,
  `scheduleAtFixedRate(...)`, and `scheduleWithFixedDelay(...)`
- `java.util.concurrent.ForkJoinPool.execute(Runnable)`

These boundaries cover standard JDK `ExecutorService` submissions and explicit
`CompletableFuture` executor paths that delegate to a supported JDK executor.
Only a correlation context already bound at a supported event-consumer entry
point is captured. The wrapper restores the prior worker context and clears or
restores it when the task completes.

Custom `Executor` implementations, custom `ExecutorService` implementations,
unlisted scheduling mechanisms, and other async libraries are outside this
contract. Their work remains untagged and required downstream runtime evidence
fails closed with `correlation_context_not_propagated`.

When a correlation policy requires runtime evidence and includes one or more
Strict Line Keys, absence of a matching runtime Line Hit is treated as a
propagation failure. This uses only the runtime `runtime_line_hit` stream; it
does not require synthetic consumer events.
