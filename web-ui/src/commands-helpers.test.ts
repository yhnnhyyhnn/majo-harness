import { describe, expect, it } from "vitest";
import { commandHintsFor } from "./commands-helpers";
import type { Command, CommandSeat } from "./slots";

const command = (name: string, group?: string, alias?: string): Command => ({
  names: alias ? [name, alias] : [name],
  usage: "",
  description: "x",
  group,
  run: () => undefined,
});

const seat = {} as CommandSeat;

describe("command completion helpers", () => {
  const list = [
    command("session-model", "model"),
    command("model", "model"),
    command("new", "session"),
    command("clear", "composer"),
    command("help", "general", "?"),
  ];

  it("groups then sorts alphabetically, aliases de-duplicated", () => {
    const hints = commandHintsFor(list, "");
    expect(hints.map((h) => h.name)).toEqual([
      "clear", // composer
      "?", // general (same command as help, listed first by sort)
      "help",
      "model", // model
      "session-model",
      "new", // session
    ]);
    // run works on any entry (sanity for the fake command above)
    expect(list[0].run(seat, [])).toBeUndefined();
  });

  it("filters by the typed prefix", () => {
    const hints = commandHintsFor(list, "m");
    expect(hints.map((h) => h.name)).toEqual(["model"]);
    expect(commandHintsFor(list, "zzz")).toEqual([]);
  });
});

import { hostCommandPayload } from "./features/commands";

describe("host command payload mapping", () => {
  it("sends the rest of the line as task for delegate", () => {
    expect(hostCommandPayload("delegate", ["2+2", "and", "more"])).toEqual({ task: "2+2 and more" });
  });

  it("sends an empty payload for other host commands", () => {
    expect(hostCommandPayload("status", ["ignored"])).toEqual({});
  });
});
