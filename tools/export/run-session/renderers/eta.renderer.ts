import { promises as fs } from "node:fs";
import path from "node:path";

import { Eta } from "eta";

const eta = new Eta({ autoEscape: false });

export async function renderEtaTemplate(input: {
  templateFileName: string;
  data: Record<string, unknown>;
}): Promise<string> {
  const templatePathAbs = path.join(__dirname, "..", "templates", input.templateFileName);
  const templateText = await fs.readFile(templatePathAbs, "utf8");
  const rendered = eta.renderString(templateText, input.data);
  if (typeof rendered !== "string") {
    throw new Error("template_render_failed");
  }
  return rendered.endsWith("\n") ? rendered : `${rendered}\n`;
}
