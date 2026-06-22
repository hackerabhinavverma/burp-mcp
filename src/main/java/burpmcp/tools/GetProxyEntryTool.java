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
import java.util.Map;

/**
 * Returns full request and response bytes for one proxy entry by id.
 */
public class GetProxyEntryTool {
    private static final int MAX_BYTES = 256 * 1024;

    private final BurpMCP burpMCP;
    private final ProxyTrafficStore store;

    public GetProxyEntryTool(BurpMCP burpMCP, ProxyTrafficStore store) {
        this.burpMCP = burpMCP;
        this.store = store;
    }

    public SyncToolSpecification createToolSpecification() {
        String schema = """
        {
            "type": "object",
            "properties": {
                "id": {
                    "type": "integer",
                    "description": "Entry id returned by list-proxy-traffic"
                },
                "include_request": {
                    "type": "boolean",
                    "description": "Include raw request bytes (default true)"
                },
                "include_response": {
                    "type": "boolean",
                    "description": "Include raw response bytes (default true)"
                },
                "max_bytes": {
                    "type": "integer",
                    "description": "Truncate request and response bodies to this many bytes each (default 262144)"
                }
            },
            "required": ["id"]
        }
        """;
        Tool tool = new Tool(
            "get-proxy-entry",
            "Retrieve the full request and response for a single live proxy traffic entry by id.",
            schema
        );
        return new SyncToolSpecification(tool, this::handle);
    }

    private CallToolResult handle(McpSyncServerExchange exchange, Map<String, Object> args) {
        burpMCP.writeToServerLog("To server", clientName(exchange), "get-proxy-entry", String.valueOf(args));
        try {
            long id = longArg(args.get("id"), -1);
            if (id < 0) {
                return new CallToolResult(Collections.singletonList(new TextContent("ERROR: missing or invalid 'id'")), true);
            }
            boolean includeReq = boolArg(args.get("include_request"), true);
            boolean includeResp = boolArg(args.get("include_response"), true);
            int maxBytes = intArg(args.get("max_bytes"), MAX_BYTES);
            if (maxBytes <= 0) maxBytes = MAX_BYTES;

            ProxyTrafficStore.Entry e = store.getById(id);
            if (e == null) {
                return new CallToolResult(Collections.singletonList(new TextContent("ERROR: entry not found id=" + id)), true);
            }

            StringBuilder sb = new StringBuilder();
            sb.append("=== ENTRY ===\n");
            sb.append("id=").append(e.id).append('\n');
            sb.append("request_time=").append(e.requestTime).append('\n');
            sb.append("response_time=").append(e.responseTime).append('\n');
            sb.append("method=").append(e.method).append('\n');
            sb.append("url=").append(e.url).append('\n');
            sb.append("host=").append(e.host).append('\n');
            sb.append("port=").append(e.port).append('\n');
            sb.append("secure=").append(e.secure).append('\n');
            sb.append("http_version=").append(e.httpVersion).append('\n');
            sb.append("status=").append(e.statusCode).append('\n');
            sb.append("response_length=").append(e.responseLength).append('\n');
            sb.append("mime=").append(e.mimeType == null ? "" : e.mimeType).append('\n');

            if (includeReq) {
                sb.append("\n=== REQUEST ===\n");
                sb.append(decode(e.requestBytes, maxBytes));
            }
            if (includeResp) {
                sb.append("\n=== RESPONSE ===\n");
                if (e.responseBytes == null) {
                    sb.append("(no response yet)");
                } else {
                    sb.append(decode(e.responseBytes, maxBytes));
                }
            }
            CallToolResult result = new CallToolResult(Collections.singletonList(new TextContent(sb.toString())), false);
            burpMCP.writeToServerLog("To client", clientName(exchange), "get-proxy-entry", "id=" + id);
            return result;
        } catch (Exception ex) {
            return new CallToolResult(Collections.singletonList(new TextContent("ERROR: " + ex.getMessage())), true);
        }
    }

    private static String decode(byte[] bytes, int max) {
        if (bytes == null) return "";
        if (bytes.length <= max) {
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }
        return new String(bytes, 0, max, StandardCharsets.ISO_8859_1)
            + "\n... [truncated " + (bytes.length - max) + " bytes]";
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

    private static boolean boolArg(Object v, boolean fallback) {
        if (v == null) return fallback;
        if (v instanceof Boolean) return (Boolean) v;
        return Boolean.parseBoolean(v.toString());
    }
}
