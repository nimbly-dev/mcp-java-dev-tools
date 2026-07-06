import type {
  ExternalVerificationAssertionResult,
  ExternalVerificationExtractResult,
  NormalizedExternalVerificationResult,
  PlanExternalVerification,
  PlanExternalVerificationProviderType,
  PlanStepExpectation,
} from "@tools-regression-execution-plan-spec/models/regression_execution_plan_spec.model";
import { normalizePlaceholderSyntaxInString } from "@tools-regression-execution-plan-spec/placeholder_resolution.util";

function hasNonBlank(value: unknown): boolean {
  return typeof value !== "undefined" && value !== null && String(value).trim() !== "";
}

function asPositiveInteger(value: unknown): number | undefined {
  return typeof value === "number" && Number.isInteger(value) && value > 0 ? value : undefined;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isExpectationOperator(value: string): boolean {
  return (
    value === "field_equals" ||
    value === "field_exists" ||
    value === "field_matches_regex" ||
    value === "numeric_gte" ||
    value === "numeric_lte" ||
    value === "contains" ||
    value === "probe_line_hit" ||
    value === "outcome_status"
  );
}

function expectationNeedsExpected(operator: string): boolean {
  return (
    operator === "field_equals" ||
    operator === "field_matches_regex" ||
    operator === "numeric_gte" ||
    operator === "numeric_lte" ||
    operator === "contains" ||
    operator === "probe_line_hit" ||
    operator === "outcome_status"
  );
}

function isExternalVerificationProviderType(value: unknown): value is PlanExternalVerificationProviderType {
  return value === "http" || value === "sql";
}

const allowedSqlRequestKeys = new Set(["connectionRef", "statement", "parameters", "timeoutMs"]);

function containsPlaceholderToken(value: string): boolean {
  const normalized = normalizePlaceholderSyntaxInString(value);
  return /\$\{[^}]+\}/.test(normalized.normalized);
}

function isSecretBearingHeaderName(value: string): boolean {
  const normalized = value.trim().toLowerCase();
  return (
    normalized === "authorization" ||
    normalized === "proxy-authorization" ||
    normalized === "x-api-key" ||
    normalized === "api-key" ||
    normalized.endsWith("-api-key") ||
    normalized.endsWith("-token")
  );
}

function validateExternalVerificationExpectationEntries(args: {
  ownerId: string;
  expectations: PlanStepExpectation[] | undefined;
}):
  | {
      ok: true;
    }
  | {
      ok: false;
      reasonCode: "external_verification_expectations_missing" | "external_verification_expectation_invalid";
      requiredUserAction: string[];
    } {
  const contractPath = "externalVerification[].expect[]";
  if (!Array.isArray(args.expectations) || args.expectations.length === 0) {
    return {
      ok: false,
      reasonCode: "external_verification_expectations_missing",
      requiredUserAction: [`Add deterministic ${contractPath} entries for external verification '${args.ownerId}'.`],
    };
  }

  for (const raw of args.expectations) {
    const expectation = raw as PlanStepExpectation;
    if (!isRecord(expectation)) {
      return {
        ok: false,
        reasonCode: "external_verification_expectation_invalid",
        requiredUserAction: [`Ensure all expectations for external verification '${args.ownerId}' are objects.`],
      };
    }
    if (!hasNonBlank(expectation.id)) {
      return {
        ok: false,
        reasonCode: "external_verification_expectation_invalid",
        requiredUserAction: [`Set non-empty expectation id for external verification '${args.ownerId}'.`],
      };
    }
    if (!hasNonBlank(expectation.actualPath)) {
      return {
        ok: false,
        reasonCode: "external_verification_expectation_invalid",
        requiredUserAction: [
          `Set non-empty expectation actualPath for external verification '${args.ownerId}' (id='${expectation.id}').`,
        ],
      };
    }
    if (!hasNonBlank(expectation.operator) || !isExpectationOperator(expectation.operator)) {
      return {
        ok: false,
        reasonCode: "external_verification_expectation_invalid",
        requiredUserAction: [
          `Set supported expectation operator for external verification '${args.ownerId}' (id='${expectation.id}').`,
        ],
      };
    }
    if (expectationNeedsExpected(expectation.operator) && typeof expectation.expected === "undefined") {
      return {
        ok: false,
        reasonCode: "external_verification_expectation_invalid",
        requiredUserAction: [
          `Set expectation expected value for external verification '${args.ownerId}' (id='${expectation.id}', operator='${expectation.operator}').`,
        ],
      };
    }
  }

  return { ok: true };
}

function validateExternalVerificationExtractEntries(args: {
  ownerId: string;
  extracts: unknown;
}):
  | {
      ok: true;
    }
  | {
      ok: false;
      reasonCode: "external_verification_extract_invalid";
      requiredUserAction: string[];
    } {
  if (typeof args.extracts === "undefined") {
    return { ok: true };
  }
  if (!Array.isArray(args.extracts)) {
    return {
      ok: false,
      reasonCode: "external_verification_extract_invalid",
      requiredUserAction: [`Set external verification '${args.ownerId}' extract[] to an array when present.`],
    };
  }

  for (const rawExtract of args.extracts) {
    if (!isRecord(rawExtract)) {
      return {
        ok: false,
        reasonCode: "external_verification_extract_invalid",
        requiredUserAction: [`Ensure all extract entries for external verification '${args.ownerId}' are objects.`],
      };
    }
    if (!hasNonBlank(rawExtract.from) || !hasNonBlank(rawExtract.as)) {
      return {
        ok: false,
        reasonCode: "external_verification_extract_invalid",
        requiredUserAction: [
          `Set non-empty extract.from and extract.as for external verification '${args.ownerId}'.`,
        ],
      };
    }
    if (typeof rawExtract.required !== "undefined" && typeof rawExtract.required !== "boolean") {
      return {
        ok: false,
        reasonCode: "external_verification_extract_invalid",
        requiredUserAction: [`Set external verification '${args.ownerId}' extract.required to boolean when present.`],
      };
    }
  }

  return { ok: true };
}

type InvalidExternalVerificationPlaceholder = {
  fieldPath: string;
  invalidToken: string;
};

function findInvalidExternalVerificationPlaceholder(args: {
  value: unknown;
  fieldPath: string;
}): InvalidExternalVerificationPlaceholder | null {
  if (typeof args.value === "string") {
    const normalized = normalizePlaceholderSyntaxInString(args.value);
    if (typeof normalized.invalidToken === "string") {
      return {
        fieldPath: args.fieldPath,
        invalidToken: normalized.invalidToken,
      };
    }
    return null;
  }
  if (Array.isArray(args.value)) {
    for (let index = 0; index < args.value.length; index += 1) {
      const invalid = findInvalidExternalVerificationPlaceholder({
        value: args.value[index],
        fieldPath: `${args.fieldPath}[${index}]`,
      });
      if (invalid) return invalid;
    }
    return null;
  }
  if (isRecord(args.value)) {
    for (const [key, entry] of Object.entries(args.value)) {
      const invalid = findInvalidExternalVerificationPlaceholder({
        value: entry,
        fieldPath: `${args.fieldPath}.${key}`,
      });
      if (invalid) return invalid;
    }
  }
  return null;
}

function validateHttpRequest(args: {
  verificationId: string;
  request: Record<string, unknown>;
}):
  | {
      ok: true;
    }
  | {
      ok: false;
      reasonCode: "external_verification_request_invalid" | "external_verification_placeholder_syntax_invalid";
      requiredUserAction: string[];
    } {
  const method = args.request.method;
  if (
    method !== "GET" &&
    method !== "POST" &&
    method !== "PUT" &&
    method !== "PATCH" &&
    method !== "DELETE" &&
    method !== "HEAD" &&
    method !== "OPTIONS"
  ) {
    return {
      ok: false,
      reasonCode: "external_verification_request_invalid",
      requiredUserAction: [
        `Set external verification '${args.verificationId}' request.http.method to GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS.`,
      ],
    };
  }

  const hasPathTemplate = hasNonBlank(args.request.pathTemplate);
  const hasUrl = hasNonBlank(args.request.url);
  if (hasPathTemplate === hasUrl) {
    return {
      ok: false,
      reasonCode: "external_verification_request_invalid",
      requiredUserAction: [
        `Set external verification '${args.verificationId}' request.http using exactly one of pathTemplate or url.`,
      ],
    };
  }

  if (typeof args.request.headers !== "undefined") {
    if (!isRecord(args.request.headers)) {
      return {
        ok: false,
        reasonCode: "external_verification_request_invalid",
        requiredUserAction: [`Set external verification '${args.verificationId}' request.http.headers to an object when present.`],
      };
    }
    for (const [headerName, headerValue] of Object.entries(args.request.headers)) {
      if (typeof headerValue !== "string") {
        return {
          ok: false,
          reasonCode: "external_verification_request_invalid",
          requiredUserAction: [
            `Set external verification '${args.verificationId}' request.http.headers.${headerName} to a string value.`,
          ],
        };
      }
      if (isSecretBearingHeaderName(headerName) && !containsPlaceholderToken(headerValue)) {
        return {
          ok: false,
          reasonCode: "external_verification_request_invalid",
          requiredUserAction: [
            `Replace inline secret-bearing header '${headerName}' in external verification '${args.verificationId}' with a canonical context placeholder-backed value.`,
          ],
        };
      }
    }
  }

  if (typeof args.request.timeoutMs !== "undefined" && args.request.timeoutMs !== null && !asPositiveInteger(args.request.timeoutMs)) {
    return {
      ok: false,
      reasonCode: "external_verification_request_invalid",
      requiredUserAction: [`Set external verification '${args.verificationId}' request.http.timeoutMs to a positive integer or null.`],
    };
  }

  const invalidPlaceholder = findInvalidExternalVerificationPlaceholder({
    value: args.request,
    fieldPath: "request.http",
  });
  if (invalidPlaceholder) {
    return {
      ok: false,
      reasonCode: "external_verification_placeholder_syntax_invalid",
      requiredUserAction: [
        `Fix malformed placeholder syntax in external verification '${args.verificationId}' at '${invalidPlaceholder.fieldPath}' (token='${invalidPlaceholder.invalidToken}'). Supported placeholder forms are \${key}, {{key}}, and {{{key}}}.`,
      ],
    };
  }

  return { ok: true };
}

function validateSqlRequest(args: {
  verificationId: string;
  request: Record<string, unknown>;
}):
  | {
      ok: true;
    }
  | {
      ok: false;
      reasonCode: "external_verification_request_invalid";
      requiredUserAction: string[];
    } {
  const invalidRequestKey = Object.keys(args.request).find((key) => !allowedSqlRequestKeys.has(key));
  if (typeof invalidRequestKey === "string") {
    return {
      ok: false,
      reasonCode: "external_verification_request_invalid",
      requiredUserAction: [
        `Remove unsupported request.sql field '${invalidRequestKey}' from external verification '${args.verificationId}'. Keep SQL connection details in runtime/project-owned configuration addressed by connectionRef.`,
      ],
    };
  }
  if (!hasNonBlank(args.request.connectionRef)) {
    return {
      ok: false,
      reasonCode: "external_verification_request_invalid",
      requiredUserAction: [`Set external verification '${args.verificationId}' request.sql.connectionRef to a non-empty logical connection reference.`],
    };
  }
  if (!hasNonBlank(args.request.statement)) {
    return {
      ok: false,
      reasonCode: "external_verification_request_invalid",
      requiredUserAction: [`Set external verification '${args.verificationId}' request.sql.statement to a non-empty SQL statement.`],
    };
  }
  if (typeof args.request.timeoutMs !== "undefined" && args.request.timeoutMs !== null && !asPositiveInteger(args.request.timeoutMs)) {
    return {
      ok: false,
      reasonCode: "external_verification_request_invalid",
      requiredUserAction: [`Set external verification '${args.verificationId}' request.sql.timeoutMs to a positive integer or null.`],
    };
  }

  if (typeof args.request.parameters === "undefined") {
    return { ok: true };
  }
  if (!Array.isArray(args.request.parameters)) {
    return {
      ok: false,
      reasonCode: "external_verification_request_invalid",
      requiredUserAction: [`Set external verification '${args.verificationId}' request.sql.parameters to an array when present.`],
    };
  }

  for (const rawParameter of args.request.parameters) {
    if (!isRecord(rawParameter) || !hasNonBlank(rawParameter.name)) {
      return {
        ok: false,
        reasonCode: "external_verification_request_invalid",
        requiredUserAction: [`Set non-empty request.sql.parameters[].name values for external verification '${args.verificationId}'.`],
      };
    }
    const hasValue = "value" in rawParameter;
    const hasValueFromContext = hasNonBlank(rawParameter.valueFromContext);
    if (hasValue === hasValueFromContext) {
      return {
        ok: false,
        reasonCode: "external_verification_request_invalid",
        requiredUserAction: [
          `Set exactly one of value or valueFromContext for SQL parameter '${rawParameter.name}' in external verification '${args.verificationId}'.`,
        ],
      };
    }
    if (
      hasValueFromContext &&
      String(rawParameter.valueFromContext).trim().startsWith("context.")
    ) {
      return {
        ok: false,
        reasonCode: "external_verification_request_invalid",
        requiredUserAction: [
          `Set SQL parameter '${rawParameter.name}' valueFromContext in external verification '${args.verificationId}' to a canonical context key without 'context.'.`,
        ],
      };
    }
  }

  return { ok: true };
}

export function validateExternalVerificationContract(
  externalVerification: PlanExternalVerification[] | undefined,
):
  | { ok: true }
  | {
      ok: false;
      reasonCode:
        | "external_verification_id_invalid"
        | "external_verification_provider_invalid"
        | "external_verification_request_invalid"
        | "external_verification_extract_invalid"
        | "external_verification_expectations_missing"
        | "external_verification_expectation_invalid"
        | "external_verification_placeholder_syntax_invalid";
      requiredUserAction: string[];
    } {
  if (typeof externalVerification === "undefined") {
    return { ok: true };
  }
  if (!Array.isArray(externalVerification)) {
    return {
      ok: false,
      reasonCode: "external_verification_id_invalid",
      requiredUserAction: ["Set contract.externalVerification to an array of external verification definitions."],
    };
  }

  const verificationIds = new Set<string>();
  for (const rawVerification of externalVerification) {
    const verification = rawVerification as PlanExternalVerification;
    if (!isRecord(verification) || !hasNonBlank(verification.id)) {
      return {
        ok: false,
        reasonCode: "external_verification_id_invalid",
        requiredUserAction: ["Set non-empty external verification id values in contract.externalVerification[].id."],
      };
    }

    const verificationId = verification.id.trim();
    if (verificationIds.has(verificationId)) {
      return {
        ok: false,
        reasonCode: "external_verification_id_invalid",
        requiredUserAction: [`Ensure external verification id '${verificationId}' is unique within contract.externalVerification[].`],
      };
    }
    verificationIds.add(verificationId);

    if (!isRecord(verification.provider) || !isExternalVerificationProviderType(verification.provider.type)) {
      return {
        ok: false,
        reasonCode: "external_verification_provider_invalid",
        requiredUserAction: [
          `Set external verification '${verificationId}' provider.type to one of: http, sql.`,
        ],
      };
    }
    if (!isRecord(verification.request)) {
      return {
        ok: false,
        reasonCode: "external_verification_request_invalid",
        requiredUserAction: [`Set external verification '${verificationId}' request to an object.`],
      };
    }

    const requestKeys = Object.keys(verification.request);
    if (requestKeys.length !== 1 || requestKeys[0] !== verification.provider.type) {
      return {
        ok: false,
        reasonCode: "external_verification_request_invalid",
        requiredUserAction: [
          `Set external verification '${verificationId}' request.${verification.provider.type} and remove non-matching provider request blocks.`,
        ],
      };
    }

    const providerRequest = verification.request[verification.provider.type];
    if (!isRecord(providerRequest)) {
      return {
        ok: false,
        reasonCode: "external_verification_request_invalid",
        requiredUserAction: [
          `Set external verification '${verificationId}' request.${verification.provider.type} to an object.`,
        ],
      };
    }

    const requestValidation = verification.provider.type === "http"
      ? validateHttpRequest({
          verificationId,
          request: providerRequest,
        })
      : validateSqlRequest({
          verificationId,
          request: providerRequest,
        });
    if (!requestValidation.ok) {
      return requestValidation;
    }

    const extractValidation = validateExternalVerificationExtractEntries({
      ownerId: verificationId,
      extracts: verification.extract,
    });
    if (!extractValidation.ok) {
      return extractValidation;
    }

    const expectationValidation = validateExternalVerificationExpectationEntries({
      ownerId: verificationId,
      expectations: verification.expect,
    });
    if (!expectationValidation.ok) {
      return expectationValidation;
    }
  }

  return { ok: true };
}

function isExtractResult(value: unknown): value is ExternalVerificationExtractResult {
  return (
    isRecord(value) &&
    hasNonBlank(value.from) &&
    hasNonBlank(value.as) &&
    value.required !== undefined &&
    typeof value.required === "boolean" &&
    (value.status === "resolved" || value.status === "unresolved")
  );
}

function isAssertionResult(value: unknown): value is ExternalVerificationAssertionResult {
  return (
    isRecord(value) &&
    hasNonBlank(value.id) &&
    hasNonBlank(value.actualPath) &&
    hasNonBlank(value.operator) &&
    isExpectationOperator(String(value.operator)) &&
    (value.status === "pass" || value.status === "fail" || value.status === "blocked")
  );
}

export function validateNormalizedExternalVerificationResultShape(
  result: unknown,
):
  | { ok: true }
  | {
      ok: false;
      reasonCode: "external_verification_request_invalid";
      requiredUserAction: string[];
    } {
  if (!isRecord(result) || !hasNonBlank(result.id) || !isExternalVerificationProviderType(result.providerType)) {
    return {
      ok: false,
      reasonCode: "external_verification_request_invalid",
      requiredUserAction: ["Set normalized external verification results with non-empty id and providerType=http|sql."],
    };
  }
  const rawStatus = result.status;
  if (
    rawStatus !== "pass" &&
    rawStatus !== "fail_assertion" &&
    rawStatus !== "blocked_runtime"
  ) {
    return {
      ok: false,
      reasonCode: "external_verification_request_invalid",
      requiredUserAction: ["Set normalized external verification result status to pass|fail_assertion|blocked_runtime."],
    };
  }
  const normalized = result as NormalizedExternalVerificationResult;

  if (normalized.providerType === "http") {
    if (!isRecord(normalized.response)) {
      return {
        ok: false,
        reasonCode: "external_verification_request_invalid",
        requiredUserAction: ["Set normalized external verification HTTP results with a response object."],
      };
    }
    if (typeof normalized.sql !== "undefined") {
      return {
        ok: false,
        reasonCode: "external_verification_request_invalid",
        requiredUserAction: ["Remove sql payload from normalized external verification HTTP results."],
      };
    }
    if (
      typeof normalized.response.statusCode !== "undefined" &&
      !Number.isInteger(normalized.response.statusCode)
    ) {
      return {
        ok: false,
        reasonCode: "external_verification_request_invalid",
        requiredUserAction: ["Set normalized external verification response.statusCode to an integer when present."],
      };
    }
  }

  if (normalized.providerType === "sql") {
    if (!isRecord(normalized.sql) || !Number.isInteger(normalized.sql.rowCount) || normalized.sql.rowCount < 0) {
      return {
        ok: false,
        reasonCode: "external_verification_request_invalid",
        requiredUserAction: ["Set normalized external verification SQL results with sql.rowCount as a non-negative integer."],
      };
    }
    if (!Array.isArray(normalized.sql.rows) || normalized.sql.rows.some((row) => !isRecord(row))) {
      return {
        ok: false,
        reasonCode: "external_verification_request_invalid",
        requiredUserAction: ["Set normalized external verification sql.rows to an array of row objects."],
      };
    }
    if (typeof normalized.response !== "undefined") {
      return {
        ok: false,
        reasonCode: "external_verification_request_invalid",
        requiredUserAction: ["Remove response payload from normalized external verification SQL results."],
      };
    }
  }

  if (
    typeof normalized.extractResults !== "undefined" &&
    (!Array.isArray(normalized.extractResults) || normalized.extractResults.some((entry) => !isExtractResult(entry)))
  ) {
    return {
      ok: false,
      reasonCode: "external_verification_request_invalid",
      requiredUserAction: ["Set normalized external verification extractResults[] using deterministic extract result entries."],
    };
  }

  if (
    typeof normalized.assertions !== "undefined" &&
    (!Array.isArray(normalized.assertions) || normalized.assertions.some((entry) => !isAssertionResult(entry)))
  ) {
    return {
      ok: false,
      reasonCode: "external_verification_request_invalid",
      requiredUserAction: ["Set normalized external verification assertions[] using deterministic assertion result entries."],
    };
  }

  return { ok: true };
}
