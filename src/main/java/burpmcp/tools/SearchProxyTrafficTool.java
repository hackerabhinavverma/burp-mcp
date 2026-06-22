package burpmcp.tools;

import burpmcp.BurpMCP;
import burpmcp.proxy.ProxyTrafficStore;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Plain-text substring search across request and response bodies/headers.
 */
public class SearchProxyTrafficTool {
    private final BurpMCP burpMCP;
    private final ProxyTrafficStore store;

    public SearchProxyTrafficTool(BurpMCP burpMCP, ProxyTrafficStore store) {
        this.burpMCP = burpMCP;
        this.store = store;
    }

    public SyncToolSpecification createToolSpecification() {
        String schema = """
        {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "Substring to search for (case-insensitive)"
                },
                "in": {
                    "type": "string",
                    "description": "Which side to search: 'request', 'response', or 'both' (default both)"
                },
                "limit": {
                    "type": "integer",
                    "description": "Max matches to return (default 25)"
                }
            },
            "required": ["query"]
        }
        """;
        Tool tool = new Tool(
            "search-proxy-traffic",
            "Case-insensitive substring search across captured proxy request/response bytes. Returns matching entry ids and a short snippet around each hit.",
            schema
        );
        return new SyncToolSpecification(tool, this::handle);
    }

    private CallToolResult handle(McpSyncServerExchange exchange, Map<String, Object> args) {
        burpMCP.writeToServerLog("To server", clientName(exchange), "search-proxy-traffic", String.valueOf(args));
        try {
            String q = args.get("query") == null ? "" : args.get("query").toString();
            if (q.isEmpty()) {
                return new CallToolResult(Collections.singletonList(new TextContent("ERROR: 'query' is required")), true);
            }
            String side = args.get("in") == null ? "both" : args.get("in").toString().toLowerCase();
            int limit = intArg(args.get("limit"), 25);
            if (limit <= 0) limit = 25;
            String needle = q.toLowerCase();

            List<ProxyTrafficStore.Entry> snap = store.snapshot();
            StringBuilder sb = new StringBuilder();
            int hits = 0;
            for (int i = snap.size() - 1; i >= 0 && hits < limit; i--) {
                ProxyTrafficStore.Entry e = snap.get(i);
                String reqLower = side.equals("response") ? "" : asciiLower(e.requestBytes);
                String respLower = side.equals("request") ? "" : asciiLower(e.responseBytes);
                int idxReq = reqLower.isEmpty() ? -1 : reqLower.indexOf(needle);
                int idxResp = respLower.isEmpty() ? -1 : respLower.indexOf(needle);
                if (idxReq < 0 && idxResp < 0) continue;
                hits++;
                sb.append("id=").append(e.id)
                  .append(" method=").append(e.method)
                  .append(" host=").append(e.host)
                  .append(" url=").append(e.url)
                  .append(" status=").append(e.statusCode == null ? "-" : e.statusCode)
                  .append('\n');
                if (idxReq >= 0) sb.append("  req: ").append(snippet(e.requestBytes, idxReq, needle.length())).append('\n');
                if (idxResp >= 0) sb.append("  resp: ").append(snippet(e.responseBytes, idxResp, needle.length())).append('\n');
            }
            sb.insert(0, "matches=" + hits + "\n");
            return new CallToolResult(Collections.singletonList(new TextContent(sb.toString())), false);
        } catch (Exception ex) {
            return new CallToolResult(Collections.singletonList(new TextContent("ERROR: " + ex.getMessage())), true);
        }
    }

    private static String asciiLower(byte[] bytes) {
        if (bytes == null) return "";
        return new String(bytes, StandardCharsets.ISO_8859_1).toLowerCase();
    }

    private static String snippet(byte[] bytes, int idx, int needleLen) {
        if (bytes == null) return "";
        int from = Math.max(0, idx - 40);
        int to = Math.min(bytes.length, idx + needleLen + 40);
        String s = new String(bytes, from, to - from, StandardCharsets.ISO_8859_1);
        return s.replace('\n', ' ').replace('\r', ' ');
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
}
