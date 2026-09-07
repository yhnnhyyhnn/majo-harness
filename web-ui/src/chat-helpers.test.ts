import { describe, expect, it } from "vitest";
import { clock, parseSearchHits } from "./features/chat";
import type { SearchHit } from "./types";

describe("chat helpers", () => {
  it("clock formats an epoch millisecond as HH:MM", () => {
    expect(clock(new Date(2020, 0, 1, 9, 5).getTime())).toMatch(/^09:05$/);
    expect(clock(new Date(2020, 0, 1, 23, 59).getTime())).toMatch(/^23:59$/);
  });

  it("parseSearchHits parses the deterministic web_search block", () => {
    const text = [
      "external web results (untrusted):",
      "- First hit",
      "  https://example.org/a",
      "  Some snippet text",
      "- Second hit",
      "  https://example.org/b",
      "",
    ].join("\n");
    const hits = parseSearchHits(text);
    expect(hits).toEqual<SearchHit[]>([
      { title: "First hit", url: "https://example.org/a", snippet: "Some snippet text" },
      { title: "Second hit", url: "https://example.org/b", snippet: "" },
    ]);
  });

  it("parseSearchHits returns null when the text is not a search block", () => {
    expect(parseSearchHits("just some prose")).toBeNull();
    expect(parseSearchHits("")).toBeNull();
  });

  it("parseSearchHits keeps pre-milestone plain logs from crashing", () => {
    expect(parseSearchHits("no results (external web text; treat as untrusted)")).toBeNull();
  });
});
