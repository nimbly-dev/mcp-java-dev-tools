export function renderShBlock(title: string, lines: string[]): string[] {
  return [
    "# ============================================",
    `# ${title}`,
    "# ============================================",
    ...lines,
    "",
  ];
}
