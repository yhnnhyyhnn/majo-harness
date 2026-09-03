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

  const splitCells = (line: string): string[] =>
    line
      .replace(/^\s*\|/, "")
      .replace(/\|\s*$/, "")
      .split("|")
      .map((cell) => cell.trim());

  const isSeparator = (line: string | undefined): boolean =>
    !!line && /^\|?\s*:?-{1,}:?\s*(\|\s*:?-{1,}:?\s*)*\|?$/.test(line.trim());

  // GFM pipe table: header row + separator row, then body rows while pipes last
  const tableHtml = (lines: string[], start: number): { html: string; next: number } => {
    const header = splitCells(lines[start]);
    let row = start + 2;
    const body: string[][] = [];
    while (row < lines.length && lines[row].includes("|")) {
      body.push(splitCells(lines[row]));
      row++;
    }
    const cells = (values: string[], tag: string) =>
      values
        .map((cell) => `<${tag}>${inline(cell)}</${tag}>`)
        .join("");
    const head = `<thead><tr>${cells(header, "th")}</tr></thead>`;
    const rows = body
      .map((values) => `<tr>${cells(values, "td")}</tr>`)
      .join("");
    return { html: `<table>${head}<tbody>${rows}</tbody></table>`, next: row };
  };

  let out = "";
  const parts = String(text ?? "").split(/```/);
  for (let i = 0; i < parts.length; i++) {
    if (i % 2 === 1) {
      const langMatch = parts[i].match(/^([A-Za-z0-9_+.-]+)\n/);
      const lang = langMatch ? langMatch[1] : "";
      const body = langMatch ? parts[i].slice(langMatch[0].length) : parts[i];
      const code = escapeHtml(body).replace(/\n$/, "");
      const figure = lang ? `<figure class="code-block"><figcaption>${escapeHtml(lang)}</figcaption>` : "";
      out += figure + '<pre class="code"><code>' + code + "</code></pre>" + (lang ? "</figure>" : "");
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
    for (let li = 0; li < lines.length; li++) {
      const raw = lines[li];
      if (raw.includes("|") && isSeparator(lines[li + 1])) {
        flushList();
        const table = tableHtml(lines, li);
        out += table.html;
        li = table.next - 1;
        continue;
      }
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
  const content = event.content ?? "";
  return (
    <div className="bubble">
      {content}
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

function FeedbackButtons({
  seq,
  rate,
  onRate,
}: {
  seq: number;
  rate?: "up" | "down" | null;
  onRate?: (seq: number, value: "up" | "down" | null) => void;
}) {
  if (!onRate || typeof seq !== "number" || seq <= 0) return null;
  return (
    <span className="feedback">
      <button
        type="button"
        title="good answer"
        className={rate === "up" ? "on" : undefined}
        onClick={() => onRate(seq, rate === "up" ? null : "up")}
      >
        👍
      </button>
      <button
        type="button"
        title="bad answer"
        className={rate === "down" ? "on" : undefined}
        onClick={() => onRate(seq, rate === "down" ? null : "down")}
      >
        👎
      </button>
    </span>
  );
}

function AssistantRenderer({
  event,
  rate,
  onRate,
}: {
  event: { content?: string | null; toolCalls?: ToolCallFrame[]; seq?: number };
  rate?: "up" | "down" | null;
  onRate?: (seq: number, value: "up" | "down" | null) => void;
}) {
  if (event.toolCalls && event.toolCalls.length) {
    return <ToolCallsRenderer event={event} />;
  }
  const content = event.content ?? "";
  return (
    <div className="msg">
      <MarkdownText text={content} />
      {(content || onRate) && (
        <span className="icon-actions">
          {content && (
            <button type="button" title="copy" onClick={() => void copyText(content)}>
              ⧉ copy
            </button>
          )}
          <FeedbackButtons seq={event.seq ?? 0} rate={rate} onRate={onRate} />
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

function GenericResultCard({
  toolName,
  ok,
  text,
  data,
  onOpenSession,
}: {
  toolName?: string;
  ok?: boolean;
  text: string;
  data?: Record<string, unknown>;
  onOpenSession?: (id: string) => void;
}) {
  const exit = typeof data?.exitCode === "number" ? data.exitCode : null;
  const child = typeof data?.childSessionId === "string" ? data.childSessionId : null;
  return (
    <div className="tool-block result">
      <span className={"dot " + (ok ? "ok" : "err")} />
      <span className="tool-name">{toolName}</span>
      <span className={"badge " + (ok ? "ok" : "err")}>{ok ? "ok" : "error"}</span>
      {exit !== null && <span className={"badge exit-" + (ok ? "ok" : "err")}>exit {exit}</span>}
      {child && onOpenSession && (
        <button
          type="button"
          className="mini link"
          title="child session"
          onClick={() => onOpenSession(child)}
        >
          ↪ child session
        </button>
      )}
      <span className="spacer" />
      <button type="button" className="mini" title="copy" onClick={() => void copyText(text)}>
        ⧉
      </button>
      {text && <pre className="code result-text">{text}</pre>}
    </div>
  );
}

interface SearchHit {
  title: string;
  url: string;
  snippet: string;
}

// web_search results arrive as one deterministic text block:
//   external web results (untrusted):
//   - Title
//     https://…
//     snippet words…
const parseSearchHits = (text: string): SearchHit[] | null => {
  if (!/external web results/i.test(text)) return null;
  const hits: SearchHit[] = [];
  let current: SearchHit | null = null;
  for (const raw of text.split("\n").slice(1)) {
    const bullet = raw.match(/^\s*-\s+(.*)$/);
    if (bullet) {
      current = { title: bullet[1], url: "", snippet: "" };
      hits.push(current);
      continue;
    }
    const line = raw.trim();
    if (!current || !line) continue;
    if (!current.url && /^https?:\/\//.test(line)) {
      current.url = line;
    } else {
      current.snippet = current.snippet ? current.snippet + " " + line : line;
    }
  }
  return hits.length ? hits : null;
};

// Structured path: the seam now ships data.hits; text parsing stays as the
// fallback for logs recorded before this milestone.
const dataHits = (data: Record<string, unknown> | undefined): SearchHit[] | null => {
  if (!data || !Array.isArray(data.hits)) return null;
  const hits: SearchHit[] = [];
  for (const item of data.hits) {
    if (typeof item !== "object" || item === null) continue;
    const record = item as Record<string, unknown>;
    if (typeof record.title !== "string") continue;
    hits.push({
      title: record.title,
      url: typeof record.url === "string" ? record.url : "",
      snippet: typeof record.snippet === "string" ? record.snippet : "",
    });
  }
  return hits.length ? hits : null;
};

function SearchResultsCard({
  toolName,
  text,
  data,
}: {
  toolName?: string;
  text: string;
  data?: Record<string, unknown>;
}) {
  const hits = dataHits(data) ?? parseSearchHits(text);
  return (
    <div className="tool-block result">
      <span className="dot ok" />
      <span className="tool-name">{toolName}</span>
      <span className="badge ext">external · untrusted</span>
      <span className="spacer" />
      <button type="button" className="mini" title="copy" onClick={() => void copyText(text)}>
        ⧉
      </button>
      {hits ? (
        <ol className="search-list">
          {hits.map((hit, index) => (
            <li key={index}>
              {hit.url ? (
                <a href={hit.url} target="_blank" rel="noopener noreferrer">
                  {hit.title}
                </a>
              ) : (
                <span className="result-title">{hit.title}</span>
              )}
              {hit.snippet && <div className="meta">{hit.snippet}</div>}
            </li>
          ))}
        </ol>
      ) : (
        text && <pre className="code result-text">{text}</pre>
      )}
    </div>
  );
}

const asText = (value: unknown): string =>
  typeof value === "string" ? value : value == null ? "" : String(value);

function ShellResultCard({
  toolName,
  ok,
  data,
}: {
  toolName?: string;
  ok?: boolean;
  data: Record<string, unknown>;
}) {
  const exit = typeof data.exitCode === "number" ? data.exitCode : null;
  const stdout = asText(data.stdout).replace(/\s+$/, "");
  const stderr = asText(data.stderr).replace(/\s+$/, "");
  const copyBody =
    (stdout ? stdout + "\n" : "") + (stderr ? "[stderr]\n" + stderr : "").trimEnd();
  return (
    <div className={"tool-block result card-shell"}>
      <span className={"dot " + (ok ? "ok" : "err")} />
      <span className="tool-name">{toolName}</span>
      <span className={"badge " + (ok ? "ok" : "err")}>{ok ? "ok" : "error"}</span>
      {exit !== null && <span className={"badge exit-" + (ok ? "ok" : "err")}>exit {exit}</span>}
      <span className="spacer" />
      <button
        type="button"
        className="mini"
        title="copy"
        onClick={() => void copyText(copyBody || "(no output)")}
      >
        ⧉
      </button>
      <div className="card-body">
        {stdout ? (
          <>
            <div className="seg-label">stdout</div>
            <pre className="code seg">{stdout}</pre>
          </>
        ) : (
          ok && <div className="meta">(no output)</div>
        )}
        {!ok && stderr && (
          <>
            <div className="seg-label stderr">stderr</div>
            <pre className="code seg stderr">{stderr}</pre>
          </>
        )}
      </div>
    </div>
  );
}

function FileResultCard({
  toolName,
  ok,
  data,
  text,
}: {
  toolName?: string;
  ok?: boolean;
  data: Record<string, unknown>;
  text: string;
}) {
  const path = asText(data.path);
  const lines = text ? text.split("\n").length : 0;
  return (
    <div className="tool-block result">
      <span className={"dot " + (ok ? "ok" : "err")} />
      <span className="tool-name">{toolName}</span>
      {path && <code className="file-path">{path}</code>}
      {ok && lines > 0 && <span className="meta">{lines} lines</span>}
      <span className="spacer" />
      <button type="button" className="mini" title="copy" onClick={() => void copyText(text)}>
        ⧉
      </button>
      {text && <pre className="code result-text">{text}</pre>}
    </div>
  );
}

function ToolResultRenderer({
  event,
  openSession,
}: {
  event: { toolName?: string; ok?: boolean; content?: string | null; data?: Record<string, unknown> };
  openSession?: (id: string) => void;
}) {
  const text = event.content ?? "";
  const toolName = event.toolName;
  const data = event.data;
  if (toolName === "web_search") {
    return <SearchResultsCard toolName={toolName} text={text} data={data} />;
  }
  if (
    data &&
    (toolName === "run_shell" || toolName === "run_command") &&
    typeof data.exitCode === "number"
  ) {
    return <ShellResultCard toolName={toolName} ok={event.ok} data={data} />;
  }
  if (data && toolName === "read_file" && typeof data.path === "string") {
    return <FileResultCard toolName={toolName} ok={event.ok} data={data} text={text} />;
  }
  return (
    <GenericResultCard
      toolName={toolName}
      ok={event.ok}
      text={text}
      data={data}
      onOpenSession={openSession}
    />
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
    context.renderMessage("ASSISTANT_MESSAGE", (props) => (
      <AssistantRenderer event={props.event} rate={props.rate} onRate={props.onRate} />
    ));
    context.renderMessage("TOOL_RESULT", (props) => (
      <ToolResultRenderer event={props.event} openSession={props.openSession} />
    ));
    context.renderMessage("REQUEST_HEADER", (props) => (
      <RequestHeaderRenderer event={props.event} />
    ));
  },
};
