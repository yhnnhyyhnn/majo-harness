import { describe, it, expect } from "vitest";
import { pluginIssues } from "./plugin-issues";

describe("plugin hygiene warnings", () => {
  it("flags a duplicate manifest id across mounts (display title ignored)", () => {
    const issues = pluginIssues([
      { name: "a", id: "io.majo.demo", title: "demo alpha", version: "1.0.0" },
      { name: "b", id: "io.majo.demo", title: "demo beta", version: "2.0.0" },
    ]);
    expect(issues).toContain('duplicate frontend id "io.majo.demo" (mounted 2x)');
  });

  it("falls back to title/name when no explicit id", () => {
    const issues = pluginIssues([
      { name: "a", title: "demo" },
      { name: "b", title: "demo", version: "2.0.0" },
    ]);
    expect(issues).toContain('duplicate frontend id "demo" (mounted 2x)');
  });

  it("flags unversioned plugins", () => {
    const issues = pluginIssues([{ name: "old", title: "legacy" }]);
    expect(issues).toContain('unversioned: "legacy" (add version to plugin.json)');
  });

  it("stays quiet for distinct, versioned mounts", () => {
    const issues = pluginIssues([
      { name: "a", id: "one", version: "1.0.0" },
      { name: "b", id: "two", version: "0.2.0" },
    ]);
    expect(issues).toEqual([]);
  });
});
