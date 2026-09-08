/** Visible identity + version for warning logic. */
export interface PluginLite {
  name: string;
  id?: string | null;
  title?: string | null;
  version?: string | null;
}

/**
 * Mounted-plugin hygiene warnings (pure, tested): duplicate frontend ids
 * (same manifest id mounted twice — stale/ambiguous reload targets) and
 * missing versions (unversioned jars can't be pinned or diffed). The
 * manifest {@code id} is the stable identity; title/name are display-only
 * fallbacks when a manifest has no explicit id.
 */
export const pluginIssues = (plugins: PluginLite[]): string[] => {
  const issues: string[] = [];
  const byId = new Map<string, number>();
  for (const plugin of plugins) {
    const key = (plugin.id || plugin.title || plugin.name).trim();
    byId.set(key, (byId.get(key) ?? 0) + 1);
  }
  for (const [id, count] of byId) {
    if (count > 1) {
      issues.push(`duplicate frontend id "${id}" (mounted ${count}x)`);
    }
  }
  for (const plugin of plugins) {
    if (!plugin.version) {
      issues.push(`unversioned: "${plugin.title || plugin.name}" (add version to plugin.json)`);
    }
  }
  return issues;
};
