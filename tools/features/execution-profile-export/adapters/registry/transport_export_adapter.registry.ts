import type { ShStepRenderArgs, ShTransportRenderResult } from "../../models/transport_export.model";
import type { ShTransportExportAdapter } from "./transport_export_adapter.interface";
import { httpShTransportAdapter } from "../http/http_sh.adapter";
import { unsupportedShTransportAdapter } from "../fallback/unsupported_sh.adapter";

const adapters: ShTransportExportAdapter[] = [httpShTransportAdapter, unsupportedShTransportAdapter];

export function renderShTransportStep(args: ShStepRenderArgs): ShTransportRenderResult {
  for (const adapter of adapters) {
    if (!adapter.canHandle(args)) continue;
    return adapter.render(args);
  }
  return unsupportedShTransportAdapter.render(args);
}
