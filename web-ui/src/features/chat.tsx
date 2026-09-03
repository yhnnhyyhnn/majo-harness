import type { Feature } from "../slots";
import type { ToolCallFrame } from "../types";

// chat feature: message renderers for every event kind (typed slot fillers).

const escapeHtml = (value: unknown): string =>
  String(value ?? "").replace(/[&<>"']/g, (c) => {
    const entities: Record<string, string> = {
      "&": "&amp;",
      "<": "&lt;",
      ">": "&gt;",
      '"': "&quot;",
      "'": "&#39;",
    };
    return entities[c] ?? c;
  });

const prettyJson = (raw: string): string => {
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
};

function MarkdownText({ text }: { text: string }) {
  const inline = (input: string): string =>
    input
      .replace(/`([^`]+)`/g, "<code>$1</code>")
      .replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>")
      .replace(/\*([^*]+)\*/g, "<em>$1</em>")
      .replace(
        /\[([^\]]+)\]\((https?:[^)\s]+)\)/g,
        '<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>'
      );

  let out = "";
  const parts = String(text ?? "").split(/```/);
  for (let i = 0; i < parts.length; i++) {
    if (i % 2 === 1) {
      const code = escapeHtml(parts[i]).replace(/^[a-zA-Z0-9_-]+\n/, "").replace(/\n$/, "");
      out += '<pre class="code"><code>' + code + "</code></pre>";
      continue;
    }
    const lines = escapeHtml(parts[i]).split("\n");
    let list: string | null = null;
    const flushList = () => {
      if (list) {
        out += list + "</ul>";
        list = null;
      }
    };
    for (const raw of lines) {
      const heading = raw.match(/^(#{1,4})\s+(.*)$/);
      const bullet = raw.match(/^\s*[-*]\s+(.*)$/);
      const ordered = raw.match(/^\s*\d+[.)]\s+(.*)$/);
      const quote = raw.match(/^\s*>\s?(.*)$/);
      if (/^\s*---+\s*$/.test(raw)) {
        flushList();
        out += "<hr>";
      } else if (heading) {
        flushList();
        out += `<h${heading[1].length}>${inline(heading[2])}</h${heading[1].length}>`;
      } else if (bullet || ordered) {
        list = list ?? "<ul>";
        list += `<li>${inline((bullet ?? ordered)?.[1] ?? "")}</li>`;
      } else if (quote) {
        flushList();
        out += "<blockquote>" + inline(quote[1]) + "</blockquote>";
      } else if (raw.trim().length === 0) {
        flushList();
        out += "<br>";
      } else {
        flushList();
        out += inline(raw);
      }
    }
    flushList();
  }
  return <div className="bubble rich" dangerouslySetInnerHTML={{ __html: out }} />;
}

export async function copyText(text: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(text);
  } catch {
    const area = document.createElement("textarea");
    area.value = text;
    document.body.appendChild(area);
    area.select();
    document.execCommand("copy");
    area.remove();
  }
}

function UserRenderer({ event }: { event: { content?: string | null } }) {
  return <div className="bubble">{event.content ?? ""}</div>;
}

function AssistantRenderer({ event }: { event: { content?: string | null; toolCalls?: ToolCallFrame[] } }) {
  if (event.toolCalls && event.toolCalls.length) {
    return <ToolCallsRenderer event={event} />;
  }
  const content = event.content ?? "";
  return (
    <div className="msg">
      <MarkdownText text={content} />
      {content && (
        <span className="icon-actions">
          <button type="button" title="copy" onClick={() => void copyText(content)}>
            ⧉ copy
          </button>
        </span>
      )}
    </div>
  );
}

function ToolCallsRenderer({ event }: { event: { toolCalls?: ToolCallFrame[] } }) {
  const calls = event.toolCalls ?? [];
  return (
    <div className="tool-block">
      <div className="tool-head">
        <span className="tool-name">tool · {calls.map((c) => c.name).join(", ")}</span>
      </div>
      {calls.map((call, index) => (
        <pre className="code args" key={call.name + index}>
          {prettyJson(call.arguments || "{}")}
        </pre>
      ))}
    </div>
  );
}

function ToolResultRenderer({ event }: { event: { toolName?: string; ok?: boolean; content?: string | null } }) {
  return (
    <div className="tool-block result">
      <span className={"dot " + (event.ok ? "ok" : "err")} />
      <span className="tool-name">{event.toolName}</span>
      <span className="result-text">{event.content ?? ""}</span>
    </div>
  );
}

function RequestHeaderRenderer({ event }: { event: { model?: string; toolNames?: string[] } }) {
  return (
    <>
      model {event.model} · tools [{(event.toolNames || []).join(", ")}]
    </>
  );
}

function NoneRenderer() {
  return null;
}

export const chatFeature: Feature = {
  id: "chat",
  register(context) {
    context.renderMessage("TURN_START", NoneRenderer);
    context.renderMessage("TURN_END", NoneRenderer);
    context.renderMessage("USER_MESSAGE", (props) => <UserRenderer event={props.event} />);
    context.renderMessage("ASSISTANT_MESSAGE", (props) => <AssistantRenderer event={props.event} />);
    context.renderMessage("TOOL_RESULT", (props) => <ToolResultRenderer event={props.event} />);
    context.renderMessage("REQUEST_HEADER", (props) => (
      <RequestHeaderRenderer event={props.event} />
    ));
  },
};
