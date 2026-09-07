// Pure markdown parsing helpers shared by the renderer and its tests.

/** Splits one GFM pipe-table row into trimmed cell strings. */
export const splitPipeCells = (line: string): string[] =>
  line
    .replace(/^\s*\|/, "")
    .replace(/\|\s*$/, "")
    .split("|")
    .map((cell) => cell.trim());

/** Whether a line is a GFM table separator row (e.g. `|---|---|`, `|:--|--:|`). */
export const isTableSeparator = (line: string | undefined): boolean =>
  !!line && /^\|?\s*:?-{1,}:?\s*(\|\s*:?-{1,}:?\s*)*\|?$/.test(line.trim());
