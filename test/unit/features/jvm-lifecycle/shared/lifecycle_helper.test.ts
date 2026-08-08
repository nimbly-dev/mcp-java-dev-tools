const assert = require("node:assert/strict");
const { EventEmitter } = require("node:events");
const { PassThrough } = require("node:stream");
const test = require("node:test");

const {
  runLifecycleHelper,
} = require("../../../../../tools/features/jvm-lifecycle/shared/lifecycle_helper");

function delayedAttachChild(): { child: object; wasKilled: () => boolean } {
  const events = new EventEmitter();
  const stdout = new PassThrough();
  let killed = false;
  const child = Object.assign(events, {
    stdout,
    kill: () => {
      killed = true;
      return true;
    },
  });
  setTimeout(() => {
    stdout.end(
      JSON.stringify({
        operation: "attach",
        outcome: "active",
        reasonCode: "active",
        pids: [],
        candidates: [],
        nonRestorableClasses: [],
      }),
    );
    events.emit("close", 0);
  }, 75);
  return { child, wasKilled: () => killed };
}

test("[UT][jvm-lifecycle][helper] attach accepts a structured result during its bounded reconciliation window", async () => {
  const fixture = delayedAttachChild();
  const result = await runLifecycleHelper(
    { javaBin: "java", helperJarAbs: "ignored.jar" },
    ["attach"],
    {
      initialTimeoutMs: 25,
      attachReconciliationTimeoutMs: 250,
      spawnHelper: () => fixture.child,
    },
  );

  assert.deepEqual(result, {
    operation: "attach",
    outcome: "active",
    reasonCode: "active",
    pids: [],
    candidates: [],
    nonRestorableClasses: [],
  });
  assert.equal(fixture.wasKilled(), false);
});

test("[UT][jvm-lifecycle][helper] non-attach operations retain the initial hard timeout", async () => {
  const fixture = delayedAttachChild();
  const result = await runLifecycleHelper(
    { javaBin: "java", helperJarAbs: "ignored.jar" },
    ["discover"],
    {
      initialTimeoutMs: 25,
      attachReconciliationTimeoutMs: 250,
      spawnHelper: () => fixture.child,
    },
  );

  assert.deepEqual(result, { reasonCode: "attach_helper_timeout" });
  assert.equal(fixture.wasKilled(), true);
});
