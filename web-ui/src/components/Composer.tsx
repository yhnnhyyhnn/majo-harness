import { useState } from "react";
import { api } from "../api";
import { commandHintsFor } from "../commands-helpers";
import { useSlots, type CommandSeat } from "../slots";
import type { useChat } from "../useChat";

type ChatState = ReturnType<typeof useChat>["state"];
type ChatActions = ReturnType<typeof useChat>["actions"];

/** One @file completion candidate (workspace-relative path). */
type MentionHint = { path: string };

/**
 * The composer: textarea, slash-command completions (float while typing a
 * leading "/") and @file mention completions (float while typing "@path"),
 * with ↑↓/Tab/Enter arbitration, and dispatch (command vs plain task).
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
  const [mentionHints, setMentionHints] = useState<MentionHint[]>([]);
  const [mentionSelected, setMentionSelected] = useState(0);

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
  const raw = state.input.trim();
  const commandHints = (() => {
    if (state.busy || !raw.startsWith("/")) return [];
    return commandHintsFor(commands, raw.slice(1).toLowerCase());
  })();

  // @file mention completions: a trailing "@prefix" floats matching files;
  // selecting one injects its content as a durable context note and leaves
  // an @path reference in the message
  const mentionQuery = (() => {
    if (state.busy || commandHints.length > 0) return null;
    const match = state.input.match(/@([^\s@]*)$/);
    return match ? match[1] : null;
  })();

  const refreshMentions = (query: string) => {
    api
      .mentionSuggest(query)
      .then((index) => {
        setMentionHints(index.files.slice(0, 8).map((file) => ({ path: file.path })));
        setMentionSelected(0);
      })
      .catch(() => setMentionHints([]));
  };

  const pickMention = (hint: MentionHint) => {
    if (!state.sessionId) {
      flash("@file: open a session first");
      setMentionHints([]);
      return;
    }
    api
      .mentionInject(state.sessionId, hint.path)
      .then(() => {
        actions.setInput(state.input.replace(/@([^\s@]*)$/, "@" + hint.path + " "));
        flash("attached @" + hint.path + " (content injected into context)");
      })
      .catch((error) => flash(String(error)))
      .finally(() => {
        setMentionHints([]);
        const input = document.getElementById("input");
        input?.focus();
      });
  };

  const mentionArbitration = (e: React.KeyboardEvent<HTMLTextAreaElement>): boolean => {
    if (mentionQuery === null || mentionHints.length === 0) return false;
    if (e.key === "Escape") {
      e.preventDefault();
      setMentionHints([]);
      return true;
    }
    if (e.key === "ArrowDown") {
      e.preventDefault();
      setMentionSelected((mentionSelected + 1) % mentionHints.length);
      return true;
    }
    if (e.key === "ArrowUp") {
      e.preventDefault();
      setMentionSelected(
        (mentionSelected - 1 + mentionHints.length) % mentionHints.length
      );
      return true;
    }
    if (e.key === "Tab" || (e.key === "Enter" && !e.shiftKey)) {
      e.preventDefault();
      const pick = mentionHints[mentionSelected % mentionHints.length];
      if (pick) pickMention(pick);
      return true;
    }
    return false;
  };

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
      {mentionQuery !== null && mentionHints.length > 0 && (
        <div id="command-hints">
          <div className="hint-group">@files</div>
          {mentionHints.map((hint, index) => (
            <button
              key={hint.path}
              type="button"
              className={index === mentionSelected ? "active" : undefined}
              onMouseEnter={() => setMentionSelected(index)}
              onClick={() => pickMention(hint)}
            >
              <code>{"@" + hint.path}</code>
              <span className="meta hint-desc">attach file contents to context</span>
            </button>
          ))}
        </div>
      )}
      <form id="composer" onSubmit={send}>
        <textarea
          id="input"
          rows={1}
          value={state.input}
          placeholder="Type a task, /command, or @file… (Enter sends, Tab completes)"
          onChange={(e) => {
            actions.setInput(e.target.value);
            setCmdSelected(0);
            const match = e.target.value.match(/@([^\s@]*)$/);
            if (match && !state.busy) {
              refreshMentions(match[1]);
            } else {
              setMentionHints([]);
            }
          }}
          onKeyDown={(e) => {
            if (mentionArbitration(e)) return;
            const trimmed = state.input.trim();
            if (trimmed.startsWith("/")) {
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
              if (e.key === "Enter" && !e.shiftKey && hints.length && !exactCommand(trimmed)) {
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
