import React from "react";
import { createRoot } from "react-dom/client";
import "./app.css";
import App from "./App";

const root = document.getElementById("root");
if (!root) {
  throw new Error("#root not found");
}

// Diagnostic surface: any uncaught render/recovery error is written straight
// into the page instead of vanishing into the console.
function surfaceError(message: string) {
  try {
    const badge = document.createElement("pre");
    badge.id = "boot-error";
    badge.style.cssText =
      "position:fixed;inset:12px;z-index:9999;background:#2b1114;color:#ffb3b3;border:1px solid #7a2b2b;" +
      "border-radius:10px;padding:14px;white-space:pre-wrap;font:12px/1.5 monospace;overflow:auto;max-height:70vh";
    badge.textContent = message;
    document.body.appendChild(badge);
  } catch {
    // ignore — nothing else we can do here
  }
}

window.addEventListener("error", (event) => surfaceError("window error: " + event.message));
window.addEventListener("unhandledrejection", (event) =>
  surfaceError("unhandled rejection: " + String((event.reason as Error | undefined)?.message ?? event.reason))
);

const reactRoot = createRoot(root, {
  onRecoverableError(error) {
    surfaceError("recoverable error: " + (error instanceof Error ? error.message : String(error)));
  },
});
reactRoot.render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);
