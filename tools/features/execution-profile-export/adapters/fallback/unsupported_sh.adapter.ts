import { escapeShSingleQuoted } from "../../common";
import type { ShTransportExportAdapter } from "../registry/transport_export_adapter.interface";

export const unsupportedShTransportAdapter: ShTransportExportAdapter = {
  canHandle() {
    return true;
  },
  render(args) {
    return {
      handled: true,
      lines: [
        `echo 'unsupported transport protocol for step ${escapeShSingleQuoted(args.step.id)}, delegating to plan wrapper'`,
        `${"${REPLAY_COMMAND}"} --plan-name '${escapeShSingleQuoted(args.planName)}'`,
        "if [ $? -ne 0 ]; then echo 'plan execution failed' >&2; exit 1; fi",
      ],
    };
  },
};
