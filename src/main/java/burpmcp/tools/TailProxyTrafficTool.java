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
 * Blocks (with a cap) until new entries arrive after since_id, or returns
 * whatever is already past since_id. Lets the LLM poll a live feed without
 * busy-looping list-proxy-traffic.
 */
public class TailProxyTrafficTool {
    private final BurpMCP burpMCP;
    private final ProxyTrafficStore store;

    public TailProxyTrafficTool(BurpMCP burpMCP, ProxyTrafficStore store) {
        this.burpMCP = burpMCP;
        this.store = store;
    }

    public SyncToolSpecification createToolSpecification() {
        String schema = """
        {
            "type": "object",
            "properties": {
                "since_id": {
                    "type": "integer",
                    "description": "Wait for entries strictly newer than this id. Pass the highest id from the previous tail or list call."
                },
                "timeout_ms": {
                    "type": "integer",
                    "description": "Max time to wait for new entries (default 5000, max 25000)"
                },
                "limit": {
                    "type": "integer",
                    "description": "Max entries to return (default 50, max 500)"
                }
            }
        }
        """;
        Tool tool = new Tool(
            "tail-proxy-traffic",
            "Wait for and return new proxy traffic entries arriving after since_id. Use this to follow live Burp traffic incrementally.",
            schema
        );
        return new SyncToolSpecification(tool, this::handle);
    }

    private CallToolResult handle(McpSyncServerExchange exchange, Map<String, Object> args) {
        burpMCP.writeToServerLog("To server", clientName(exchange), "tail-proxy-traffic", String.valueOf(args));
        try {
            long sinceId = longArg(args.get("since_id"), store.highestId());
            int timeoutMs = intArg(args.get("timeout_ms"), 5000);
            if (timeoutMs <= 0) timeoutMs = 5000;
            if (timeoutMs > 25000) timeoutMs = 25000;
            int limit = intArg(args.get("limit"), 50);
            if (limit <= 0) limit = 50;
            if (limit > 500) limit = 500;

            long deadline = System.currentTimeMillis() + timeoutMs;
            List<ProxyTrafficStore.Entry> entries = store.sinceId(sinceId, limit);
            while (entries.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(200);
                entries = store.sinceId(sinceId, limit);
            }

            StringBuilder sb = new StringBuilder();
            sb.append("returned=").append(entries.size())
              .append(" highest_id=").append(store.highestId()).append('\n');
            sb.append("id\ttime\tmethod\thost:port\turl\tstatus\tresp_len\n");
            for (ProxyTrafficStore.Entry e : entries) {
                sb.append(e.id).append('\t')
                  .append(e.requestTime).append('\t')
                  .append(e.method).append('\t')
                  .append(e.host).append(':').append(e.port).append('\t')
                  .append(e.url).append('\t')
                  .append(e.statusCode == null ? "-" : e.statusCode).append('\t')
                  .append(e.responseLength == null ? "-" : e.responseLength)
                  .append('\n');
            }
            return new CallToolResult(Collections.singletonList(new TextContent(sb.toString())), false);
        } catch (Exception ex) {
            return new CallToolResult(Collections.singletonList(new TextContent("ERROR: " + ex.getMessage())), true);
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
