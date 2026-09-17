import { useState } from "react";
import { commandHintsFor } from "../commands-helpers";
import { useSlots, type CommandSeat } from "../slots";
import type { useChat } from "../useChat";

type ChatState = ReturnType<typeof useChat>["state"];
type ChatActions = ReturnType<typeof useChat>["actions"];

/**
 * The composer: textarea, slash-command completions (float while typing a
 * leading "/", ↑↓/Tab/Enter arbitration) and dispatch (command vs plain task).
 */
export function Composer({
  state,
  actions,
}: {
  state: ChatState;
  actions: ChatActions;
}) {
  const { commands } = useSlots();
  const [cmdSelected, setCmdSelected] = useState(0);

  const flash = (message: string) => actions.setNotice(message);

  const completeCommand = (name: string) => {
    actions.setInput("/" + name + " ");
    const input = document.getElementById("input");
    input?.focus();
  };

  const exactCommand = (raw: string): boolean => {
    const typed = raw.trim().slice(1).toLowerCase();
    return commands.some((candidate) =>
      candidate.names.some((name) => name.toLowerCase() === typed)
    );
  };

  const runCommand = async (raw: string): Promise<void> => {
    const tokens = raw.trim().slice(1).split(/\s+/);
    const name = (tokens[0] || "").toLowerCase();
    const def = commands.find((candidate) =>
      candidate.names.some((alias) => alias.toLowerCase() === name)
    );
    if (!def) {
      flash("unknown command /" + name + " — try /help");
      return;
    }
    const seat: CommandSeat = {
      state,
      async run(action) {
        try {
          await action(actions);
        } catch (error) {
          flash(String(error));
        }
      },
      flash,
      commands,
    };
    try {
      const output = await def.run(seat, tokens.slice(1));
      if (typeof output === "string") flash(output);
    } catch (error) {
      flash(String(error));
    }
  };

  const send = (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (state.input.trim().startsWith("/")) {
      void runCommand(state.input);
    } else {
      void actions.sendTask();
    }
  };

  // slash-command completions: typing a leading "/" floats matching commands
  const commandHints = (() => {
    if (state.busy || !state.input.trim().startsWith("/")) return [];
    const typed = state.input.trim().slice(1).toLowerCase();
    return commandHintsFor(commands, typed);
  })();

  return (
    <>
      {commandHints.length > 0 && (
        <div id="command-hints">
          {commandHints.map(({ command, name }, index) => {
            const previous = commandHints[index - 1];
            const showGroup =
              !previous || (previous.command.group || "") !== (command.group || "");
            return (
              <span key={name}>
                {showGroup && (
                  <div className="hint-group">{command.group || "commands"}</div>
                )}
                <button
                  type="button"
                  className={index === cmdSelected ? "active" : undefined}
                  onMouseEnter={() => setCmdSelected(index)}
                  onClick={() => completeCommand(name)}
                >
                  <code>/{name}</code>
                  {command.usage && <span className="hint-usage">{command.usage}</span>}
                  <span className="meta hint-desc">{command.description}</span>
                </button>
              </span>
            );
          })}
        </div>
      )}
      <form id="composer" onSubmit={send}>
        <textarea
          id="input"
          rows={1}
          value={state.input}
          placeholder="Type a task or /command… (Enter sends, Tab completes)"
          onChange={(e) => {
            actions.setInput(e.target.value);
            setCmdSelected(0);
          }}
          onKeyDown={(e) => {
            const raw = state.input.trim();
            if (raw.startsWith("/")) {
              const hints = commandHints;
              if (e.key === "Escape") {
                e.preventDefault();
                actions.setInput("");
                return;
              }
              if (e.key === "ArrowDown") {
                e.preventDefault();
                if (hints.length) setCmdSelected((cmdSelected + 1) % hints.length);
                return;
              }
              if (e.key === "ArrowUp") {
                e.preventDefault();
                if (hints.length)
                  setCmdSelected((cmdSelected - 1 + hints.length) % hints.length);
                return;
              }
              if (e.key === "Tab") {
                e.preventDefault();
                const pick = hints[Math.min(cmdSelected, hints.length - 1)];
                if (pick) completeCommand(pick.name);
                return;
              }
              if (e.key === "Enter" && !e.shiftKey && hints.length && !exactCommand(raw)) {
                e.preventDefault();
                const pick = hints[cmdSelected % hints.length];
                if (pick) completeCommand(pick.name);
                return;
              }
            }
            if (e.key === "Enter" && !e.shiftKey) {
              e.preventDefault();
              if (state.input.trim().startsWith("/")) {
                void runCommand(state.input);
              } else {
                void actions.sendTask();
              }
            }
          }}
        />
        <button id="send" type="submit" disabled={state.busy}>
          Send
        </button>
      </form>
    </>
  );
}
