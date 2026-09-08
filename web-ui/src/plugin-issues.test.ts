import { describe, it, expect } from "vitest";
import { pluginIssues } from "./plugin-issues";

describe("plugin hygiene warnings", () => {
  it("flags a duplicate frontend id across mounts", () => {
    const issues = pluginIssues([
      { name: "a", title: "demo", version: "1.0.0" },
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
      { name: "a", title: "one", version: "1.0.0" },
      { name: "b", title: "two", version: "0.2.0" },
    ]);
    expect(issues).toEqual([]);
  });
});
