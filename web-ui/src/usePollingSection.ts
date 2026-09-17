import { useEffect, useRef } from "react";

/**
 * Shared polling pattern for collapsible sidebar sections (Plugins, Subagents,
 * Skills): fetch once when opened, then keep the data fresh on an interval
 * until the section closes. `onInitialError` only sees the first load's
 * failure (panels use it to leave "loading…" and show an empty state);
 * steady-state poll failures are swallowed so a flaky backend never clears
 * rendered content.
 */
export function usePollingSection(
  open: boolean,
  load: () => Promise<void>,
  intervalMs: number,
  onInitialError?: (error: unknown) => void
): void {
  const loadRef = useRef(load);
  loadRef.current = load;
  const errorRef = useRef(onInitialError);
  errorRef.current = onInitialError;
  useEffect(() => {
    if (!open) return;
    void loadRef.current().catch((error) => errorRef.current?.(error));
    const timer = window.setInterval(() => {
      void loadRef.current().catch(() => {});
    }, intervalMs);
    return () => window.clearInterval(timer);
  }, [open, intervalMs]);
}
