import { useEffect, useState } from "react";
import { api } from "../api";
import type { GoalSnapshotView } from "../types";
import type { useChat } from "../useChat";

type ChatState = ReturnType<typeof useChat>["state"];

/**
 * The composer chip for the session goal (dsh goal chip, design Phase C):
 * renders while a goal is live, showing phase + round progress; the click
 * action pauses the goal via the host `/goal pause` command (which also
 * aborts a running round — the human control affordance).
 */
export function GoalChip({ state }: { state: ChatState }) {
  const [goal, setGoal] = useState<GoalSnapshotView | null>(null);
  const sessionId = state.sessionId;
  const busy = state.busy;

  useEffect(() => {
    if (!sessionId) {
      setGoal(null);
      return;
    }
    let cancelled = false;
    void api
      .goal(sessionId)
      .then((snapshot) => {
        if (!cancelled) setGoal(snapshot.goalId ? snapshot : null);
      })
      .catch(() => {
        if (!cancelled) setGoal(null);
      });
    return () => {
      cancelled = true;
    };
  }, [sessionId, busy]);

  if (!sessionId || !goal) return null;
  const paused = goal.phase === "paused" || goal.phase === "blocked";
  const label = `goal ${goal.phase} · round ${goal.roundsStarted}/${
    goal.maxRounds ?? "?"
  }`;
  const title = (goal.objective ?? "") + (paused ? " — click to resume" : " — click to pause");
  return (
    <button
      id="goal-chip"
      type="button"
      className={"goal-phase-" + (goal.phase ?? "active")}
      title={title}
      onClick={() => {
        const action = paused ? "resume" : "pause";
        void api
          .runCommand("goal", { session: sessionId, args: [action] })
          .then(() => setGoal(null))
          .catch(() => {});
      }}
    >
      ⛳ {label}
    </button>
  );
}
