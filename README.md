# BurpMCP

A Burp Suite extension that turns Burp into an MCP server, so Claude Code, Cursor, Cline, Dive, and (with the bundled bridge) Claude Desktop can talk to it.

Forked from swgee/burpmcp. The original shipped 14 tools and a saved-request store. This version is up to 52 tools and feels like a real assistant: the model can run scans, follow proxy traffic live, walk the site map, read cookies, mess with scope, and so on.

I built this because I got tired of switching between Burp and an LLM tab during long testing sessions. With this loaded, I right-click a request in Burp, hit "Send entire branch to BurpMCP", flip on auto-save for the in-scope hosts, and Claude can pick it up from there.

![cover](assets/cover_image.png)

## What's in it

Tools are grouped roughly the way I think about them while testing:

**Sending HTTP**
`http1-send`, `http2-send`, `http1-resend`, `http2-resend`

The resend pair takes a saved-request id plus a list of regex substitutions, which is how I do most of my "now try the same request with X swapped out" loops without re-pasting the whole thing.

**Saved requests**
`save-http1-request`, `save-http2-request`, `get-saved-request`, `update-note`

Saved across project reloads. Notes column is searchable. Auto-save (see below) drops things in here on its own.

**Live proxy tap**
`list-proxy-traffic`, `get-proxy-entry`, `search-proxy-traffic`, `tail-proxy-traffic`

The live tap is the part I'm proudest of. `tail-proxy-traffic` takes a `since_id` and waits up to N ms for newer entries, so an agent loop can stream what's happening through Burp in near real time without re-pulling history every iteration.

**Full proxy history + WebSocket history**
`proxy-history` (regex/substring filters, status range, offset), `websocket-history`

Reads from Burp's real history (the persisted one, not the in-memory live tap).

**Site map + scope**
`sitemap-list`, `sitemap-issues`, `sitemap-add`, `scope-check`, `scope-update`

`sitemap-add` lets the model inject a request/response pair into the site map. Useful when you've pulled something from a screenshot or a file and want it represented in Burp.

**Scanner**
`scanner-start-audit`, `scanner-audit-status`, `scanner-start-crawl`, `scanner-crawl-status`, `scanner-cancel`, `scanner-generate-report`, `scanner-import-bcheck`

`start-audit` takes a saved-request id list plus a built-in config (`LEGACY_ACTIVE_AUDIT_CHECKS` or `LEGACY_PASSIVE_AUDIT_CHECKS`) and returns an opaque `audit_id`. You poll with `audit-status`. There's an in-memory registry behind it; if the extension reloads, audits in flight die with it. This is the biggest gap vs the official PortSwigger MCP, which can only *read* scanner issues — here you can drive scans end to end.

**Hand-off to Burp tools**
`send-to-repeater`, `send-to-intruder`, `send-to-organizer`, `send-to-comparer`

Same payload shape as the resend tools — saved id or raw fields.

**Cookies**
`cookie-list`, `cookie-set`

Reads/writes Burp's cookie jar directly. Setting a cookie with an ISO expiration works; omit `expires_iso` for a session cookie.

**Response analyzers**
`analyzer-create-keywords`, `analyzer-create-variations`, `analyzer-feed-response`

Wraps Burp's `ResponseKeywordsAnalyzer` and `ResponseVariationsAnalyzer`. Useful for blind testing — feed N responses through, ask which keywords or attributes vary vs which stay constant. I haven't seen another MCP server expose these.

**Encoding / crypto / data**
`decode-encode` (base64/URL/HTML), `crypto-digest` (60+ algorithms via Burp's CryptoUtils), `compress-data` (GZIP/DEFLATE/BROTLI), `string-hex`, `number-convert`, `random-string`, `json-query`

**Burp itself**
`options-export`, `options-import`, `task-engine` (run/pause), `editor-get`, `editor-set`, `project-info`, `burp-log`

`editor-get`/`editor-set` read/write whatever Burp text component currently has keyboard focus — basically lets the model see what you're typing in Repeater right now, or paste something back. Borrowed the trick from the official extension.

**Collaborator**
`generate-collaborator-payload`, `retrieve-collaborator-interactions`

Standard Collaborator client. The interaction store is persisted per project.

## The UI side

Most BurpMCP extensions are headless. I needed it to feel like a Burp panel I actually use:

- **Right-click any request** (Proxy history, site map, Repeater, message editor) → BurpMCP submenu with Send to BurpMCP, "Send entire branch" (walks the site map under the URL prefix and grabs everything), Copy URL / curl / MCP http1-send JSON, Send to Repeater / Intruder / Organizer / Comparer, scope add/remove.
- **Auto-save in-scope traffic** — toggle on the control bar. Anything in scope, from any Burp tool (Proxy, Target, Scanner, Crawler, Repeater, Intruder), lands in the saved-request list with a 60-second per-URL dedup. Notes column gets `auto-saved (Scanner, in-scope)` or similar so you know where it came from.
- **Multi-select + Delete Selected** in all three tables (Saved Requests, Request Logs, Server Logs). Shift-click, Cmd-click, DEL or Backspace, right-click → Delete. Took me too long to add this.
- **Server health dot** — green/amber/red next to the host/port fields. Refreshes every 2 seconds. Amber means it bound but recorded an error on start; red means it's stopped or the bind failed.
- **One-click registration** — buttons that write the BurpMCP entry into `~/.claude.json` and `~/.cursor/mcp.json`. Once registered, the button is replaced by a green "Connected with Claude Code" / "Connected with Cursor" pill. If the host/port changes and the URL no longer matches, the buttons come back.

## Compared to the official PortSwigger MCP

PortSwigger ships their own at https://github.com/PortSwigger/mcp-server. It has 27 tools. This one has 52. Everything the official does is here (sometimes under a different name — their `send_http1_request` is my `http1-send`, etc.). The big extra surface is:

| Capability | Official | This |
|---|---|---|
| Read scanner issues | ✓ | ✓ |
| Start / poll / cancel audits + crawls | ✗ | ✓ |
| Generate HTML/XML scan reports | ✗ | ✓ |
| Import BCheck scripts | ✗ | ✓ |
| Persistent saved-request store + notes | ✗ | ✓ |
| Auto-save in-scope traffic | ✗ | ✓ |
| Live proxy tap with cursor (`tail-proxy-traffic`) | ✗ | ✓ |
| Response analyzers (variant/invariant) | ✗ | ✓ |
| Cookie jar read + write | ✗ (read only) | ✓ |
| Right-click context menu in Burp | ✗ | ✓ |
| Register-in-Claude-Code / Register-in-Cursor button | ✗ | ✓ |
| WebSocket history | ✓ | ✓ |
| Project/user options export+import | ✓ | ✓ |
| Active editor get/set | ✓ | ✓ |
| Task execution engine state | ✓ | ✓ |

I tried not to be unfair — the official extension is younger and has the PortSwigger team behind it, which matters for tracking Montoya API changes over time.

## Install

1. Grab `BurpMCP.jar` from the releases.
2. Burp → Extensions → Add → Java → pick the jar. Java 21 in Burp's bundled JRE works.
3. First load asks if you want to register with Claude Code and/or Cursor. Say yes. Restart the MCP client.

If you're on Claude Desktop (STDIO-only), use the bundled bridge:

```sh
pip3 install typer mcp
python3 stdio-bridge.py http://localhost:8181/mcp/sse
```

Then point `claude_desktop_config.json` at it:

```json
{
  "mcpServers": {
    "BurpMCP": {
      "command": "python3",
      "args": ["path/to/stdio-bridge.py", "http://localhost:8181/mcp/sse"],
      "env": {}
    }
  }
}
```

For Cline / Dive / anything else that speaks SSE directly, point them at `http://localhost:8181/mcp/sse`. Default host/port are editable on the BurpMCP tab when the server is stopped.

## Building it

```sh
git clone https://github.com/swgee/burpmcp.git
cd burpmcp
mvn -B -ntp -DskipTests package
# target/burpmcp-1.1.0-jar-with-dependencies.jar
```

After a build, the bundled probe is the fastest sanity check:

```sh
python3 scripts/probe-tools.py
# TOTAL: 52
```

It does the SSE handshake + `tools/list` and prints the live tool roster. Useful when adding a new tool — if it doesn't show up here, you forgot the `.tool(...)` line in `MCPServer.start()`.

## How a typical session looks

This is roughly the flow I follow now:

1. Burp project loaded, target in scope.
2. BurpMCP tab → flip **Auto-save in-scope** to ON.
3. Browse / fuzz / whatever — Burp Proxy captures everything; in-scope traffic streams into Saved Requests automatically.
4. Switch to Claude Code. Ask it to walk the saved requests, summarise the attack surface, propose payloads.
5. Claude calls `http1-resend` with regex substitutions. I watch the results in the **Request Logs** tab.
6. Interesting? Right-click in Saved Requests → Send to Repeater for manual follow-up. Or ask Claude to fire `scanner-start-audit` against the same saved id and poll it.

The point is the model and I share the same set of saved requests, so I'm not screenshotting things into a chat or pasting curl commands back and forth.

## Known sharp edges

- **CRLF replacement defeats HTTP/1.1 request smuggling.** When the LF→CRLF toggle is on, Content-Length gets recomputed after expansion. Turn it off if smuggling is what you're testing — but then some MCP clients can't send raw `\r\n` in their JSON, and the request will fail HTTP parsing. Pick your poison; it's documented inline on the toggle tooltip.
- **HTTP/2 resend re-serializes headers** (joins by newline, splits, splits cookies). Header values containing newlines get mangled. Real HTTP/2 protocol smuggling testing is hard here.
- **Scanner state is in-memory.** If you reload the extension or restart Burp, in-flight audits/crawls die with it. The issues already written to the site map persist; only the task handle goes.
- **Auto-save dedup is 60 seconds per (method+url)** capped at 4096 keys. If you're hammering one URL with thousands of variants in a minute, only the first lands in the saved list. Use `http1-resend` instead if you want each variant tracked.
- **Active editor read/write uses Swing focus.** Has to be a `JTextComponent` with keyboard focus on the actual Burp UI. If Burp's not the foreground window you get "no focused JTextComponent".
- **Single maintainer.** The official PortSwigger one will track new Montoya API releases faster than I will.

## What I haven't built yet

A few things I'd like but haven't gotten to:

- Per-tool authentication / API key. Right now anyone who can reach `localhost:8181` can drive Burp. If you're on a shared box, bind to 127.0.0.1 (default) and don't expose it.
- Streaming responses for `scanner-audit-status` (long-poll new issues instead of full re-pull).
- A `proxy-edit-history-entry` tool that lets the model annotate existing Burp Proxy entries.
- Better WebSocket support — right now I only read history; sending new frames from MCP isn't wired up.

PRs welcome.

## Tool definitions

Per-tool JSON schemas live in `src/main/java/burpmcp/tools/*.java`. Each one has a `createToolSpecification()` method with the inline schema string. The handler is right below it. Easiest place to add a new tool: copy `ScopeCheckTool.java`, edit the schema + handler, then add one line in `MCPServer.start()`.

## License

Same as the upstream fork — see `LICENSE.md`.

## Thanks

- PortSwigger for the Montoya API + the official MCP extension I cribbed the editor-focus trick from
- everyone using this in earnest who's filed an issue
