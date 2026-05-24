import type { ShStepRenderArgs, ShTransportRenderResult } from "@tools-export-execution-profile/models/transport_export.model";

export interface ShTransportExportAdapter {
  canHandle(args: ShStepRenderArgs): boolean;
  render(args: ShStepRenderArgs): ShTransportRenderResult;
}
