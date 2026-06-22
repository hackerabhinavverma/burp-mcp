# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

BurpMCP is a Burp Suite extension (Java 21, packaged via Maven) that embeds an MCP (Model Context Protocol) server inside Burp. The server exposes Burp's HTTP client, Collaborator, and a saved-request store as MCP tools so external MCP clients (Cline, Claude Desktop, Cursor, Dive, etc.) can drive manual webapp testing through an LLM. Default transport is SSE on `http://localhost:8181/mcp/sse`; STDIO-only clients use `stdio-bridge.py`.

## Commands

- Build extension jar: `mvn clean package` → `target/burpmcp-1.0-jar-with-dependencies.jar`. Load that jar into Burp (Extensions → Add). Java 21 required.
- Fast build (CI-style, no tests): `mvn -B -ntp -DskipTests package`. Matches `.github/workflows/release.yml`.
- No test suite exists — surefire is declared but there are zero tests. Do not invent test commands. Verification is manual: rebuild, reload the extension in Burp, exercise the affected tool from an MCP client, watch the **Server Logs** tab.
- STDIO bridge deps (for Claude Desktop): `pip3 install typer mcp`; then run `python3 stdio-bridge.py http://localhost:8181/mcp/sse`.

## Architecture

Entry point is `burpmcp.BurpMCP` (implements `burp.api.montoya.BurpExtension`). On `initialize()` it:

1. Constructs three Swing list models (`SavedRequestListModel`, `SentRequestListModel`, `ServerLogListModel`) — these are the in-memory source of truth and back the three Burp tab panels.
2. Hydrates them from `BurpMCPPersistence`, which serializes everything through Montoya's `api.persistence().extensionData()` (per-project storage). Server host/port/CRLF-replace/enabled-flag, Collaborator `SecretKey`, retrieved interactions, and table sort state all live there.
3. Instantiates `MCPServer` and registers a Burp context-menu item ("Send to BurpMCP") that pushes selected `HttpRequestResponse` into `SavedRequestListModel`.
4. Builds the Suite tab with three sub-tabs (Saved Requests / Request Logs / Server Logs) and a control bar (host, port, MCP toggle, LF→CRLF toggle).
5. If `mcpServerEnabled` was persisted true, auto-clicks the toggle so the server restarts with the project.

`burpmcp.MCPServer` owns the MCP runtime. It builds a `WebFluxSseServerTransportProvider` (paths `/mcp/sse` and `/mcp/message`), wires it into a `McpSyncServer` from `io.modelcontextprotocol.sdk`, then hosts it on a Reactor Netty `HttpServer` with `SO_KEEPALIVE` and a 30-min idle timeout. A daemon `ScheduledExecutorService` emits a `LoggingMessageNotification` every 60 s as a heartbeat to keep SSE clients alive. `stop()` calls `cleanup()` which closes the sync server, disposes the Reactor server, nulls the transport provider, and calls `System.gc()` — the GC call is intentional, Burp keeps the classloader alive across reloads and lingering Netty buffers caused leaks.

All MCP tools live in `burpmcp.tools.*`. Each is a plain class with a `createToolSpecification()` that returns a `SyncToolSpecification` containing a `Tool` (name + JSON schema string) and a `BiFunction<McpSyncServerExchange, Map<String,Object>, CallToolResult>` handler. `MCPServer.start()` constructs every tool and registers it in the `.tool(spec.tool(), spec.call())` chain. To add or modify a tool: edit/create the class, edit the JSON schema literal inside `createToolSpecification()`, and add the `.tool(...)` line to the builder in `MCPServer.start()` — there is no auto-discovery.

**52 MCP tools** live under `src/main/java/burpmcp/tools/`. Categories:

- HTTP send/resend (`Http1SendTool`, `Http2SendTool`, `Http1ResendTool`, `Http2ResendTool`)
- Saved requests (`GetSavedRequestTool`, `UpdateNoteTool`, `SaveHttp1RequestTool`, `SaveHttp2RequestTool`)
- Collaborator (`GenerateCollaboratorPayloadTool`, `RetrieveCollaboratorInteractionsTool`)
- Live proxy tap (`ListProxyTrafficTool`, `GetProxyEntryTool`, `SearchProxyTrafficTool`, `TailProxyTrafficTool`)
- Proxy & WebSocket history (`ProxyHistoryTool`, `WebSocketHistoryTool`)
- Site map (`SiteMapListTool`, `SiteMapIssuesTool`, `SitemapAddTool`)
- Scope (`ScopeCheckTool`, `ScopeUpdateTool`)
- Proxy intercept (`ProxyInterceptTool`)
- Scanner control (`ScannerStartAuditTool`, `ScannerAuditStatusTool`, `ScannerStartCrawlTool`, `ScannerCrawlStatusTool`, `ScannerCancelTool`, `ScannerGenerateReportTool`, `ScannerImportBcheckTool`) — long-lived `Audit` / `Crawl` references kept in `burpmcp.scan.ScanRegistry`.
- Hand-off (`SendToRepeaterTool`, `SendToIntruderTool`, `SendToOrganizerTool`, `SendToComparerTool`)
- Cookies (`CookieListTool`, `CookieSetTool`)
- Response analyzers (`AnalyzerCreateKeywordsTool`, `AnalyzerCreateVariationsTool`, `AnalyzerFeedResponseTool`) — analyzers pinned in `burpmcp.scan.AnalyzerRegistry`.
- Encoding & crypto (`DecodeEncodeTool`, `CryptoDigestTool`, `CompressDataTool`, `StringHexTool`, `NumberConvertTool`, `RandomStringTool`)
- JSON (`JsonQueryTool`)
- Burp Suite control (`OptionsExportTool`, `OptionsImportTool`, `TaskEngineTool`, `EditorGetTool`, `EditorSetTool`)
- Project & logging (`ProjectInfoTool`, `BurpLogTool`)

Send/resend tools route through Burp's HTTP client (`api.http().sendRequest(...)`); shared request-building helpers (header parsing, LF→CRLF conversion when `burpMCP.crlfReplace` is true) live in `burpmcp.utils.HttpUtils`. `Send-to-*` tools accept either a saved-request `id` or a raw HTTP/1.1 payload (`data`/`host`/`port`/`secure`) via `HttpUtils.buildHttpRequestFromArgsOrSaved`.

`burpmcp.proxy.AutoSaveHttpHandler` registers on `api.http().registerHttpHandler` and auto-saves in-scope traffic from every non-Proxy/non-Extensions tool source (Target, Scanner, Repeater, Intruder, Crawler) into the saved-requests list when `BurpMCP.autoSaveInScope` is true. `ProxyTapHandler` covers Proxy traffic separately. Both use a 60-second per-(method+url) dedup window.

The control bar exposes **Register in Claude Code** and **Register in Cursor** buttons that write the burpmcp SSE entry into `~/.claude.json` and `~/.cursor/mcp.json` respectively, via `ClaudeCodeConfig.CLAUDE_CODE.register(url)` / `ClaudeCodeConfig.CURSOR.register(url)` (the same instance type — one per target client). A `JLabel` next to the buttons shows the live install status ("Installed in: Claude Code, Cursor"), refreshed after each registration. On first load, `logClientRegistrationStatus()` records the same info to Burp's Output, and `maybePromptClientRegistration()` offers a one-time `JOptionPane` covering any missing client. The suppression flag (persisted as `claudeCodeAutoPrompted` in `BurpMCPPersistence` for back-compat; in-memory field `clientAutoPrompted`) is stored via `BurpMCPPersistence.saveServerConfig`/`restoreServerConfig`.

UI panels are in `burpmcp.ui.*` and follow a list-panel + detail-panel split (e.g. `SavedRequestLogsPanel` + `SavedRequestDetailPanel`). Each list panel exposes its `JTable` so the parent can call `updateUI()` after a model mutation. Sort state for every table flows through `BurpMCPPersistence.{save,restore}TableSortingState(tableKey, sorter)` — call both ends if you add a new sortable table.

## Conventions and gotchas

- The `crlfReplace` flag on `BurpMCP` is read by HTTP/1.1 send/resend tools through `HttpUtils`. When enabled, this defeats HTTP/1.1 request-smuggling testing because `Content-Length` is recomputed after CRLF expansion — README §Limitations documents this and it is intentional.
- HTTP/2 resend re-serializes headers (joins by newline, re-splits, splits cookies into separate headers), which mangles header values that legitimately contain newlines — also documented as a limitation. Do not "fix" this without designing around the Montoya HTTP/2 API.
- Persistence is per-Burp-project, not global. State is written on extension unload (registered in `BurpMCP.initialize`). If you add new persisted fields, mirror the existing pattern: get-or-create a child `PersistedObject`, then read/write typed lists or scalars; size-mismatched lists must short-circuit the restore (see `restoreSavedRequests`).
- Logging to the **Server Logs** tab goes through `BurpMCP.writeToServerLog(direction, client, tool, messageData)` — tool handlers should call this for every inbound request and outbound response so the operator can audit what the LLM is doing.
- The Collaborator client is restored by `SecretKey` so the same Collaborator instance persists across project reloads. Do not regenerate the client unless `collaboratorClientSecretKey` is null.
- Releases: pushing a GitHub release triggers `.github/workflows/release.yml` which builds and attaches `BurpMCP.jar` (renamed from `target/burpmcp-1.0-jar-with-dependencies.jar`). Bump `<version>` in `pom.xml` if you change the artifact name expectations.
