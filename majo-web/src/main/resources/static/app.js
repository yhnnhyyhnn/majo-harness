"use strict";

const listEl = document.getElementById("session-list");
const titleEl = document.getElementById("current-title");
const convEl = document.getElementById("conversation");
const formEl = document.getElementById("composer");
const inputEl = document.getElementById("input");
const sendBtn = document.getElementById("send");
const busyEl = document.getElementById("busy");

let currentSessionId = null;

const esc = (s) =>
  String(s ?? "").replace(/[&<>"']/g, (c) =>
    ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

async function api(path, options) {
  const response = await fetch(path, options);
  const body = await response.json();
  if (!response.ok) throw new Error(body.error || `HTTP ${response.status}`);
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

function metaLine(html) {
  const li = document.createElement("li");
  li.className = "meta-line";
  li.innerHTML = html;
  convEl.appendChild(li);
}

function toolLine(html) {
  const li = document.createElement("li");
  li.className = "tool-line";
  li.innerHTML = html;
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
            metaLine('assistant requested tool <b style="color:var(--text)">'
              + esc(call.name) + "</b>");
            toolLine('<span class="chip">' + esc(call.arguments || "{}") + "</span>");
          }
        } else if (event.content != null) {
          bubble(event.content);
        }
        break;
      case "TOOL_RESULT":
        toolLine('<span class="chip"><span class="dot ' + (event.ok ? "ok" : "err") + '"></span>'
          + esc(event.toolName) + " → " + esc(event.content ?? "") + "</span>");
        break;
      case "REQUEST_HEADER":
        metaLine("model " + esc(event.model) + " · tools [" + esc((event.toolNames || []).join(", ")) + "]");
        break;
    }
  }
  convEl.scrollTop = convEl.scrollHeight;
}

async function loadSessions() {
  const data = await api("/api/sessions");
  listEl.replaceChildren();
  for (const session of data.sessions) {
    const button = document.createElement("button");
    button.className = "session" + (session.id === currentSessionId ? " active" : "");
    button.type = "button";
    const title = document.createElement("span");
    title.className = "title";
    title.textContent = session.title || "Untitled " + shortId(session.id);
    const meta = document.createElement("span");
    meta.className = "meta";
    meta.textContent = session.eventCount + " events";
    button.append(title, meta);
    button.addEventListener("click", () => selectSession(session.id));
    listEl.appendChild(button);
  }
}

async function selectSession(sessionId) {
  currentSessionId = sessionId;
  const data = await api("/api/sessions/" + encodeURIComponent(sessionId));
  titleEl.textContent = data.title || "Untitled " + shortId(sessionId);
  renderEvents(data.events);
  loadSessions();
}

function setBusy(busy) {
  busyEl.hidden = !busy;
  sendBtn.disabled = busy;
  inputEl.disabled = busy;
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
    loadSessions();
    inputEl.value = "";
  } catch (error) {
    bubble("error: " + error.message, "user");
  } finally {
    setBusy(false);
    inputEl.focus();
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

document.getElementById("new-chat").addEventListener("click", () => {
  currentSessionId = null;
  titleEl.textContent = "—";
  convEl.replaceChildren();
  loadSessions();
  inputEl.focus();
});

loadSessions().catch((error) => {
  bubble("cannot load sessions: " + error.message, "user");
});
