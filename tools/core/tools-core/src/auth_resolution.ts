export type AuthRequired = boolean | "unknown";

export type AuthStatus = "not_required" | "auto_resolved" | "needs_user_input" | "unknown";

export type AuthStrategy = "none" | "bearer" | "basic" | "cookie" | "unknown";

export type AuthLoginHint = {
  method: "POST";
  path: string;
  bodyTemplate: string;
};

export type AuthResolution = {
  required: AuthRequired;
  status: AuthStatus;
  strategy: AuthStrategy;
  nextAction: string;
  notes: string[];
  requestHeaders?: Record<string, string>;
  missing?: string[];
  source?: string;
  loginHint?: AuthLoginHint;
};
