import type { ShStepRenderArgs, ShTransportRenderResult } from "@tools-export-execution-profile/models/transport_export.model";
import type { ShTransportExportAdapter } from "@tools-export-execution-profile/adapters/registry/transport_export_adapter.interface";
import { httpShTransportAdapter } from "@tools-export-execution-profile/adapters/http/http_sh.adapter";
import { unsupportedShTransportAdapter } from "@tools-export-execution-profile/adapters/fallback/unsupported_sh.adapter";

const adapters: ShTransportExportAdapter[] = [httpShTransportAdapter, unsupportedShTransportAdapter];

export function renderShTransportStep(args: ShStepRenderArgs): ShTransportRenderResult {
  for (const adapter of adapters) {
    if (!adapter.canHandle(args)) continue;
    return adapter.render(args);
  }
  return unsupportedShTransportAdapter.render(args);
}
