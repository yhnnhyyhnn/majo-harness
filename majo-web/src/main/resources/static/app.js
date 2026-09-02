"use strict";

const $ = (id) => document.getElementById(id);
const listEl = $("session-list");
const titleEl = $("current-title");
const convEl = $("conversation");
const formEl = $("composer");
const inputEl = $("input");
const sendBtn = $("send");
const busyEl = $("busy");
const statusEl = $("status");
const bannerEl = $("banner");
const emptyHintEl = $("empty-hint");

let currentSessionId = null;
let typingRow = null;

const esc = (s) =>
  String(s ?? "").replace(/[&<>"']/g, (c) =>
    ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

function showBanner(message) {
  bannerEl.hidden = false;
  bannerEl.textContent = message;
  const retry = document.createElement("button");
  retry.type = "button";
  retry.textContent = "Retry";
  retry.addEventListener("click", () => {
    bannerEl.hidden = true;
    statusEl.className = "";
    statusEl.textContent = "connecting…";
    refresh().catch(() => {});
  });
  bannerEl.appendChild(retry);
}

function hideEmptyHint() {
  emptyHintEl.style.display = "none";
}

function refreshEmptyHint() {
  emptyHintEl.style.display =
    convEl.children.length === 0 ? "block" : "none";
}

function startTyping() {
  stopTyping();
  typingRow = document.createElement("li");
  typingRow.className = "tool-line";
  typingRow.textContent = "…";
  convEl.appendChild(typingRow);
  convEl.scrollTop = convEl.scrollHeight;
}

function stopTyping() {
  if (typingRow) {
    typingRow.remove();
    typingRow = null;
  }
}

async function api(path, options) {
  const response = await fetch(path, options);
  let body;
  try {
    body = await response.json();
  } catch {
    throw new Error("bad response from server (HTTP " + response.status + ")");
  }
  if (!response.ok) throw new Error(body.error || "HTTP " + response.status);
  return body;
}

function shortId(id) {
  return id.length > 8 ? id.slice(0, 8) : id;
}

function bubble(text, className) {
  const li = document.createElement("li");
  li.className = "message " + (className || "");
  const div = document.createElement("div");
  div.className = "bubble";
  div.textContent = text;
  li.appendChild(div);
  convEl.appendChild(li);
}

function metaLine(text) {
  const li = document.createElement("li");
  li.className = "meta-line";
  li.textContent = text;
  convEl.appendChild(li);
}

function toolLine(html) {
  const li = document.createElement("li");
  li.className = "tool-line";
  const chip = document.createElement("span");
  chip.className = "chip";
  chip.innerHTML = html;
  li.appendChild(chip);
  convEl.appendChild(li);
}

// One assistant step is drawn as tool chips; final text is a plain bubble.
function renderEvents(events) {
  convEl.replaceChildren();
  for (const event of events) {
    switch (event.kind) {
      case "TURN_START":
      case "TURN_END":
        break;
      case "USER_MESSAGE":
        bubble(event.content, "user");
        break;
      case "ASSISTANT_MESSAGE":
        if (event.toolCalls && event.toolCalls.length) {
          for (const call of event.toolCalls) {
            metaLine("assistant requested tool " + call.name);
            toolLine(esc(call.arguments || "{}"));
          }
        } else if (event.content != null) {
          bubble(event.content);
        }
        break;
      case "TOOL_RESULT":
        toolLine('<span class="dot ' + (event.ok ? "ok" : "err") + '"></span>'
          + esc(event.toolName) + " → " + esc(event.content ?? ""));
        break;
      case "REQUEST_HEADER":
        metaLine("model " + esc(event.model) + " · tools ["
          + esc((event.toolNames || []).join(", ")) + "]");
        break;
    }
  }
  refreshEmptyHint();
  convEl.scrollTop = convEl.scrollHeight;
}

async function loadSessions() {
  const data = await api("/api/sessions");
  listEl.replaceChildren();
  const sessions = (data.sessions || []).slice().reverse(); // newest first
  if (!sessions.length) {
    const empty = document.createElement("div");
    empty.className = "meta";
    empty.textContent = "no sessions yet";
    listEl.appendChild(empty);
  }
  for (const session of sessions) {
    const button = document.createElement("button");
    button.className = "session" + (session.id === currentSessionId ? " active" : "");
    button.type = "button";
    const t = document.createElement("span");
    t.className = "title";
    t.textContent = session.title || "Untitled " + shortId(session.id);
    const m = document.createElement("span");
    m.className = "meta";
    m.textContent = session.eventCount + " events";
    button.append(t, m);
    button.addEventListener("click", () => selectSession(session.id));
    listEl.appendChild(button);
  }
}

async function selectSession(sessionId) {
  currentSessionId = sessionId;
  const data = await api("/api/sessions/" + encodeURIComponent(sessionId));
  titleEl.textContent = data.title || "Untitled " + shortId(sessionId);
  renderEvents(data.events);
  await loadSessions();
}

function setBusy(busy) {
  busyEl.hidden = !busy;
  sendBtn.disabled = busy;
  inputEl.disabled = busy;
  if (busy) startTyping();
}

async function sendTask() {
  const task = inputEl.value.trim();
  if (!task) return;
  setBusy(true);
  try {
    const data = await api("/api/turn", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ sessionId: currentSessionId, task }),
    });
    if (currentSessionId && currentSessionId !== data.sessionId) {
      throw new Error("session changed mid-turn");
    }
    currentSessionId = data.sessionId;
    titleEl.textContent = "…";
    renderEvents(data.events);
    hideEmptyHint();
    await loadSessions();
    titleEl.textContent = "New chat";
    inputEl.value = "";
  } catch (error) {
    bubble("error: " + error.message, "user");
  } finally {
    stopTyping();
    setBusy(false);
    inputEl.focus();
  }
}

async function refresh() {
  try {
    await loadSessions();
    statusEl.textContent = "online";
    statusEl.className = "online";
    hideEmptyHint();
    const sessions = await api("/api/sessions");
    const all = sessions.sessions || [];
    if (!currentSessionId && all.length) {
      currentSessionId = all[all.length - 1].id;
    }
    if (currentSessionId) {
      const detail = await api("/api/sessions/" + encodeURIComponent(currentSessionId));
      titleEl.textContent = detail.title || "New chat";
      renderEvents(detail.events);
    } else {
      titleEl.textContent = "New chat";
      renderEvents([]);
    }
  } catch (error) {
    statusEl.textContent = "offline — cannot reach the harness backend";
    statusEl.className = "error";
    showBanner("Cannot reach the harness backend: " + error.message);
    throw error;
  }
}

formEl.addEventListener("submit", (event) => {
  event.preventDefault();
  sendTask();
});

inputEl.addEventListener("keydown", (event) => {
  if (event.key === "Enter" && !event.shiftKey) {
    event.preventDefault();
    sendTask();
  }
});

$("new-chat").addEventListener("click", () => {
  currentSessionId = null;
  titleEl.textContent = "New chat";
  renderEvents([]);
  loadSessions().catch(() => {});
  inputEl.focus();
});

refresh().catch(() => {}); // banner + status already describe the failure
