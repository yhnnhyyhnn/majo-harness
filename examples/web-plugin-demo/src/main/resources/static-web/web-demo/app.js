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

document.getElementById("refresh").addEventListener("click", () => void refresh());
void refresh();
