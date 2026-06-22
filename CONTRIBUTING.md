# Contributing

The fastest way to extend BurpMCP is to add a new MCP tool. There's no autodiscovery — every tool is a class under `src/main/java/burpmcp/tools/` plus one line in `MCPServer.start()`. Took me a while to settle on this; auto-registration via reflection looked clever but made the wiring impossible to grep.

## Adding a new tool

The smallest example in the tree is `ScopeCheckTool.java`. Copy that, rename, and edit two things: the JSON schema string and the handler body. Don't forget to call `burpMCP.writeToServerLog(...)` on the way in and out — the Server Logs tab is how an operator audits what the LLM is doing.

Then in `MCPServer.start()`:

```java
// near the other tool instantiations
MyNewTool myNewTool = new MyNewTool(api, burpMCP);
SyncToolSpecification myNewToolSpec = myNewTool.createToolSpecification();

// in the .tool(...).tool(...) builder chain near the bottom of the method
.tool(myNewToolSpec.tool(), myNewToolSpec.call())
```

Build, reload the jar in Burp, run `python3 scripts/probe-tools.py`. If the new tool name doesn't appear in the list, you missed one of the two edits.

## Conventions worth knowing

- **Error messages.** Use `(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())`. Models hate seeing `ERROR: ... null`.
- **HTTP request building.** Reuse `HttpUtils.buildHttp1Request` / `buildHttp2Request` / `buildHttpRequestFromArgsOrSaved`. Don't roll your own; the LF→CRLF replacement gate lives in there and skipping it breaks request smuggling testing for users who care about it.
- **Long-lived state.** If the tool returns a handle the user will later poll (audit_id, analyzer_id), use `burpmcp.scan.ScanRegistry` / `AnalyzerRegistry`. Adding another registry class is fine if it's a different category of object.
- **Tool naming.** Lowercase with dashes, not snake_case. e.g. `scanner-start-audit`, not `scanner_start_audit`. Yes, the official PortSwigger MCP uses snake_case. I picked dashes early and didn't want to break existing scripts.
- **Schema strings.** Java text blocks (`"""`) so the JSON stays readable. Don't drop them into a single string with `\n` — it's painful to debug.

## What not to add

Things I considered and dropped:

- **`shell-execute` via Burp's `ShellUtils`.** Burp exposes it. Easy. Also a footgun — anything that can reach `localhost:8181` can now spawn arbitrary processes. Not worth it.
- **`persistence-extension-data` get/set.** Effectively a KV store. The model has its own scratch space; it doesn't need Burp's per-project blob. Open a PR if you disagree and have a concrete use case.
- **Auto-discovery of tools via reflection.** Looked clean, made tracing what's actually registered a pain. The explicit `.tool(...)` chain is verbose but obvious.

## Manual verification

There's no unit test suite. Burp wires everything through Swing + the Montoya API, neither of which is easy to fake. The closest thing to a test is:

```sh
mvn -B -ntp -DskipTests package
# reload jar in Burp
python3 scripts/probe-tools.py
# then exercise the new tool from an MCP client and watch Server Logs
```

If you wire in mocks for the Montoya types, I'd happily take a PR.

## Filing issues

Useful in the issue: what you called, what came back, what the Server Logs tab said. The "To server"/"To client" entries already contain the raw MCP JSON, so just paste them in. Saves a round trip.
