/* Hermes Mobile — a phone-first client for a self-hosted Hermes Agent API server.
 *
 * Transport: POST {server}/v1/chat/completions with stream:true. The full transcript is sent
 * each turn (the Open WebUI-style path the server supports without a session header); the
 * server derives a stable session from the system prompt + first user message, so each local
 * conversation carries a unique tag in its system prompt to keep sessions apart.
 *
 * Stream frames (confirmed against gateway/platforms/api_server_openai_routes.py):
 *   data: {chat.completion.chunk}               delta.content / delta.reasoning_content / finish_reason
 *   event: hermes.tool.progress  data: {tool, emoji, label, toolCallId, status: running|completed}
 *   event: hermes.status         data: {kind, text}
 *   event: approval.request      data: {command, description, choices[], run_id, ...}
 *   data: [DONE]
 *   ": keepalive" comments every 10s of silence.
 * Approvals answer via POST /v1/runs/{completion id}/approval {choice}.
 */
(() => {
  "use strict";

  // ---------- storage (every access guarded: private mode / blocked storage) ----------
  const store = {
    get(key, fallback) {
      try { const v = localStorage.getItem(key); return v == null ? fallback : JSON.parse(v); }
      catch { return fallback; }
    },
    set(key, value) {
      try { localStorage.setItem(key, JSON.stringify(value)); return true; }
      catch { return false; }
    },
  };

  const DEFAULT_SETTINGS = {
    baseUrl: "",
    apiKey: "",
    model: "",
    demo: false,
    theme: "system",
    showReasoning: true,
    instructions: "The user is on a phone. Prefer short, scannable answers unless asked for detail.",
  };
  const settings = Object.assign({}, DEFAULT_SETTINGS, store.get("hm.settings", {}));
  let conversations = store.get("hm.conversations", []);
  if (!Array.isArray(conversations)) conversations = [];
  let currentId = store.get("hm.current", null);

  // A turn left "streaming" by a closed tab is finished as interrupted.
  for (const c of conversations) for (const m of c.messages || []) {
    if (m.role === "assistant" && m.status === "streaming") {
      m.status = "stopped";
      for (const t of m.tools || []) if (t.status === "running") t.status = "unknown";
    }
  }

  const saveSettings = () => store.set("hm.settings", settings);
  let saveTimer = 0;
  const saveConversations = (now) => {
    clearTimeout(saveTimer);
    const write = () => {
      if (!store.set("hm.conversations", conversations)) toast("Couldn't save. Storage is full or blocked.");
      store.set("hm.current", currentId);
    };
    if (now) write(); else saveTimer = setTimeout(write, 400);
  };

  // ---------- helpers ----------
  const $ = (sel, root = document) => root.querySelector(sel);
  const uid = () => (crypto.randomUUID ? crypto.randomUUID() : Date.now().toString(36) + Math.random().toString(36).slice(2));
  const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  const trimSlash = (u) => String(u || "").trim().replace(/\/+$/, "");
  const isConnected = () => settings.demo || (settings.baseUrl && settings.apiKey);

  function toast(text, ms = 2600) {
    const el = $("#toast");
    el.textContent = text;
    el.hidden = false;
    clearTimeout(toast.t);
    toast.t = setTimeout(() => { el.hidden = true; }, ms);
  }

  function relTime(ts) {
    const d = (Date.now() - ts) / 1000;
    if (d < 60) return "Just now";
    if (d < 3600) return `${Math.floor(d / 60)} min ago`;
    if (d < 86400) return `${Math.floor(d / 3600)} h ago`;
    const date = new Date(ts);
    return date.toLocaleDateString(undefined, { month: "short", day: "numeric" });
  }
  function fmtDuration(ms) {
    if (ms == null || !isFinite(ms)) return "";
    if (ms < 1000) return `${Math.max(0, Math.round(ms))} ms`;
    if (ms < 60000) return `${(ms / 1000).toFixed(1)} s`;
    return `${Math.floor(ms / 60000)} m ${Math.round((ms % 60000) / 1000)} s`;
  }

  // ---------- markdown (escape first, then a small safe subset) ----------
  const unesc = (s) => s.replace(/&(amp|lt|gt|quot|#39);/g, (_, e) => ({ amp: "&", lt: "<", gt: ">", quot: '"', "#39": "'" }[e]));
  function safeUrl(url, forImage) {
    const u = unesc(String(url).trim());
    if (/^https?:\/\//i.test(u)) return u;
    if (forImage && /^data:image\/(png|jpe?g|gif|webp);base64,[a-z0-9+/=\s]+$/i.test(u)) return u;
    return null;
  }
  function emphasis(s) {
    s = s.replace(/\*\*([^*\n]+)\*\*/g, "<strong>$1</strong>");
    s = s.replace(/(^|[^*\w])\*([^*\n]+)\*(?!\w)/g, "$1<em>$2</em>");
    s = s.replace(/(^|[^_\w])_([^_\n]+)_(?!\w)/g, "$1<em>$2</em>");
    return s.replace(/~~([^~\n]+)~~/g, "<del>$1</del>");
  }
  function inline(text) {
    // `text` is already HTML-escaped. Every piece of markup this function generates is parked in
    // `tokens` behind a placeholder at once, so later passes never see (or rewrite) generated HTML.
    const tokens = [];
    const park = (html) => { tokens.push(html); return `\u0000${tokens.length - 1}\u0000`; };
    const linkTo = (u, labelHtml) => park(`<a href="${esc(u)}" target="_blank" rel="noopener noreferrer">${labelHtml}</a>`);
    let s = text.replace(/\u0000/g, "");
    s = s.replace(/`([^`\n]+)`/g, (_, c) => park(`<code>${c}</code>`));
    s = s.replace(/!\[([^\]]*)\]\(([^)\s]+)\)/g, (m, alt, url) => {
      const altText = alt.replace(/["<>]/g, "");
      if (safeUrl(url, true) && /^data:/i.test(url.trim())) return park(`<img alt="${altText}" src="${esc(safeUrl(url, true))}">`);
      // Remote images are never auto-loaded (a classic way to leak data out of an agent); link them instead.
      const u = safeUrl(url, false);
      return u ? linkTo(u, `Image: ${altText || "open"}`) : m;
    });
    s = s.replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, (m, label, url) => {
      const u = safeUrl(url, false);
      return u ? linkTo(u, emphasis(label.replace(/\u0000(\d+)\u0000/g, (_, i) => tokens[+i]))) : m;
    });
    s = s.replace(/(^|[\s(])(https?:\/\/[^\s<)\u0000]+[^\s<).,;:!?'"\u0000])/g, (m, pre, url) =>
      pre + linkTo(unesc(url), url));
    s = emphasis(s);
    // Restore (tokens never contain other unresolved placeholders except link labels, handled above).
    return s.replace(/\u0000(\d+)\u0000/g, (_, i) => tokens[+i]);
  }
  function renderMarkdown(src) {
    const lines = esc(src).split("\n");
    const out = [];
    let i = 0;
    let para = [];
    const flush = () => { if (para.length) { out.push(`<p>${inline(para.join("<br>"))}</p>`); para = []; } };
    while (i < lines.length) {
      const line = lines[i];
      const fence = line.match(/^\s*(```|~~~)\s*([\w+#.-]*)/);
      if (fence) {
        flush();
        const lang = fence[2] || "";
        const body = [];
        i++;
        while (i < lines.length && !lines[i].trim().startsWith(fence[1])) body.push(lines[i++]);
        i++; // closing fence (or end of a still-streaming block)
        out.push(`<div class="code"><div class="code-head"><span>${lang || "code"}</span><button type="button" data-copy-code>Copy</button></div><pre><code>${body.join("\n")}</code></pre></div>`);
        continue;
      }
      if (/^\s*$/.test(line)) { flush(); i++; continue; }
      const h = line.match(/^(#{1,4})\s+(.*)$/);
      if (h) { flush(); out.push(`<h${h[1].length}>${inline(h[2])}</h${h[1].length}>`); i++; continue; }
      if (/^\s*([-*_])(\s*\1){2,}\s*$/.test(line)) { flush(); out.push("<hr>"); i++; continue; }
      if (/^&gt;\s?/.test(line)) {
        flush();
        const q = [];
        while (i < lines.length && /^&gt;\s?/.test(lines[i])) q.push(lines[i++].replace(/^&gt;\s?/, ""));
        out.push(`<blockquote>${inline(q.join("<br>"))}</blockquote>`);
        continue;
      }
      if (/^\s*\|.*\|\s*$/.test(line) && i + 1 < lines.length && /^\s*\|?\s*:?-{2,}/.test(lines[i + 1])) {
        flush();
        const cells = (l) => l.trim().replace(/^\||\|$/g, "").split("|").map((c) => inline(c.trim()));
        const head = cells(line);
        i += 2;
        const rows = [];
        while (i < lines.length && /^\s*\|.*\|\s*$/.test(lines[i])) rows.push(cells(lines[i++]));
        out.push(`<table><thead><tr>${head.map((c) => `<th>${c}</th>`).join("")}</tr></thead><tbody>${rows.map((r) => `<tr>${r.map((c) => `<td>${c}</td>`).join("")}</tr>`).join("")}</tbody></table>`);
        continue;
      }
      const li = line.match(/^\s*([-*+]|\d+[.)])\s+(.*)$/);
      if (li) {
        flush();
        const ordered = /\d/.test(li[1]);
        const items = [];
        while (i < lines.length) {
          const m = lines[i].match(/^\s*([-*+]|\d+[.)])\s+(.*)$/);
          if (m && /\d/.test(m[1]) === ordered) { items.push(m[2]); i++; }
          else if (items.length && /^\s{2,}\S/.test(lines[i])) { items[items.length - 1] += "<br>" + lines[i].trim(); i++; }
          else break;
        }
        const tag = ordered ? "ol" : "ul";
        out.push(`<${tag}>${items.map((t) => `<li>${inline(t)}</li>`).join("")}</${tag}>`);
        continue;
      }
      para.push(line);
      i++;
    }
    flush();
    return out.join("");
  }

  // ---------- SSE over fetch (EventSource can't send a bearer header) ----------
  async function* sseEvents(response, signal) {
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buf = "";
    const parse = (block) => {
      let event = "message";
      const data = [];
      for (const raw of block.split(/\r\n|\r|\n/)) {
        if (!raw || raw.startsWith(":")) continue;
        const idx = raw.indexOf(":");
        const field = idx === -1 ? raw : raw.slice(0, idx);
        let value = idx === -1 ? "" : raw.slice(idx + 1);
        if (value.startsWith(" ")) value = value.slice(1);
        if (field === "event") event = value;
        else if (field === "data") data.push(value);
      }
      return data.length ? { event, data: data.join("\n") } : null;
    };
    try {
      while (true) {
        if (signal && signal.aborted) return;
        const { value, done } = await reader.read();
        if (done) break;
        buf += decoder.decode(value, { stream: true });
        let m;
        while ((m = buf.match(/\r\n\r\n|\n\n|\r\r/))) {
          const block = buf.slice(0, m.index);
          buf = buf.slice(m.index + m[0].length);
          const ev = parse(block);
          if (ev) yield ev;
        }
      }
      buf += decoder.decode();
      const tail = parse(buf);
      if (tail) yield tail;
    } finally {
      try { reader.releaseLock(); } catch { /* already released */ }
    }
  }

  // ---------- API ----------
  class ApiError extends Error {
    constructor(message, { status = 0, kind = "http", hint = "" } = {}) {
      super(message); this.status = status; this.kind = kind; this.hint = hint;
    }
  }
  function authHeaders(extra) {
    return Object.assign({ Authorization: `Bearer ${settings.apiKey}` }, extra || {});
  }
  async function readError(res) {
    let msg = `${res.status} ${res.statusText || ""}`.trim();
    try {
      const j = await res.json();
      msg = (j.error && (j.error.message || j.error)) || j.message || j.detail || msg;
    } catch { /* not JSON */ }
    if (typeof msg !== "string") msg = JSON.stringify(msg);
    const kind = res.status === 401 || res.status === 403 ? "auth" : res.status === 429 ? "busy" : "http";
    const hint = kind === "auth" ? "Check the API key in Settings. It must match API_SERVER_KEY on the server."
      : kind === "busy" ? "The server is running its maximum number of turns. Try again in a moment." : "";
    return new ApiError(msg, { status: res.status, kind, hint });
  }
  async function apiFetch(path, opts = {}) {
    const url = trimSlash(settings.baseUrl) + path;
    let res;
    try {
      res = await fetch(url, Object.assign({}, opts, { headers: authHeaders(opts.headers), cache: "no-store", referrerPolicy: "no-referrer" }));
    } catch (e) {
      if (e.name === "AbortError") throw e;
      throw new ApiError("Couldn't reach the server.", { kind: "network", hint: networkHint() });
    }
    if (!res.ok) throw await readError(res);
    return res;
  }
  function networkHint() {
    const u = trimSlash(settings.baseUrl);
    if (location.protocol === "https:" && /^http:\/\//i.test(u) && !/^http:\/\/(localhost|127\.0\.0\.1)(:|\/|$)/i.test(u)) {
      return "This page is served over HTTPS, so the browser blocks plain http:// servers. Put the server behind HTTPS (a tunnel or reverse proxy).";
    }
    return `Check that the server is running and that API_SERVER_CORS_ORIGINS includes ${location.origin}.`;
  }

  // ---------- demo transport (canned data, no server) ----------
  const sleep = (ms, signal) => new Promise((res, rej) => {
    const t = setTimeout(res, ms);
    if (signal) signal.addEventListener("abort", () => { clearTimeout(t); rej(new DOMException("Aborted", "AbortError")); }, { once: true });
  });
  let demoApproval = null;
  async function* demoStream(userText, signal) {
    const id = "chatcmpl-demo" + Math.random().toString(36).slice(2, 10);
    const chunk = (delta, finish) => ({ event: "message", data: JSON.stringify({ id, object: "chat.completion.chunk", choices: [{ index: 0, delta, finish_reason: finish || null }] }) });
    const tool = (o) => ({ event: "hermes.tool.progress", data: JSON.stringify(o) });
    yield chunk({ role: "assistant" });
    await sleep(350, signal);
    yield chunk({ reasoning_content: "The user wants a look at the server. I'll check disk usage first, then the largest directories." });
    await sleep(500, signal);
    yield tool({ tool: "terminal", emoji: "💻", label: "df -h /", toolCallId: "t1", status: "running" });
    await sleep(900, signal);
    yield tool({ tool: "terminal", toolCallId: "t1", status: "completed" });
    yield tool({ tool: "terminal", emoji: "💻", label: "du -sh /var/* | sort -h | tail -5", toolCallId: "t2", status: "running" });
    await sleep(1200, signal);
    yield tool({ tool: "terminal", toolCallId: "t2", status: "completed" });
    yield { event: "approval.request", data: JSON.stringify({ event: "approval.request", run_id: id, command: "journalctl --vacuum-size=200M", description: "Shrinks the systemd journal to 200 MB. Deletes old log entries.", choices: ["once", "session", "always", "deny"] }) };
    const choice = await new Promise((resolve, reject) => {
      demoApproval = resolve;
      signal.addEventListener("abort", () => reject(new DOMException("Aborted", "AbortError")), { once: true });
    });
    demoApproval = null;
    let text;
    if (choice === "deny") {
      text = `Your root disk is **78% full** (31 GB of 40 GB).\n\nThe biggest directory is \`/var/log\` at 6.2 GB, mostly the systemd journal. You declined the cleanup, so nothing was deleted.\n\nTo free space later, run:\n\n\`\`\`bash\njournalctl --vacuum-size=200M\n\`\`\``;
    } else {
      yield tool({ tool: "terminal", emoji: "💻", label: "journalctl --vacuum-size=200M", toolCallId: "t3", status: "running" });
      await sleep(1100, signal);
      yield tool({ tool: "terminal", toolCallId: "t3", status: "completed" });
      text = `Done. Your root disk went from **78% to 63% full**.\n\n| Directory | Before | After |\n|---|---|---|\n| /var/log | 6.2 GB | 0.2 GB |\n| /var/lib/docker | 9.8 GB | 9.8 GB |\n| /var/cache | 1.1 GB | 1.1 GB |\n\nNext biggest win: \`docker system prune\` would clear about 4 GB of unused images. Want me to run it?`;
    }
    for (const piece of text.match(/[\s\S]{1,7}/g)) {
      yield chunk({ content: piece });
      await sleep(18, signal);
    }
    yield chunk({}, "stop");
    yield { event: "message", data: "[DONE]" };
  }
  const DEMO_SKILLS = [
    { name: "arxiv", description: "Search and summarize papers from arXiv.", category: "research" },
    { name: "github-pr-review", description: "Review a pull request and leave inline comments.", category: "software-development" },
    { name: "obsidian", description: "Read and write notes in an Obsidian vault.", category: "productivity" },
    { name: "server-health", description: "Check disk, memory and service status on this host.", category: "devops" },
  ];
  const DEMO_TOOLSETS = [
    { name: "terminal", label: "Terminal", description: "Run shell commands on the server.", enabled: true, configured: true, tools: ["terminal", "process"] },
    { name: "web", label: "Web", description: "Search the web and read pages.", enabled: true, configured: true, tools: ["web_search", "web_extract"] },
    { name: "browser", label: "Browser", description: "Drive a headless browser.", enabled: false, configured: false, tools: ["browser_navigate", "browser_click"] },
  ];
  const DEMO_JOBS = [
    { id: "a1", name: "Morning digest", schedule_display: "Every day at 08:00", state: "scheduled", enabled: true, prompt: "Summarize overnight alerts and unread GitHub notifications.", next_run_at: new Date(Date.now() + 5 * 3600e3).toISOString() },
    { id: "b2", name: "Backup check", schedule_display: "Every 6 hours", state: "paused", enabled: true, prompt: "Verify last night's restic snapshot exists and is under 24h old.", next_run_at: null },
  ];

  // ---------- conversations ----------
  function current() { return conversations.find((c) => c.id === currentId) || null; }
  function newConversation() {
    const c = { id: uid(), title: "", createdAt: Date.now(), updatedAt: Date.now(), messages: [] };
    conversations.unshift(c);
    currentId = c.id;
    saveConversations(true);
    return c;
  }
  function deriveTitle(text) {
    const t = text.replace(/\s+/g, " ").trim();
    return t.length > 48 ? t.slice(0, 46).trimEnd() + "…" : t;
  }

  // ---------- rendering ----------
  const els = {
    thread: $("#thread"), messages: $("#messages"), empty: $("#empty"), input: $("#input"),
    send: $("#btn-send"), title: $("#conv-title"), conn: $("#conn-pill"), connLabel: $("#conn-label"),
    jump: $("#btn-jump"), drawer: $("#drawer"), scrim: $("#scrim"), convList: $("#conv-list"),
    sheet: $("#sheet"), sheetScrim: $("#sheet-scrim"), sheetTitle: $("#sheet-title"), sheetBody: $("#sheet-body"),
  };

  const STARTERS = [
    ["💽", "How much disk space is left on the server?"],
    ["🧭", "What can you do? List your tools and skills."],
    ["📰", "Search the web for today's top AI news and summarize it."],
    ["⏰", "Every weekday at 9am, send me a summary of my GitHub notifications."],
  ];
  function renderEmpty() {
    const c = current();
    const empty = !c || c.messages.length === 0;
    els.empty.hidden = !empty;
    if (!empty) return;
    const ready = isConnected();
    $("#empty-lede").textContent = ready
      ? (settings.demo ? "You're in demo mode. Answers are canned, so try the first prompt." : "Your agent is standing by. Ask it to do something on your server.")
      : "Connect to your Hermes server to start, or try demo mode to look around.";
    $("#btn-setup").hidden = ready;
    $("#btn-demo").hidden = ready;
    $("#starters").innerHTML = ready ? STARTERS.map(([g, t]) =>
      `<button class="starter" type="button" data-starter="${esc(t)}"><span class="glyph" aria-hidden="true">${g}</span><span>${esc(t)}</span></button>`).join("") : "";
  }

  function renderConn(state) {
    let s = state;
    if (!s) s = settings.demo ? "demo" : (settings.baseUrl && settings.apiKey ? "idle" : "none");
    els.conn.dataset.state = s;
    els.connLabel.textContent = {
      demo: "Demo mode", ok: hostLabel(), idle: hostLabel(), none: "Not connected", error: "Connection problem",
    }[s] || hostLabel();
  }
  function hostLabel() {
    try { return new URL(settings.baseUrl).host; } catch { return "Server"; }
  }

  function renderTitle() {
    const c = current();
    els.title.textContent = (c && c.title) || "New conversation";
  }

  function toolStepHTML(t) {
    const dur = t.endedAt && t.startedAt ? fmtDuration(t.endedAt - t.startedAt) : "";
    const meta = t.status === "running" ? "Running" : t.status === "completed" ? dur : t.status === "failed" ? "Failed" : "";
    return `<li class="step" data-status="${esc(t.status)}">
      <span class="step-emoji" aria-hidden="true">${esc(t.emoji || "•")}</span>
      <span class="step-label" title="${esc(t.label || t.tool)}">${esc(t.label || t.tool)}</span>
      <span class="step-meta">${esc(meta)}</span></li>`;
  }
  const CHOICE_LABEL = { once: "Allow once", session: "Allow for this chat", always: "Always allow", deny: "Deny" };
  function approvalHTML(a, msgId, live) {
    if (a.resolved === "expired" || (!a.resolved && !live)) {
      return `<div class="approval resolved" data-approval="${esc(a.id)}"><p class="approval-done">Expired without an answer: <code>${esc(a.command || a.description || "action")}</code></p></div>`;
    }
    if (a.resolved) {
      return `<div class="approval resolved${a.resolved === "deny" ? " denied" : ""}" data-approval="${esc(a.id)}"><p class="approval-done">${a.resolved === "deny" ? "You denied" : "You approved"}: <code>${esc(a.command || a.description || "action")}</code>${a.resolved !== "deny" && a.resolved !== "once" ? ` (${esc(CHOICE_LABEL[a.resolved] || a.resolved).toLowerCase()})` : ""}</p></div>`;
    }
    const choices = (Array.isArray(a.choices) && a.choices.length ? a.choices : ["once", "deny"]);
    const btns = choices.map((c) => `<button type="button" class="${c === "once" ? "primary" : c === "deny" ? "deny" : ""}" data-approve="${esc(c)}" data-msg="${esc(msgId)}" data-aid="${esc(a.id)}">${esc(CHOICE_LABEL[c] || c)}</button>`).join("");
    return `<div class="approval" data-approval="${esc(a.id)}">
      <h3>Hermes needs your approval</h3>
      ${a.description ? `<p>${esc(a.description)}</p>` : ""}
      ${a.command ? `<pre>${esc(a.command)}</pre>` : ""}
      <div class="approval-actions">${btns}</div></div>`;
  }

  function agentHTML(m) {
    const streaming = m.status === "streaming";
    const parts = [];
    const time = new Date(m.createdAt).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });
    parts.push(`<div class="agent-head"><span class="agent-name">Hermes</span><span>${streaming ? (m.tools && m.tools.some((t) => t.status === "running") ? "Working" : "Thinking") : esc(time)}</span></div>`);
    if (settings.showReasoning && m.reasoning) {
      parts.push(`<details class="reasoning"${m.reasoningOpen ? " open" : ""} data-reasoning="${esc(m.id)}"><summary>${streaming && !m.content ? "Reasoning" : "Show reasoning"}</summary><div class="reasoning-body">${esc(m.reasoning)}</div></details>`);
    }
    // Interleave tool steps and approvals in arrival order.
    const items = [];
    for (const t of m.tools || []) items.push({ at: t.seq, html: null, t });
    for (const a of m.approvals || []) items.push({ at: a.seq, a });
    items.sort((x, y) => x.at - y.at);
    let trail = [];
    const flushTrail = () => { if (trail.length) { parts.push(`<ol class="trail">${trail.join("")}</ol>`); trail = []; } };
    for (const it of items) {
      if (it.t) trail.push(toolStepHTML(it.t));
      else { flushTrail(); parts.push(approvalHTML(it.a, m.id, streaming)); }
    }
    flushTrail();
    if (streaming && m.statusText) parts.push(`<p class="status-line">${esc(m.statusText)}</p>`);
    if (m.content) parts.push(`<div class="md">${renderMarkdown(m.content)}${streaming ? '<span class="caret" aria-hidden="true"></span>' : ""}</div>`);
    else if (streaming && !(m.tools && m.tools.length) && !m.reasoning) parts.push(`<div class="md"><span class="caret" aria-hidden="true"></span></div>`);
    if (m.status === "error") {
      parts.push(`<div class="turn-error" role="alert"><strong>${esc(m.error || "Something went wrong.")}</strong>${m.errorHint ? `<div>${esc(m.errorHint)}</div>` : ""}<button type="button" data-retry="${esc(m.id)}">Try again</button></div>`);
    }
    if (!streaming && m.status !== "error") {
      const note = m.status === "stopped" ? "Stopped" : m.finish && m.finish !== "stop" ? `Ended: ${m.finish}` : "";
      parts.push(`<div class="turn-foot">${note ? `<span>${esc(note)}</span>` : ""}<span class="spacer"></span>
        ${m.content ? `<button type="button" data-copy="${esc(m.id)}" aria-label="Copy reply"><svg viewBox="0 0 24 24" aria-hidden="true"><rect x="8" y="8" width="12" height="12" rx="2"/><path d="M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2"/></svg>Copy</button>` : ""}
        <button type="button" data-retry="${esc(m.id)}" aria-label="Regenerate reply"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20 11a8 8 0 1 0-2.3 5.7M20 4v7h-7"/></svg>Retry</button></div>`);
    }
    return parts.join("");
  }

  function messageEl(m) {
    const el = document.createElement("article");
    el.dataset.id = m.id;
    if (m.role === "user") {
      el.className = "msg msg-user";
      el.innerHTML = `<div class="bubble">${esc(m.content)}</div>`;
    } else {
      el.className = "msg msg-agent";
      el.dataset.status = m.status;
      el.innerHTML = agentHTML(m);
    }
    return el;
  }

  function renderMessages() {
    const c = current();
    els.messages.textContent = "";
    if (c) for (const m of c.messages) els.messages.appendChild(messageEl(m));
    renderEmpty();
    renderTitle();
  }

  let rafPending = new Set();
  function updateMessage(m) {
    rafPending.add(m);
    if (rafPending.size > 1) return;
    requestAnimationFrame(() => {
      const stick = nearBottom();
      for (const msg of rafPending) {
        const el = els.messages.querySelector(`[data-id="${CSS.escape(msg.id)}"]`);
        if (!el) continue;
        el.dataset.status = msg.status;
        el.innerHTML = agentHTML(msg);
        if (msg.status === "streaming") el.style.setProperty("--rail", `${Math.min(100, 20 + (msg.content.length / 12) + (msg.tools || []).length * 12)}%`);
      }
      rafPending = new Set();
      if (stick) scrollToBottom();
      updateJump();
    });
  }

  function renderConvList() {
    if (!conversations.length) { els.convList.innerHTML = `<li class="conv-empty">No conversations yet.</li>`; return; }
    els.convList.innerHTML = conversations.map((c) => `
      <li class="conv-item" ${c.id === currentId ? 'aria-current="true"' : ""}>
        <button class="conv-open" type="button" data-open="${esc(c.id)}">
          <span class="conv-title">${esc(c.title || "New conversation")}</span>
          <span class="conv-when">${esc(relTime(c.updatedAt))}</span>
        </button>
        <button class="conv-del" type="button" data-del="${esc(c.id)}" aria-label="Delete ${esc(c.title || "conversation")}">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3"/></svg>
        </button>
      </li>`).join("");
  }

  // ---------- scrolling ----------
  const nearBottom = () => els.thread.scrollHeight - els.thread.scrollTop - els.thread.clientHeight < 120;
  const scrollToBottom = (smooth) => els.thread.scrollTo({ top: els.thread.scrollHeight, behavior: smooth ? "smooth" : "auto" });
  const updateJump = () => { els.jump.hidden = nearBottom(); };
  els.thread.addEventListener("scroll", updateJump, { passive: true });
  els.jump.addEventListener("click", () => scrollToBottom(true));

  // ---------- sending ----------
  let active = null; // { controller, msg, runId }

  function setBusy(busy) {
    els.send.classList.toggle("stop", busy);
    els.send.setAttribute("aria-label", busy ? "Stop" : "Send");
    els.thread.setAttribute("aria-busy", busy ? "true" : "false");
    syncSend();
  }
  function syncSend() {
    els.send.disabled = !active && !els.input.value.trim();
  }

  function buildMessages(conv, uptoIndex) {
    const sys = [settings.instructions.trim(), `(Client: Hermes Mobile, conversation ${conv.id.slice(0, 8)})`].filter(Boolean).join("\n\n");
    const msgs = [{ role: "system", content: sys }];
    for (const m of conv.messages.slice(0, uptoIndex)) {
      if (m.role === "user") msgs.push({ role: "user", content: m.content });
      else if (m.content) msgs.push({ role: "assistant", content: m.content });
    }
    return msgs;
  }

  async function send(text) {
    if (active) return;
    if (!isConnected()) { openSheet("settings"); toast("Connect to a server or turn on demo mode first."); return; }
    let conv = current() || newConversation();
    if (!conv.title) conv.title = deriveTitle(text);
    conv.messages.push({ id: uid(), role: "user", content: text, createdAt: Date.now() });
    await runTurn(conv);
  }

  async function runTurn(conv) {
    const msg = { id: uid(), role: "assistant", content: "", reasoning: "", tools: [], approvals: [], status: "streaming", createdAt: Date.now() };
    conv.messages.push(msg);
    conv.updatedAt = Date.now();
    // move to top of list
    conversations = [conv, ...conversations.filter((c) => c !== conv)];
    saveConversations();
    renderMessages();
    scrollToBottom();

    const controller = new AbortController();
    active = { controller, msg, runId: null, conv };
    setBusy(true);
    let seq = 0;

    try {
      let events;
      if (settings.demo) {
        events = demoStream(conv.messages[conv.messages.length - 2].content, controller.signal);
      } else {
        const body = { model: settings.model || "hermes-agent", stream: true, messages: buildMessages(conv, conv.messages.length - 1) };
        const res = await apiFetch("/v1/chat/completions", {
          method: "POST", signal: controller.signal,
          headers: { "Content-Type": "application/json", Accept: "text/event-stream", "Idempotency-Key": msg.id },
          body: JSON.stringify(body),
        });
        renderConn("ok");
        if (!res.body) throw new ApiError("This browser can't read streaming responses.", { kind: "client" });
        events = sseEvents(res, controller.signal);
      }

      let done = false;
      for await (const ev of events) {
        if (ev.data === "[DONE]") { done = true; break; }
        let data;
        try { data = JSON.parse(ev.data); } catch { continue; }
        if (ev.event === "message") {
          if (data.id && !active.runId) active.runId = data.id;
          const choice = data.choices && data.choices[0];
          if (data.error && !choice) { msg.error = data.error.message || String(data.error); }
          if (choice) {
            const d = choice.delta || {};
            if (typeof d.content === "string") { msg.content += d.content; msg.statusText = ""; }
            if (typeof d.reasoning_content === "string") msg.reasoning += d.reasoning_content;
            if (choice.finish_reason) msg.finish = choice.finish_reason;
          }
          if (data.error) { msg.error = data.error.message || msg.error; }
          if (data.usage) msg.usage = data.usage;
        } else if (ev.event === "hermes.tool.progress") {
          const id = data.toolCallId || data.tool_call_id || `${data.tool}-${seq}`;
          let t = msg.tools.find((x) => x.id === id);
          if (!t) {
            t = { id, tool: data.tool || "tool", emoji: data.emoji || "", label: data.label || data.tool || "tool", status: "running", startedAt: Date.now(), seq: seq++ };
            msg.tools.push(t);
          }
          if (data.emoji) t.emoji = data.emoji;
          if (data.label) t.label = data.label;
          if (data.status && data.status !== "running") { t.status = data.status === "completed" ? "completed" : data.status; t.endedAt = Date.now(); }
          msg.statusText = "";
        } else if (ev.event === "hermes.status") {
          msg.statusText = typeof data.text === "string" ? data.text : "";
        } else if (ev.event === "approval.request") {
          msg.approvals.push({
            id: data.request_id || uid(), requestId: data.request_id || null,
            runId: data.run_id || active.runId, command: data.command || "", description: data.description || "",
            choices: data.choices, seq: seq++, resolved: null,
          });
          if (navigator.vibrate) try { navigator.vibrate(60); } catch { /* ignore */ }
          if (document.hidden) notify("Hermes needs your approval", data.command || data.description || "");
        }
        updateMessage(msg);
      }
      if (msg.status === "streaming") {
        if (msg.finish && msg.finish !== "stop" && msg.finish !== "length") {
          msg.status = "error";
          msg.error = msg.error || (msg.finish === "cancelled" ? "The turn was cancelled on the server." : "The agent stopped with an error.");
        } else if (!done && !msg.finish) {
          msg.status = "error";
          msg.error = "The connection closed before the reply finished.";
          msg.errorHint = "Your server or proxy may have timed out. Proxies in front of Hermes must allow long-lived streaming responses.";
        } else {
          msg.status = "done";
        }
      }
    } catch (e) {
      if (e.name === "AbortError") {
        msg.status = "stopped";
      } else {
        msg.status = "error";
        msg.error = e.message || "Something went wrong.";
        msg.errorHint = e.hint || "";
        if (e.kind === "network" || e.kind === "auth") renderConn("error");
      }
    } finally {
      for (const t of msg.tools) if (t.status === "running") { t.status = msg.status === "done" ? "completed" : "unknown"; t.endedAt = t.endedAt || Date.now(); }
      msg.statusText = "";
      active = null;
      setBusy(false);
      conv.updatedAt = Date.now();
      saveConversations(true);
      updateMessage(msg);
      if (document.hidden && msg.status === "done") notify("Hermes replied", msg.content.slice(0, 120));
    }
  }

  function stop() {
    if (!active) return;
    const { controller, runId } = active;
    // Best effort: ask the server to stop, then drop the stream (a disconnect also interrupts the agent).
    if (!settings.demo && runId) {
      fetch(trimSlash(settings.baseUrl) + `/v1/runs/${encodeURIComponent(runId)}/stop`, { method: "POST", headers: authHeaders() }).catch(() => {});
    }
    controller.abort();
  }

  async function answerApproval(msgId, approvalId, choice, button) {
    const conv = current();
    const msg = conv && conv.messages.find((m) => m.id === msgId);
    const a = msg && msg.approvals.find((x) => x.id === approvalId);
    if (!a || a.resolved) return;
    const btns = button.closest(".approval").querySelectorAll("button");
    btns.forEach((b) => { b.disabled = true; });
    try {
      if (settings.demo) {
        if (demoApproval) demoApproval(choice);
      } else {
        const runId = a.runId || (active && active.runId);
        if (!runId) throw new ApiError("This approval has no run id, so it can't be answered.");
        const body = { choice };
        if (a.requestId) body.request_id = a.requestId;
        await apiFetch(`/v1/runs/${encodeURIComponent(runId)}/approval`, {
          method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
        });
      }
      a.resolved = choice;
      saveConversations();
      updateMessage(msg);
    } catch (e) {
      btns.forEach((b) => { b.disabled = false; });
      toast(e.status === 409 ? "That approval already expired." : `Couldn't send your answer: ${e.message}`);
      if (e.status === 409) { a.resolved = "expired"; saveConversations(); updateMessage(msg); }
    }
  }

  function retry(msgId) {
    if (active) return;
    const conv = current();
    if (!conv) return;
    const idx = conv.messages.findIndex((m) => m.id === msgId);
    if (idx < 0) return;
    conv.messages.splice(idx); // drop this reply and anything after it
    runTurn(conv);
  }

  // ---------- notifications (only after the user enables them) ----------
  function notify(title, body) {
    try {
      if (settings.notify && "Notification" in window && Notification.permission === "granted") {
        new Notification(title, { body, icon: "icons/icon-192.png", tag: "hermes" });
      }
    } catch { /* not supported in this context */ }
  }

  // ---------- drawer ----------
  function openDrawer() {
    renderConvList();
    els.scrim.hidden = false;
    els.drawer.classList.add("open");
    els.drawer.setAttribute("aria-hidden", "false");
    $("#btn-menu").setAttribute("aria-expanded", "true");
    setTimeout(() => $("#btn-close-drawer").focus(), 50);
  }
  function closeDrawer() {
    els.drawer.classList.remove("open");
    els.drawer.setAttribute("aria-hidden", "true");
    els.scrim.hidden = true;
    $("#btn-menu").setAttribute("aria-expanded", "false");
  }

  // ---------- sheets ----------
  let sheetName = null;
  let lastFocus = null;
  function openSheet(name) {
    closeDrawer();
    sheetName = name;
    lastFocus = document.activeElement;
    els.sheetTitle.textContent = { settings: "Settings", skills: "Skills", tools: "Tools", jobs: "Scheduled jobs", rename: "Rename conversation" }[name];
    els.sheetBody.innerHTML = "";
    els.sheetScrim.hidden = false;
    els.sheet.classList.add("open");
    els.sheet.setAttribute("aria-hidden", "false");
    ({ settings: renderSettings, skills: renderSkills, tools: renderTools, jobs: renderJobs, rename: renderRename })[name]();
    setTimeout(() => $("#btn-close-sheet").focus(), 60);
  }
  function closeSheet() {
    sheetName = null;
    els.sheet.classList.remove("open");
    els.sheet.setAttribute("aria-hidden", "true");
    els.sheetScrim.hidden = true;
    if (lastFocus && lastFocus.focus) lastFocus.focus();
  }

  function renderRename() {
    const c = current();
    els.sheetBody.innerHTML = `
      <form id="rename-form">
        <div class="field"><label for="f-title">Name</label><input id="f-title" maxlength="80" value="${esc((c && c.title) || "")}"></div>
        <div class="btn-row"><button class="btn" type="button" data-close>Cancel</button><button class="btn primary" type="submit">Save name</button></div>
      </form>`;
    $("#rename-form").addEventListener("submit", (e) => {
      e.preventDefault();
      const conv = current() || newConversation();
      conv.title = $("#f-title").value.trim();
      saveConversations(true);
      renderTitle();
      closeSheet();
    });
  }

  function renderSettings() {
    const mixed = location.protocol === "https:";
    els.sheetBody.innerHTML = `
      <form id="settings-form" autocomplete="off">
        <div class="field">
          <label for="f-url">Server address</label>
          <input id="f-url" type="url" inputmode="url" placeholder="https://hermes.example.com" value="${esc(settings.baseUrl)}" spellcheck="false" autocapitalize="off">
          <span class="hint">The HTTPS address of your Hermes API server${mixed ? ". Plain http:// addresses are blocked on this page." : "."}</span>
        </div>
        <div class="field">
          <label for="f-key">API key</label>
          <div class="key-row">
            <input id="f-key" type="password" placeholder="API_SERVER_KEY" value="${esc(settings.apiKey)}" spellcheck="false" autocapitalize="off">
            <button type="button" id="btn-reveal" aria-pressed="false">Show</button>
          </div>
          <span class="hint">Stored only in this browser on this device.</span>
        </div>
        <div class="field">
          <label for="f-model">Model name</label>
          <input id="f-model" list="model-list" placeholder="hermes-agent" value="${esc(settings.model)}" spellcheck="false" autocapitalize="off">
          <datalist id="model-list"></datalist>
          <span class="hint">Leave empty for the server's default agent.</span>
        </div>
        <div class="btn-row">
          <button class="btn" type="button" id="btn-test">Test connection</button>
          <button class="btn primary" type="submit">Save</button>
        </div>
        <div id="check" hidden></div>

        <div class="switch-row">
          <div><div class="t">Demo mode</div><div class="hint">Canned replies, no server needed.</div></div>
          <label class="switch"><input type="checkbox" id="f-demo" ${settings.demo ? "checked" : ""} aria-label="Demo mode"><span></span></label>
        </div>
        <div class="switch-row">
          <div><div class="t">Show reasoning</div><div class="hint">When the model streams its thinking.</div></div>
          <label class="switch"><input type="checkbox" id="f-reason" ${settings.showReasoning ? "checked" : ""} aria-label="Show reasoning"><span></span></label>
        </div>
        <div class="switch-row">
          <div><div class="t">Notifications</div><div class="hint">Alert me about approvals and replies while this app is in the background.</div></div>
          <label class="switch"><input type="checkbox" id="f-notify" ${settings.notify ? "checked" : ""} aria-label="Notifications"><span></span></label>
        </div>

        <div class="field field-gap">
          <span id="theme-label" class="field-label">Appearance</span>
          <div class="seg" role="group" aria-labelledby="theme-label">
            ${["system", "light", "dark"].map((t) => `<button type="button" data-theme-set="${t}" aria-pressed="${settings.theme === t}">${t[0].toUpperCase() + t.slice(1)}</button>`).join("")}
          </div>
        </div>
        <div class="field">
          <label for="f-instr">Instructions for every conversation</label>
          <textarea id="f-instr" rows="3">${esc(settings.instructions)}</textarea>
          <span class="hint">Sent to the agent as a system message.</span>
        </div>
      </form>

      <div class="guide">
        <h3>Set up your server</h3>
        <p>Enable the API server and allow this page's address in your Hermes config, then restart the gateway.</p>
        <pre>API_SERVER_ENABLED=true
API_SERVER_KEY=choose-a-long-random-key
API_SERVER_CORS_ORIGINS=${esc(location.origin)}
hermes gateway run</pre>
        <p>Your phone needs an HTTPS address for the server. A tunnel such as Tailscale Serve or Cloudflare Tunnel, or a reverse proxy with a certificate, gives you one. Make sure the proxy doesn't buffer or time out streaming responses.</p>
        <h3>Your data</h3>
        <p>Conversations are kept in this browser. Hermes also keeps its own session history on the server.</p>
        <div class="btn-row"><button class="btn danger" type="button" id="btn-wipe">Delete all conversations</button><span></span></div>
      </div>`;

    const form = $("#settings-form");
    const readForm = () => ({
      baseUrl: trimSlash($("#f-url").value),
      apiKey: $("#f-key").value.trim(),
      model: $("#f-model").value.trim(),
      instructions: $("#f-instr").value,
    });
    form.addEventListener("submit", (e) => {
      e.preventDefault();
      Object.assign(settings, readForm());
      saveSettings();
      renderConn();
      renderEmpty();
      toast("Settings saved");
      closeSheet();
    });
    $("#btn-reveal").addEventListener("click", (e) => {
      const k = $("#f-key");
      const show = k.type === "password";
      k.type = show ? "text" : "password";
      e.currentTarget.textContent = show ? "Hide" : "Show";
      e.currentTarget.setAttribute("aria-pressed", String(show));
    });
    $("#btn-test").addEventListener("click", async (e) => {
      const btn = e.currentTarget;
      btn.disabled = true; btn.textContent = "Testing…";
      const result = await testConnection(readForm());
      btn.disabled = false; btn.textContent = "Test connection";
      const box = $("#check");
      box.hidden = false;
      box.className = "check-result";
      box.dataset.kind = result.kind;
      box.innerHTML = `<strong>${esc(result.title)}</strong>${esc(result.body || "")}${result.list ? `<ul>${result.list.map((x) => `<li>${esc(x)}</li>`).join("")}</ul>` : ""}`;
      if (result.models) $("#model-list").innerHTML = result.models.map((m) => `<option value="${esc(m)}">`).join("");
    });
    $("#f-demo").addEventListener("change", (e) => { settings.demo = e.target.checked; saveSettings(); renderConn(); renderEmpty(); });
    $("#f-reason").addEventListener("change", (e) => { settings.showReasoning = e.target.checked; saveSettings(); renderMessages(); });
    $("#f-notify").addEventListener("change", async (e) => {
      if (e.target.checked && "Notification" in window && Notification.permission !== "granted") {
        const p = await Notification.requestPermission().catch(() => "denied");
        if (p !== "granted") { e.target.checked = false; toast("Notifications are blocked for this site in your browser settings."); return; }
      } else if (e.target.checked && !("Notification" in window)) {
        e.target.checked = false; toast("This browser doesn't support notifications here. Add the app to your home screen first."); return;
      }
      settings.notify = e.target.checked; saveSettings();
    });
    els.sheetBody.querySelectorAll("[data-theme-set]").forEach((b) => b.addEventListener("click", () => {
      settings.theme = b.dataset.themeSet; saveSettings(); applyTheme();
      els.sheetBody.querySelectorAll("[data-theme-set]").forEach((x) => x.setAttribute("aria-pressed", String(x === b)));
    }));
    $("#btn-wipe").addEventListener("click", () => {
      if (!confirm("Delete all conversations from this device? Hermes keeps its own copy on the server.")) return;
      if (active) stop();
      conversations = []; currentId = null; saveConversations(true); renderMessages(); toast("All conversations deleted");
    });
  }

  async function testConnection(form) {
    const base = form.baseUrl;
    if (!base) return { kind: "error", title: "Add your server address first." };
    if (!/^https?:\/\//i.test(base)) return { kind: "error", title: "The address must start with https://" };
    if (location.protocol === "https:" && /^http:\/\//i.test(base) && !/^http:\/\/(localhost|127\.0\.0\.1)(:|\/|$)/i.test(base)) {
      return { kind: "error", title: "Plain http:// is blocked here.", body: " This page uses HTTPS, so the browser won't connect to an http:// server. Put Hermes behind HTTPS with a tunnel or reverse proxy." };
    }
    // 1. Is anything there, and does it allow this origin?
    try {
      const r = await fetch(base + "/health", { cache: "no-store" });
      if (!r.ok) return { kind: "error", title: `The server answered ${r.status} at /health.`, body: " Check the address points at the Hermes API server (default port 8642)." };
    } catch {
      let reachable = false;
      try { await fetch(base + "/health", { mode: "no-cors", cache: "no-store" }); reachable = true; } catch { /* unreachable */ }
      return reachable
        ? { kind: "error", title: "The server is up but blocks this page.", body: ` Add ${location.origin} to API_SERVER_CORS_ORIGINS and restart the gateway.` }
        : { kind: "error", title: "Couldn't reach the server.", body: " Check the address, that the gateway is running, and that your phone can reach it." };
    }
    // 2. Does the key work?
    if (!form.apiKey) return { kind: "warn", title: "Server found. Now add the API key." };
    try {
      const r = await fetch(base + "/v1/models", { headers: { Authorization: `Bearer ${form.apiKey}` }, cache: "no-store" });
      if (r.status === 401 || r.status === 403) return { kind: "error", title: "The API key was rejected.", body: " It must match API_SERVER_KEY on the server." };
      if (!r.ok) return { kind: "error", title: `The server answered ${r.status} for /v1/models.` };
      const j = await r.json().catch(() => ({}));
      const models = (j.data || []).map((m) => m.id).filter(Boolean);
      return { kind: "ok", title: "Connected.", body: models.length ? ` Agent: ${models.join(", ")}.` : "", models };
    } catch {
      return { kind: "error", title: "The key check failed.", body: " The server allowed /health but not an authenticated request. Check your proxy forwards the Authorization header." };
    }
  }

  async function loadList(path, demoData, key) {
    if (settings.demo) return demoData;
    if (!settings.baseUrl || !settings.apiKey) throw new ApiError("Connect to a server in Settings first.");
    const res = await apiFetch(path);
    const j = await res.json();
    return j[key] || [];
  }
  function listError(e) {
    els.sheetBody.innerHTML = `<div class="check-result" data-kind="error"><strong>${esc(e.message)}</strong>${esc(e.hint || "")}</div>`;
  }

  async function renderSkills() {
    els.sheetBody.innerHTML = `<p class="loading">Loading skills…</p>`;
    let skills;
    try { skills = await loadList("/v1/skills", DEMO_SKILLS, "data"); } catch (e) { return listError(e); }
    if (sheetName !== "skills") return;
    if (!skills.length) { els.sheetBody.innerHTML = `<p class="empty-note">No skills installed. Install one on the server with <code>hermes skills install</code>.</p>`; return; }
    els.sheetBody.innerHTML = `<input class="search" type="search" placeholder="Search ${skills.length} skills" aria-label="Search skills"><ul class="rows" id="skill-rows"></ul>`;
    const draw = (q) => {
      const ql = q.toLowerCase();
      const hits = skills.filter((s) => !ql || `${s.name} ${s.description || ""} ${s.category || ""}`.toLowerCase().includes(ql));
      $("#skill-rows").innerHTML = hits.length ? hits.map((s) => `
        <li class="row"><div class="row-top"><span class="row-name">${esc(s.name)}</span>${s.category ? `<span class="tag">${esc(s.category)}</span>` : ""}</div>
        ${s.description ? `<div class="row-desc">${esc(s.description)}</div>` : ""}
        <div class="row-actions"><button type="button" data-use-skill="${esc(s.name)}">Use in chat</button></div></li>`).join("")
        : `<li class="empty-note">No skills match “${esc(q)}”.</li>`;
    };
    draw("");
    $(".search", els.sheetBody).addEventListener("input", (e) => draw(e.target.value));
  }

  async function renderTools() {
    els.sheetBody.innerHTML = `<p class="loading">Loading tools…</p>`;
    let sets;
    try { sets = await loadList("/v1/toolsets", DEMO_TOOLSETS, "data"); } catch (e) { return listError(e); }
    if (sheetName !== "tools") return;
    const on = sets.filter((s) => s.enabled);
    const off = sets.filter((s) => !s.enabled);
    const row = (s) => `<li class="row"><div class="row-top"><span class="row-name">${esc(s.label || s.name)}</span>
      ${s.enabled ? (s.configured === false ? `<span class="tag warn">Needs keys</span>` : `<span class="tag on">On</span>`) : `<span class="tag">Off</span>`}</div>
      ${s.description ? `<div class="row-desc">${esc(s.description)}</div>` : ""}
      ${s.tools && s.tools.length ? `<div class="row-tools">${esc(s.tools.join(", "))}</div>` : ""}</li>`;
    els.sheetBody.innerHTML = `
      <p class="row-desc sheet-intro">Tools run on your server. Change them with <code>hermes tools</code>.</p>
      ${on.length ? `<h3 class="group-h">Available to the agent</h3><ul class="rows">${on.map(row).join("")}</ul>` : ""}
      ${off.length ? `<h3 class="group-h">Turned off</h3><ul class="rows">${off.map(row).join("")}</ul>` : ""}`;
  }

  async function renderJobs() {
    els.sheetBody.innerHTML = `<p class="loading">Loading jobs…</p>`;
    let jobs;
    try { jobs = await loadList("/api/jobs?include_disabled=true", DEMO_JOBS, "jobs"); } catch (e) { return listError(e); }
    if (sheetName !== "jobs") return;
    if (!jobs.length) {
      els.sheetBody.innerHTML = `<p class="empty-note">No scheduled jobs yet. Ask Hermes in chat, for example “Every morning at 8, summarize my inbox.”</p>`;
      return;
    }
    const stateTag = (j) => {
      const s = j.state || (j.enabled === false ? "disabled" : "scheduled");
      const cls = s === "paused" || s === "disabled" ? "" : s === "error" ? "bad" : "on";
      return `<span class="tag ${cls}">${esc(s[0].toUpperCase() + s.slice(1))}</span>`;
    };
    els.sheetBody.innerHTML = `<ul class="rows">${jobs.map((j) => {
      const paused = j.state === "paused";
      const next = j.next_run_at ? new Date(j.next_run_at) : null;
      return `<li class="row"><div class="row-top"><span class="row-name">${esc(j.name || j.id)}</span>${stateTag(j)}</div>
        <div class="row-desc">${esc(j.schedule_display || j.schedule || "")}${next && !isNaN(next) && !paused ? `. Next run ${esc(next.toLocaleString([], { weekday: "short", hour: "numeric", minute: "2-digit" }))}` : ""}</div>
        ${j.prompt ? `<div class="row-desc row-prompt">${esc(j.prompt.length > 160 ? j.prompt.slice(0, 158) + "…" : j.prompt)}</div>` : ""}
        <div class="row-actions">
          <button type="button" data-job="${esc(j.id)}" data-job-act="run">Run now</button>
          <button type="button" data-job="${esc(j.id)}" data-job-act="${paused ? "resume" : "pause"}">${paused ? "Resume" : "Pause"}</button>
        </div></li>`;
    }).join("")}</ul>`;
  }
  async function jobAction(id, act, btn) {
    btn.disabled = true;
    try {
      if (settings.demo) {
        const j = DEMO_JOBS.find((x) => x.id === id);
        if (act === "pause") j.state = "paused";
        if (act === "resume") j.state = "scheduled";
      } else {
        await apiFetch(`/api/jobs/${encodeURIComponent(id)}/${act}`, { method: "POST" });
      }
      toast({ run: "Job started", pause: "Job paused", resume: "Job resumed" }[act]);
      renderJobs();
    } catch (e) {
      btn.disabled = false;
      toast(`Couldn't ${act === "run" ? "run" : act} the job: ${e.message}`);
    }
  }

  // ---------- theme ----------
  function applyTheme() {
    const root = document.documentElement;
    if (settings.theme === "light" || settings.theme === "dark") root.dataset.theme = settings.theme;
    else delete root.dataset.theme;
    const dark = settings.theme === "dark" || (settings.theme === "system" && matchMedia("(prefers-color-scheme: dark)").matches);
    document.querySelectorAll('meta[name="theme-color"]').forEach((m) => {
      m.setAttribute("content", dark ? "#0F1620" : "#E9EDF2");
      m.removeAttribute("media");
    });
  }

  // ---------- viewport / keyboard ----------
  function syncViewport() {
    const vv = window.visualViewport;
    const h = vv ? vv.height : window.innerHeight;
    document.documentElement.style.setProperty("--vvh", `${h}px`);
    document.body.classList.toggle("keyboard-open", vv ? window.innerHeight - vv.height > 120 : false);
    if (vv && vv.offsetTop) window.scrollTo(0, 0);
    const comp = $(".composer");
    if (comp) document.documentElement.style.setProperty("--composer-h", `${comp.offsetHeight}px`);
  }
  if (window.visualViewport) {
    visualViewport.addEventListener("resize", () => { const stick = nearBottom(); syncViewport(); if (stick) scrollToBottom(); });
    visualViewport.addEventListener("scroll", syncViewport);
  }
  window.addEventListener("resize", syncViewport);

  // ---------- composer ----------
  function autosize() {
    els.input.style.height = "auto";
    els.input.style.height = Math.min(els.input.scrollHeight, window.innerHeight * 0.4) + "px";
    syncViewport();
  }
  els.input.addEventListener("input", () => { autosize(); syncSend(); store.set("hm.draft", els.input.value); });
  els.input.addEventListener("keydown", (e) => {
    // Enter sends on hardware keyboards; on touch devices Enter inserts a newline.
    if (e.key === "Enter" && !e.shiftKey && !e.isComposing && matchMedia("(hover: hover) and (pointer: fine)").matches) {
      e.preventDefault();
      $("#composer").requestSubmit();
    }
  });
  $("#composer").addEventListener("submit", (e) => {
    e.preventDefault();
    if (active) { stop(); return; }
    const text = els.input.value.trim();
    if (!text) return;
    els.input.value = "";
    store.set("hm.draft", "");
    autosize();
    syncSend();
    send(text);
  });

  // ---------- global click delegation ----------
  document.addEventListener("click", async (e) => {
    const t = e.target.closest("button, [data-close]");
    if (!t) return;
    if (t.dataset.starter) { els.input.value = t.dataset.starter; autosize(); syncSend(); $("#composer").requestSubmit(); return; }
    if (t.dataset.approve) { answerApproval(t.dataset.msg, t.dataset.aid, t.dataset.approve, t); return; }
    if (t.dataset.retry) { retry(t.dataset.retry); return; }
    if (t.dataset.copy) {
      const m = current() && current().messages.find((x) => x.id === t.dataset.copy);
      if (m) copyText(m.content);
      return;
    }
    if (t.hasAttribute("data-copy-code")) { copyText(t.closest(".code").querySelector("code").textContent); return; }
    if (t.dataset.open) { currentId = t.dataset.open; saveConversations(true); closeDrawer(); renderMessages(); scrollToBottom(); return; }
    if (t.dataset.del) {
      const c = conversations.find((x) => x.id === t.dataset.del);
      if (!c || !confirm(`Delete “${c.title || "New conversation"}” from this device?`)) return;
      if (active && active.conv === c) stop();
      conversations = conversations.filter((x) => x !== c);
      if (currentId === c.id) currentId = conversations[0] ? conversations[0].id : null;
      saveConversations(true); renderConvList(); renderMessages();
      return;
    }
    if (t.dataset.sheet) { openSheet(t.dataset.sheet); return; }
    if (t.dataset.useSkill) {
      closeSheet();
      els.input.value = `Use the ${t.dataset.useSkill} skill to `;
      autosize(); syncSend(); els.input.focus();
      return;
    }
    if (t.dataset.job) { jobAction(t.dataset.job, t.dataset.jobAct, t); return; }
    if (t.hasAttribute("data-close")) { closeSheet(); return; }
  });
  document.addEventListener("toggle", (e) => {
    const d = e.target;
    if (d.matches && d.matches("details[data-reasoning]")) {
      const m = current() && current().messages.find((x) => x.id === d.dataset.reasoning);
      if (m) m.reasoningOpen = d.open;
    }
  }, true);

  async function copyText(text) {
    try { await navigator.clipboard.writeText(text); toast("Copied"); }
    catch { toast("Couldn't copy. Select the text and copy it instead."); }
  }

  $("#btn-menu").addEventListener("click", openDrawer);
  $("#btn-close-drawer").addEventListener("click", closeDrawer);
  els.scrim.addEventListener("click", closeDrawer);
  $("#btn-close-sheet").addEventListener("click", closeSheet);
  els.sheetScrim.addEventListener("click", closeSheet);
  $("#btn-setup").addEventListener("click", () => openSheet("settings"));
  $("#btn-demo").addEventListener("click", () => { settings.demo = true; saveSettings(); renderConn(); renderEmpty(); toast("Demo mode is on. Turn it off in Settings."); });
  $("#btn-title").addEventListener("click", () => { if (current() && current().messages.length) openSheet("rename"); });
  $("#btn-new").addEventListener("click", () => {
    const c = current();
    if (c && c.messages.length === 0) { els.input.focus(); return; }
    currentId = null;
    saveConversations(true);
    renderMessages();
    els.input.focus();
  });
  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape") { if (sheetName) closeSheet(); else if (els.drawer.classList.contains("open")) closeDrawer(); }
  });

  // Swipe the drawer closed.
  (() => {
    let x0 = null;
    els.drawer.addEventListener("touchstart", (e) => { x0 = e.touches[0].clientX; }, { passive: true });
    els.drawer.addEventListener("touchend", (e) => { if (x0 != null && x0 - e.changedTouches[0].clientX > 60) closeDrawer(); x0 = null; }, { passive: true });
  })();
  // Pull the sheet down to close.
  (() => {
    let y0 = null;
    const head = $(".sheet-grip");
    const area = [head, $(".sheet-head")];
    area.forEach((el) => {
      el.addEventListener("touchstart", (e) => { y0 = e.touches[0].clientY; }, { passive: true });
      el.addEventListener("touchend", (e) => { if (y0 != null && e.changedTouches[0].clientY - y0 > 70) closeSheet(); y0 = null; }, { passive: true });
    });
  })();

  // ---------- boot ----------
  if (!current()) currentId = null;
  // ?demo=1 is a shareable "look around" link.
  if (new URLSearchParams(location.search).get("demo") === "1" && !settings.baseUrl) { settings.demo = true; saveSettings(); }
  applyTheme();
  matchMedia("(prefers-color-scheme: dark)").addEventListener?.("change", applyTheme);
  els.input.value = store.get("hm.draft", "") || "";
  renderConn();
  renderMessages();
  autosize();
  syncSend();
  syncViewport();
  scrollToBottom();

  if ("serviceWorker" in navigator && location.protocol !== "file:") {
    navigator.serviceWorker.register("sw.js", { scope: "./" }).catch(() => { /* offline shell is optional */ });
  }
})();
