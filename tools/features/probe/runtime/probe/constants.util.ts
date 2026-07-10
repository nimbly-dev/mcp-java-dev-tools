import { CONFIG_DEFAULTS } from "@tools-core/probe_defaults";

export const LAST_RESET_EPOCH_BY_KEY = new Map<string, number>();

export const DEFAULT_PROBE_WAIT_UNREACHABLE_MAX_RETRIES =
  CONFIG_DEFAULTS.PROBE_WAIT_UNREACHABLE_MAX_RETRIES;

export const HARD_MAX_PROBE_WAIT_UNREACHABLE_MAX_RETRIES =
  CONFIG_DEFAULTS.PROBE_WAIT_UNREACHABLE_MAX_RETRIES_MAX;

export type ProbeGuidance = {
  actionCode: string;
  nextActionCode: string;
  nextAction: string;
};

export const GUIDANCE_RUNTIME_NOT_ALIGNED: ProbeGuidance = {
  actionCode: "runtime_not_aligned",
  nextActionCode: "align_runtime_and_artifact",
  nextAction: "rebuild_app_artifact_and_restart_jvm_then_rerun_probe",
};

export const GUIDANCE_LINE_NOT_EXECUTED_IN_WINDOW: ProbeGuidance = {
  actionCode: "line_not_executed_in_window",
  nextActionCode: "verify_trigger_path",
  nextAction: "verify_trigger_path_or_branch_then_rerun_probe_wait_for_hit",
};

export const GUIDANCE_PROBE_CONNECTIVITY_ISSUE: ProbeGuidance = {
  actionCode: "probe_connectivity_issue",
  nextActionCode: "verify_probe_connectivity",
  nextAction: "verify_probe_base_url_and_agent_reachability_then_rerun",
};
