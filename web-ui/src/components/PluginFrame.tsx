import { useEffect, useRef } from "react";
import type { useChat } from "../useChat";

type ChatActions = ReturnType<typeof useChat>["actions"];

/**
 * The plugin view that replaces the main pane: a sandboxed iframe showing a
 * hosted plugin page, plus the postMessage bridge. Hosted pages
 * (source "majo-plugin") may drive the host — flash a notice, start a new
 * chat, close the pane, run a task, or open a session.
 */
export function PluginFrame({
  view,
  actions,
}: {
  view: { name: string; url: string };
  actions: ChatActions;
}) {
  const frameRef = useRef<HTMLIFrameElement | null>(null);

  useEffect(() => {
    const onMessage = (event: MessageEvent) => {
      const frame = frameRef.current;
      if (!frame || event.source !== frame.contentWindow) return;
      const msg = event.data as { source?: string; type?: string } & Record<string, unknown>;
      if (!msg || msg.source !== "majo-plugin") return;
      switch (msg.type) {
        case "flash":
          if (typeof msg.message === "string") actions.setNotice(msg.message);
          break;
        case "newChat":
          actions.newChat();
          break;
        case "close":
          actions.closePlugin();
          break;
        case "sendTask":
          if (typeof msg.task === "string") void actions.runTask(msg.task);
          break;
        case "openSession":
          if (typeof msg.sessionId === "string") void actions.selectSession(msg.sessionId);
          break;
      }
    };
    window.addEventListener("message", onMessage);
    return () => window.removeEventListener("message", onMessage);
  }, [actions]);

  return (
    <div id="plugin-pane">
      <div id="plugin-toolbar">
        <button type="button" onClick={actions.closePlugin}>
          ← back to chat
        </button>
        <strong>{view.name}</strong>
      </div>
      <iframe
        ref={frameRef}
        title={view.name}
        src={view.url}
        className="plugin-frame"
        sandbox="allow-scripts allow-same-origin allow-forms allow-popups"
      />
    </div>
  );
}
