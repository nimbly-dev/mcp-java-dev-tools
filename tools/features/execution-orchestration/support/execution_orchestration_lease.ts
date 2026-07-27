import {
  acquireRegressionSuiteLease,
  openRunStateStore,
  releaseRegressionSuiteLease,
} from "@tools-feature-artifact-management";
import { CheckpointPersistenceError } from "./execution_orchestration_state";

export async function releaseSuiteLeaseBestEffort(args: {
  workspaceRootAbs: string;
  projectName: string;
  suiteRunId: string;
  ownerId: string;
}): Promise<void> {
  const store = await openRunStateStore({
    workspaceRootAbs: args.workspaceRootAbs,
    projectName: args.projectName,
  });
  if (!store.ok) return;
  try {
    releaseRegressionSuiteLease({
      store,
      suiteRunId: args.suiteRunId,
      ownerId: args.ownerId,
      nowEpochMs: Date.now(),
    });
  } finally {
    store.close();
  }
}

export async function renewSuiteLease(args: {
  workspaceRootAbs: string;
  projectName: string;
  suiteRunId: string | undefined;
  ownerId: string;
  acquired: boolean;
  deadlineAtEpochMs: number | undefined;
}): Promise<void> {
  if (!args.acquired || typeof args.suiteRunId !== "string") return;
  const store = await openRunStateStore({
    workspaceRootAbs: args.workspaceRootAbs,
    projectName: args.projectName,
  });
  if (!store.ok) throw new CheckpointPersistenceError(store.reasonCode);
  try {
    const nowEpochMs = Date.now();
    const leaseDurationMs =
      typeof args.deadlineAtEpochMs === "number" && args.deadlineAtEpochMs > nowEpochMs
        ? Math.max(30_000, args.deadlineAtEpochMs - nowEpochMs + 5_000)
        : 30_000;
    const renewed = acquireRegressionSuiteLease({
      store,
      suiteRunId: args.suiteRunId,
      ownerId: args.ownerId,
      nowEpochMs,
      leaseDurationMs,
    });
    if (!renewed.ok) throw new CheckpointPersistenceError(renewed.reasonCode);
  } finally {
    store.close();
  }
}
