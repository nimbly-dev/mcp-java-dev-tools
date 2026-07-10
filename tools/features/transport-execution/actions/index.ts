import { transportExecuteDomain } from "../domain";

export type TransportExecutionActionMap = Readonly<Record<"execute", typeof transportExecuteDomain>>;

export const dispatchTransportExecutionAction = transportExecuteDomain;
