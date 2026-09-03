import type { Command, CommandSeat, Feature } from "../slots";

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
    run(seat: CommandSeat) {
      const sorted = [...seat.commands].sort((a, b) => a.names[0].localeCompare(b.names[0]));
      return "commands:\n" + sorted.map(usageLine).join("\n");
    },
  },
  {
    names: ["clear"],
    usage: "",
    description: "clear the composer",
    run(seat: CommandSeat) {
      seat.run((actions) => actions.setInput(""));
      return "composer cleared";
    },
  },
  {
    names: ["new"],
    usage: "",
    description: "start a new conversation",
    run(seat: CommandSeat) {
      void seat.run((actions) => actions.newChat());
      return "new conversation";
    },
  },
  {
    names: ["model"],
    usage: "<name>",
    description: "switch the global model",
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
    async run(seat: CommandSeat, args: string[]) {
      const override = args[0] && args[0].toLowerCase() !== "default" ? args[0] : null;
      await seat.run((actions) => actions.changeSessionModel(override));
      return override ? "session model → " + override : "session model → default";
    },
  },
];

export const commandsFeature: Feature = {
  id: "commands",
  register(context) {
    for (const command of commands) {
      context.addCommand(command);
    }
  },
};
