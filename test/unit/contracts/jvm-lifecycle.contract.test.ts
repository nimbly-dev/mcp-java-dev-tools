import assert from "node:assert/strict";
import test from "node:test";

import { JvmLifecycleRequestSchema } from "@tools-contracts/jvm-lifecycle";

test("[UT][contracts][jvm_lifecycle] requires an explicit numeric PID and confirmation for attachment", () => {
  const accepted = JvmLifecycleRequestSchema.safeParse({
    action: "attach",
    input: {
      pid: "1234",
      confirm: true,
      probeHost: "127.0.0.1",
      probePort: 9190,
    },
  });
  assert.equal(accepted.success, true);

  const rejected = JvmLifecycleRequestSchema.safeParse({
    action: "attach",
    input: { pid: "1234", confirm: false },
  });
  assert.equal(rejected.success, false);
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
