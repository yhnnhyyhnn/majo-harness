import { describe, expect, it } from "vitest";
import { userMessageFrame } from "./features/chat";

describe("userMessageFrame (machine-produced user messages)", () => {
  it("labels goal rounds", () => {
    expect(userMessageFrame("<goal_round>\nObjective: \"x\"\nRound: 1/256")).toBe("goal round");
  });

  it("labels scheduled reminders", () => {
    expect(
      userMessageFrame("[SCHEDULE REMINDER] This is a scheduled message from the user…"),
    ).toBe("scheduled reminder");
  });

  it("labels late answers", () => {
    expect(userMessageFrame("[answer_to_pending_question] question: q\nanswer: a")).toBe(
      "late answer",
    );
  });

  it("labels compaction summaries", () => {
    expect(userMessageFrame("[conversation summary] the session so far")).toBe(
      "compacted history",
    );
  });

  it("leaves real human typing unlabeled", () => {
    expect(userMessageFrame("hello, please continue")).toBeNull();
    expect(userMessageFrame("")).toBeNull();
  });
});
