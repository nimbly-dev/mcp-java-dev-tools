import { transportExecuteAction } from "./execute_transport.action";

export type TransportExecutionActionMap = Readonly<Record<"execute", typeof transportExecuteAction>>;

export function dispatchTransportExecutionAction(args: Parameters<typeof transportExecuteAction>[0]) {
  switch ("execute") {
    case "execute":
      return transportExecuteAction(args);
  }
}
