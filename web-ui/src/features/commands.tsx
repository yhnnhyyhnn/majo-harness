import type { Command, CommandSeat, Feature } from "../slots";
import { api } from "../api";

// Slash commands (dsh ui-commands equivalent): pure definitions registered
// against the commands slot. The shell injects live state/actions at run
// time, so features stay action-free until executed. Add a command = add one
// definition here (or from any feature via context.addCommand).

const usageLine = (command: Command) =>
  `/${command.names[0]} ${command.usage}`.trimEnd() + " — " + command.description;

const commands: Command[] = [
  {
    names: ["help", "?"],
    usage: "",
    description: "list available commands",
    group: "general",
    run(seat: CommandSeat) {
      const sorted = [...seat.commands].sort((a, b) => a.names[0].localeCompare(b.names[0]));
      return "commands:\n" + sorted.map(usageLine).join("\n");
    },
  },
  {
    names: ["clear"],
    usage: "",
    description: "clear the composer",
    group: "composer",
    run(seat: CommandSeat) {
      seat.run((actions) => actions.setInput(""));
      return "composer cleared";
    },
  },
  {
    names: ["new"],
    usage: "",
    description: "start a new conversation",
    group: "session",
    run(seat: CommandSeat) {
      void seat.run((actions) => actions.newChat());
      return "new conversation";
    },
  },
  {
    names: ["model"],
    usage: "<name>",
    description: "switch the global model",
    group: "model",
    async run(seat: CommandSeat, args: string[]) {
      if (!args[0]) {
        const available = seat.state.models.join(", ") || "—";
        return "usage: /model <name>\navailable: " + available;
      }
      await seat.run((actions) => actions.changeModel(args[0]));
      return "model → " + args[0];
    },
  },
  {
    names: ["session-model"],
    usage: "<name> | default",
    description: "override the model for this session",
    group: "model",
    async run(seat: CommandSeat, args: string[]) {
      const override = args[0] && args[0].toLowerCase() !== "default" ? args[0] : null;
      await seat.run((actions) => actions.changeSessionModel(override));
      return override ? "session model → " + override : "session model → default";
    },
  },
  {
    names: ["delegate"],
    usage: "<task>",
    description: "run a scoped child delegation directly",
    group: "agents",
    async run(_seat: CommandSeat, args: string[]) {
      const task = args.join(" ").trim();
      if (!task) return "usage: /delegate <task>";
      const result = await api.delegate({ task });
      const preview = (result.answer || "").replace(/\s+/g, " ").trim().slice(0, 160);
      return "child " + result.childSessionId.slice(0, 8) + " → " + (preview || "(no answer)");
    },
  },
  {
    names: ["status"],
    usage: "",
    description: "harness counters from the host backend (status)",
    group: "system",
    async run(_seat: CommandSeat) {
      const result = await api.runCommand("status", {});
      return (result.output || "").trim() || "(host returned nothing)";
    },
  },
];

/** Payload mapping for host commands (pure, tested). */
export function hostCommandPayload(name: string, args: string[]): Record<string, unknown> {
  return name === "delegate" ? { task: args.join(" ").trim() } : {};
}

/** Host backend commands (listCommands) surface here unless a client twin exists. */
function hostCommand(host: { name: string; description: string }): Command {
  return {
    names: [host.name],
    usage: "",
    description: host.description || "host backend command",
    group: "host",
    async run(_seat: CommandSeat, args: string[]) {
      const result = await api.runCommand(host.name, hostCommandPayload(host.name, args));
      return (result.output || "").replace(/\s+/g, " ").trim().slice(0, 200)
        || "(host returned nothing)";
    },
  };
}

export const commandsFeature: Feature = {
  id: "commands",
  register(context) {
    for (const command of commands) {
      context.addCommand(command);
    }
    const clientNames = new Set(commands.flatMap((command) => command.names));
    // Host commands can be contributed by plugins at runtime; list them and
    // register any that have no client twin (e.g. a plugin-provided command).
    void api
      .listCommands()
      .then((index) => {
        for (const host of index.commands) {
          if (host.name && !clientNames.has(host.name)) {
            context.addCommand(hostCommand(host));
          }
        }
      })
      .catch(() => {
        // offline/older backend: host commands simply stay absent
      });
  },
};
