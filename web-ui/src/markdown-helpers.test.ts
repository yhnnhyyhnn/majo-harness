import { describe, expect, it } from "vitest";
import { isTableSeparator, splitPipeCells } from "./markdown-helpers";

describe("markdown table helpers", () => {
  it("splitPipeCells handles leading/trailing pipes and spacing", () => {
    expect(splitPipeCells("| a | b |")).toEqual(["a", "b"]);
    expect(splitPipeCells("| x ")).toEqual(["x"]);
    expect(splitPipeCells("plain|cell")).toEqual(["plain", "cell"]);
    expect(splitPipeCells("| one || three |")).toEqual(["one", "", "three"]);
  });

  it("isTableSeparator accepts GFM separators", () => {
    for (const line of ["|---|---|", "|:--|--:|", "| :-: | --- |", "|---|", "---"]) {
      expect(isTableSeparator(line)).toBe(true);
    }
  });

  it("isTableSeparator rejects header/body and prose lines", () => {
    for (const line of ["| a | b |", "just text", "| a -- b |"]) {
      expect(isTableSeparator(line)).toBe(false);
    }
    expect(isTableSeparator(undefined)).toBe(false);
  });
});
