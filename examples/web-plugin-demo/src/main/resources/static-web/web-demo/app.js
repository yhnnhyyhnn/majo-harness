// Hosted frontend of the web-demo plugin: same-origin, so it can call the
// majo JSON API directly (the same typed endpoints the chat UI uses).
const out = document.getElementById("out");
const model = document.getElementById("model");

async function refresh() {
  try {
    const sessions = await fetch("/api/sessions").then((r) => r.json());
    out.textContent = JSON.stringify(sessions, null, 2);
  } catch (error) {
    out.textContent = "error: " + String(error);
  }
  try {
    const state = await fetch("/api/settings/model").then((r) => r.json());
    model.textContent = JSON.stringify(state, null, 2);
  } catch (error) {
    model.textContent = "error: " + String(error);
  }
}

const send = (type, payload = {}) =>
  window.parent?.postMessage({ source: "majo-plugin", type, ...payload }, "*");

document.getElementById("send-task").addEventListener("click", () =>
  send("sendTask", { task: "2+2" })
);
document.getElementById("open-session").addEventListener("click", async () => {
  const sessions = await fetch("/api/sessions").then((r) => r.json());
  const list = sessions.sessions || [];
  if (list.length) send("openSession", { sessionId: list[list.length - 1].id });
  else send("flash", { message: "no sessions yet" });
});
document.getElementById("new-chat").addEventListener("click", () => send("newChat"));
document.getElementById("flash").addEventListener("click", () =>
  send("flash", { message: "hello from web-demo plugin" })
);
document.getElementById("close").addEventListener("click", () => send("close"));

document.getElementById("refresh").addEventListener("click", () => void refresh());
void refresh();
