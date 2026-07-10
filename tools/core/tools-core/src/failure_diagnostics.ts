import type { FailureReasonMeta } from "@tools-core/failure_diagnostics.model";

const NEXT_ACTION_CODE_BY_REASON: Record<string, string> = {
  project_selector_required: "provide_project_root",
  project_selector_missing: "provide_project_root",
  project_selector_not_absolute: "provide_project_root",
  project_selector_not_found: "provide_project_root",
  project_selector_no_build_marker: "fix_project_root_or_build_marker",
  project_selector_no_java_sources: "fix_project_root_or_java_sources",
  project_selector_invalid: "fix_project_selector_input",
  project_scope_mismatch: "align_project_selector",
  project_artifact_ambiguous: "select_project_artifact",
  project_artifact_missing: "create_or_select_project_artifact",
  class_hint_not_fqcn: "provide_class_fqcn",
  class_hint_required: "provide_class_hint",
  target_not_found: "refine_target_hints",
  target_candidate_missing: "refine_target_hints",
  target_ambiguous: "disambiguate_target",
  runtime_unreachable: "verify_probe_reachability",
  runtime_line_unresolved: "select_resolvable_line",
  line_hint_not_resolvable: "select_resolvable_line",
  line_target_required_for_probe_mode: "provide_line_hint",
  request_candidate_missing: "refine_request_hints",
  request_confirmation_required: "confirm_request_candidate",
  actuation_input_required: "provide_actuation_input",
  auth_input_required: "provide_auth_input",
  additional_source_roots_invalid: "fix_additional_source_roots",
  additional_source_roots_limit_exceeded: "reduce_additional_source_roots",
  line_key_required: "provide_strict_line_key",
  invalid_line_target: "align_runtime_and_artifact",
  service_unreachable: "verify_probe_connectivity",
  capture_not_found: "request_new_capture",
  capture_unavailable: "retry_capture_lookup",
  diagnose_failed: "resolve_probe_diagnostics",
  runtime_not_aligned: "align_runtime_and_artifact",
  probe_connectivity_issue: "verify_probe_connectivity",
  line_not_executed_in_window: "verify_trigger_path",
  runtime_mappings_input_required: "provide_mappings_base_url",
  runtime_mappings_unreachable: "verify_runtime_mappings_endpoint",
  runtime_mappings_unauthorized: "authorize_runtime_mappings_access",
  runtime_mappings_invalid_payload: "verify_runtime_mappings_payload",
  runtime_mapping_not_found: "refine_runtime_mapping_hints",
  runtime_mapping_ambiguous: "disambiguate_runtime_mapping",
};

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function sanitizeActionCode(value: string): string {
  return value.trim().toLowerCase().replace(/[^a-z0-9]+/g, "_").replace(/^_+|_+$/g, "");
}

export function deriveNextActionCode(reasonCode?: string): string | undefined {
  if (typeof reasonCode !== "string" || reasonCode.trim().length === 0) return undefined;
  return NEXT_ACTION_CODE_BY_REASON[reasonCode] ?? sanitizeActionCode(reasonCode);
}

export function normalizeReasonMeta(
  reasonMeta: unknown,
  allowedKeys?: readonly string[],
): FailureReasonMeta | undefined {
  if (!isRecord(reasonMeta)) return undefined;
  const entries = Object.entries(reasonMeta);
  const filtered =
    Array.isArray(allowedKeys) && allowedKeys.length > 0
      ? entries.filter(([key]) => allowedKeys.includes(key))
      : entries;
  if (filtered.length === 0) return undefined;
  return Object.fromEntries(filtered);
}

