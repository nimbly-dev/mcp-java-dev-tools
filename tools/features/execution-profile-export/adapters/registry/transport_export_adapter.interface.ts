import type { ShStepRenderArgs, ShTransportRenderResult } from "../../models/transport_export.model";

export interface ShTransportExportAdapter {
  canHandle(args: ShStepRenderArgs): boolean;
  render(args: ShStepRenderArgs): ShTransportRenderResult;
}
