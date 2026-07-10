export type RecipeTemplateModel = Record<string, string>;

const TOKEN_RX = /\{\{\s*([a-zA-Z0-9_.-]+)\s*\}\}/g;

function resolveToken(model: RecipeTemplateModel, token: string): string | undefined {
  if (token in model) return model[token];
  const lower = token.toLowerCase();
  if (lower in model) return model[lower];
  return undefined;
}

export function renderRecipeTemplate(template: string, model: RecipeTemplateModel): string {
  return template.replace(TOKEN_RX, (full, key: string) => {
    const resolved = resolveToken(model, key.trim());
    // Keep unknown variables unchanged for maximum template freedom.
    return typeof resolved === "string" ? resolved : full;
  });
}
