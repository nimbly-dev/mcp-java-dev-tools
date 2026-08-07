import assert from "node:assert/strict";
import test from "node:test";

import { JvmLifecycleRequestSchema } from "@tools-contracts/jvm-lifecycle";

test("[UT][contracts][jvm_lifecycle] requires an explicit numeric PID and confirmation for attachment", () => {
  const accepted = JvmLifecycleRequestSchema.safeParse({
    action: "attach",
    input: {
      pid: "1234",
      expectedProcessStartEpochMs: 1720000000000,
      confirm: true,
      probeHost: "127.0.0.1",
      probePort: 9190,
    },
  });
  assert.equal(accepted.success, true);

  const rejected = JvmLifecycleRequestSchema.safeParse({
    action: "attach",
    input: { pid: "1234", expectedProcessStartEpochMs: 1720000000000, confirm: false },
  });
  assert.equal(rejected.success, false);
});

test("[UT][contracts][jvm_lifecycle] requires process-start fencing for mutation", () => {
  const missingFence = JvmLifecycleRequestSchema.safeParse({
    action: "deactivate",
    input: { pid: "1234", confirm: true },
  });
  assert.equal(missingFence.success, false);

  const accepted = JvmLifecycleRequestSchema.safeParse({
    action: "deactivate",
    input: { pid: "1234", expectedProcessStartEpochMs: 1720000000000, confirm: true },
  });
  assert.equal(accepted.success, true);
});

test("[UT][contracts][jvm_lifecycle] keeps discovery separate from lifecycle mutation", () => {
  const discovery = JvmLifecycleRequestSchema.safeParse({
    action: "list_jvms",
    input: {},
  });
  assert.equal(discovery.success, true);

  const invalidDiscovery = JvmLifecycleRequestSchema.safeParse({
    action: "list_jvms",
    input: { pid: "1234" },
  });
  assert.equal(invalidDiscovery.success, false);
});
