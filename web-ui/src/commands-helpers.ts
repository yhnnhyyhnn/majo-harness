import type { Command } from "./slots";

/** One completion entry in the slash-command panel. */
export interface CommandHint {
  command: Command;
  name: string;
}

/**
 * Builds the completion list for a partial slash input: every command name
 * (aliases de-duplicated, first wins), sorted by optional group then name,
 * filtered by the typed prefix when present. Used by the composer hints and
 * tested directly.
 */
export const commandHintsFor = (commands: Command[], typed: string): CommandHint[] => {
  const seen = new Set<string>();
  const all = commands
    .flatMap((candidate) => candidate.names.map((name) => ({ command: candidate, name })))
    .filter(({ name }) => !seen.has(name) && seen.add(name))
    .sort((a, b) => {
      const groupA = a.command.group || "";
      const groupB = b.command.group || "";
      return groupA === groupB ? a.name.localeCompare(b.name) : groupA.localeCompare(groupB);
    });
  return typed ? all.filter(({ name }) => name.startsWith(typed)) : all;
};
