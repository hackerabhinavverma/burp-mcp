# Changelog

## 1.1.0

First version I'd actually call done. 14 tools became 52, the UI grew most of the things I kept wishing it had, and the catch-everything error paths got tightened up.

### Tools added (38)

In rough order of "how often I end up calling them":

Live proxy + history
- `list-proxy-traffic`, `get-proxy-entry`, `search-proxy-traffic`, `tail-proxy-traffic` (in-memory live tap with `since_id` cursor)
- `proxy-history` (full Burp history, regex + substring + status range + offset)
- `websocket-history` (proxy WebSocket frames, direction/regex/host filters)

Scope + site map
- `scope-check`, `scope-update`
- `sitemap-list` (prefix filter), `sitemap-issues` (severity + url-prefix filter), `sitemap-add`

Scanner (this is the headline feature)
- `scanner-start-audit`, `scanner-audit-status`, `scanner-cancel`
- `scanner-start-crawl`, `scanner-crawl-status`
- `scanner-generate-report` (HTML / XML)
- `scanner-import-bcheck`

Hand-off
- `send-to-repeater`, `send-to-intruder`, `send-to-organizer`, `send-to-comparer`

Cookies + analyzers
- `cookie-list`, `cookie-set`
- `analyzer-create-keywords`, `analyzer-create-variations`, `analyzer-feed-response`

Encoding / crypto / data
- `decode-encode` (base64 / URL / HTML, encode + decode)
- `crypto-digest` (60+ algorithms via Burp's CryptoUtils)
- `compress-data` (GZIP / DEFLATE / BROTLI)
- `string-hex`, `number-convert`, `random-string`, `json-query`

Burp itself
- `options-export`, `options-import`
- `task-engine` (run / pause)
- `editor-get`, `editor-set` (focused Swing text component)
- `project-info`, `burp-log`

Proxy intercept toggle
- `proxy-intercept`

### UI

- Right-click context menu on requests anywhere in Burp. Send to BurpMCP / Send entire sitemap branch / Copy URL / Copy as curl / Copy as MCP http1-send JSON / Send to Repeater / Intruder / Organizer / Comparer / scope add/remove.
- Auto-save in-scope toggle. Covers every Burp tool, not just Proxy. 60s dedup.
- Register-in-Claude-Code and Register-in-Cursor buttons. Each becomes a green "Connected with …" pill once the URL in `~/.claude.json` or `~/.cursor/mcp.json` matches the current SSE endpoint.
- Server status traffic light (green / amber / red) refreshing every 2 seconds.
- Multi-select in Saved Requests / Request Logs / Server Logs. Shift / Cmd click. DEL + Backspace shortcuts. Right-click popup with Delete Selected.
- Tooltips on host/port fields documenting that they're editable when the server is stopped.

### Cleanup

- Removed `mcp-proxy.jar` (13 MB blob, never referenced anywhere).
- Removed `Showcase/` upstream-fork chat-log directory.
- Removed `wiretap(true)` from the Reactor Netty config (debug spam under load).
- Removed unused `burp.api.montoya.collaborator.Interaction` import.
- Migrated off deprecated Montoya calls: `Version.major()/minor()/build()` → `Version.toString()` + `buildNumber()`; `ProxyHttpRequestResponse.host()/url()/method()/port()/secure()` → `httpService()` + `request()`.
- Every tool's `catch (Exception e)` now falls back to `e.getClass().getSimpleName()` when `getMessage()` is null, so the model never sees "ERROR: ... null".
- `.gitignore` covers `.idea/`, `*.iml`, `.vscode/`, `*.bak.*`, common editor backups.

### Build / release

- `pom.xml` version `1.0` → `1.1.0`.
- Release workflow artefact path bumped accordingly.
- New `scripts/probe-tools.py` does the SSE handshake + `tools/list` so you can sanity-check a build without hand-rolling JSON-RPC.

## 1.0

Original BurpMCP from swgee/burpmcp. 14 MCP tools, single transport, no scanner control, no auto-save, no client-registration UI.
