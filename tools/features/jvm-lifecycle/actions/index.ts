import type { JvmLifecycleRequest } from "@tools-contracts/jvm-lifecycle";

import { attachAction } from "./attach.action";
import { deactivateAction } from "./deactivate.action";
import { listJvmsAction } from "./list_jvms.action";
import type { JvmLifecycleDomain, JvmLifecycleResponse } from "../models/jvm_lifecycle.model";

export function createJvmLifecycleDomain(): JvmLifecycleDomain {
  return {
    listJvms: listJvmsAction,
    attach: attachAction,
    deactivate: deactivateAction,
  };
}

export async function dispatchJvmLifecycleAction(
  domain: JvmLifecycleDomain,
  request: JvmLifecycleRequest,
): Promise<JvmLifecycleResponse> {
  switch (request.action) {
    case "list_jvms":
      return await domain.listJvms();
    case "attach":
      return await domain.attach(request.input);
    case "deactivate":
      return await domain.deactivate(request.input);
  }
}
