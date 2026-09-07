document.getElementById("refresh").addEventListener("click", () => void refresh());
void refresh();

async function refresh() {
  const out = document.getElementById("out");
  try {
    const info = await fetch("/api/info").then((r) => r.json());
    out.textContent = JSON.stringify(info, null, 2);
  } catch (error) {
    out.textContent = "error: " + String(error);
  }
}

// postMessage bridge example: drive the host (see docs/plugin-development).
document.getElementById("notify")?.addEventListener?.("click", () =>
  window.parent?.postMessage(
    { source: "majo-plugin", type: "flash", message: "hello from __name__" },
    "*"
  )
);
