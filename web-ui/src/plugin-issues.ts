/** Visible identity + version for warning logic. */
export interface PluginLite {
  name: string;
  title?: string | null;
  version?: string | null;
}

/**
 * Mounted-plugin hygiene warnings (pure, tested): duplicate frontend ids
 * (same title mounted twice — stale/ambiguous reload targets) and missing
 * versions (unversioned jars can't be pinned or diffed). Title is the
 * manifest identity when plugin.json has no explicit id.
 */
export const pluginIssues = (plugins: PluginLite[]): string[] => {
  const issues: string[] = [];
  const byTitle = new Map<string, number>();
  for (const plugin of plugins) {
    const key = (plugin.title || plugin.name).trim();
    byTitle.set(key, (byTitle.get(key) ?? 0) + 1);
  }
  for (const [title, count] of byTitle) {
    if (count > 1) {
      issues.push(`duplicate frontend id "${title}" (mounted ${count}x)`);
    }
  }
  for (const plugin of plugins) {
    if (!plugin.version) {
      issues.push(`unversioned: "${plugin.title || plugin.name}" (add version to plugin.json)`);
    }
  }
  return issues;
};
