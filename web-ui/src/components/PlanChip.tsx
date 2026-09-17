import { useEffect, useState } from "react";
import { api } from "../api";
import type { useChat } from "../useChat";

type ChatState = ReturnType<typeof useChat>["state"];

/**
 * The composer chip for plan mode (dsh PlanModeControl): renders while the
 * session's plan is active; clicking it runs the host `plan off` command.
 * The host `/plan` command itself auto-registers in the command palette.
 */
export function PlanChip({ state }: { state: ChatState }) {
  const [active, setActive] = useState(false);
  const [plan, setPlan] = useState<string | null>(null);
  const sessionId = state.sessionId;
  const busy = state.busy;

  useEffect(() => {
    if (!sessionId) {
      setActive(false);
      setPlan(null);
      return;
    }
    let cancelled = false;
    void api
      .plan(sessionId)
      .then((snapshot) => {
        if (!cancelled) {
          setActive(snapshot.active);
          setPlan(snapshot.plan ?? null);
        }
      })
      .catch(() => {
        if (!cancelled) setActive(false);
      });
    return () => {
      cancelled = true;
    };
  }, [sessionId, busy]);

  if (!sessionId || !active) return null;
  return (
    <button
      id="plan-chip"
      type="button"
      title={plan ? "plan mode — click to leave: " + plan : "plan mode — click to leave"}
      onClick={() => {
        void api
          .runCommand("plan", { session: sessionId, args: ["off"] })
          .then(() => {
            setActive(false);
            setPlan(null);
          })
          .catch(() => {});
      }}
    >
      📋 plan mode · ✕
    </button>
  );
}
