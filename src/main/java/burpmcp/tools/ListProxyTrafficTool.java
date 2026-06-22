package burpmcp.tools;

import burpmcp.BurpMCP;
import burpmcp.proxy.ProxyTrafficStore;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Returns a summary of the most recent live proxy traffic entries.
 */
public class ListProxyTrafficTool {
    private final BurpMCP burpMCP;
    private final ProxyTrafficStore store;

    public ListProxyTrafficTool(BurpMCP burpMCP, ProxyTrafficStore store) {
        this.burpMCP = burpMCP;
        this.store = store;
    }

    public SyncToolSpecification createToolSpecification() {
        String schema = """
        {
            "type": "object",
            "properties": {
                "limit": {
                    "type": "integer",
                    "description": "Max number of most-recent entries to return (default 50, max 500)"
                },
                "since_id": {
                    "type": "integer",
                    "description": "Return only entries with id strictly greater than this value. Useful for incremental tailing."
                },
                "host_contains": {
                    "type": "string",
                    "description": "Optional substring filter applied to the host field"
                }
            }
        }
        """;
        Tool tool = new Tool(
            "list-proxy-traffic",
            "List recent live Burp Proxy traffic entries with summary metadata. Use this to discover request IDs, then call get-proxy-entry for full request/response bytes.",
            schema
        );
        return new SyncToolSpecification(tool, this::handle);
    }

    private CallToolResult handle(McpSyncServerExchange exchange, Map<String, Object> args) {
        burpMCP.writeToServerLog("To server", clientName(exchange), "list-proxy-traffic", String.valueOf(args));
        try {
            int limit = intArg(args.get("limit"), 50);
            if (limit <= 0) limit = 50;
            if (limit > 500) limit = 500;
            long sinceId = longArg(args.get("since_id"), -1);
            String hostFilter = args.get("host_contains") == null ? null : args.get("host_contains").toString().toLowerCase();

            List<ProxyTrafficStore.Entry> entries;
            if (sinceId >= 0) {
                entries = store.sinceId(sinceId, limit);
            } else {
                entries = store.latest(limit);
            }

            StringBuilder sb = new StringBuilder();
            sb.append("total_in_store=").append(store.size())
              .append(" highest_id=").append(store.highestId())
              .append(" returned=");
            int matched = 0;
            StringBuilder rows = new StringBuilder();
            for (ProxyTrafficStore.Entry e : entries) {
                if (hostFilter != null && !e.host.toLowerCase().contains(hostFilter)) continue;
                matched++;
                rows.append(e.id).append('\t')
                    .append(e.requestTime).append('\t')
                    .append(e.method).append('\t')
                    .append(e.host).append(':').append(e.port).append('\t')
                    .append(e.url).append('\t')
                    .append(e.statusCode == null ? "-" : e.statusCode).append('\t')
                    .append(e.responseLength == null ? "-" : e.responseLength).append('\t')
                    .append(e.mimeType == null ? "" : e.mimeType)
                    .append('\n');
            }
            sb.append(matched).append('\n');
            sb.append("id\ttime\tmethod\thost:port\turl\tstatus\tresp_len\tmime\n");
            sb.append(rows);

            CallToolResult result = new CallToolResult(Collections.singletonList(new TextContent(sb.toString())), false);
            burpMCP.writeToServerLog("To client", clientName(exchange), "list-proxy-traffic", "rows=" + matched);
            return result;
        } catch (Exception e) {
            return new CallToolResult(Collections.singletonList(new TextContent("ERROR: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()))), true);
        }
    }

    private static String clientName(McpSyncServerExchange exchange) {
        try { return exchange.getClientInfo().name() + " " + exchange.getClientInfo().version(); }
        catch (Exception e) { return "unknown"; }
    }

    private static int intArg(Object v, int fallback) {
        if (v == null) return fallback;
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return fallback; }
    }

    private static long longArg(Object v, long fallback) {
        if (v == null) return fallback;
        if (v instanceof Number) return ((Number) v).longValue();
        try { return Long.parseLong(v.toString()); } catch (Exception e) { return fallback; }
    }
}
