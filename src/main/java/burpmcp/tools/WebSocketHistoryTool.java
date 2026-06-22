package burpmcp.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.proxy.ProxyWebSocketMessage;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import burpmcp.BurpMCP;

/** Reads Burp's Proxy WebSocket history with optional regex / substring / direction filters. */
public class WebSocketHistoryTool {
    private final MontoyaApi api;
    private final BurpMCP burpMCP;

    public WebSocketHistoryTool(MontoyaApi api, BurpMCP burpMCP) {
        this.api = api;
        this.burpMCP = burpMCP;
    }

    public SyncToolSpecification createToolSpecification() {
        String schema = """
        {
            "type": "object",
            "properties": {
                "direction": {"type": "string", "enum": ["CLIENT_TO_SERVER","SERVER_TO_CLIENT"], "description": "Optional direction filter"},
                "contains":  {"type": "string", "description": "Optional case-insensitive substring search over payload"},
                "regex":     {"type": "string", "description": "Optional regex over payload (overrides contains)"},
                "host_contains": {"type": "string", "description": "Optional substring on the upgrade-request host"},
                "limit":     {"type": "integer", "description": "Default 100, max 1000"},
                "max_payload_bytes": {"type": "integer", "description": "Truncate each payload to this many bytes (default 8192)"},
                "include_payload": {"type": "boolean", "description": "Default true"}
            }
        }
        """;
        Tool tool = new Tool("websocket-history",
                "Reads Burp's Proxy WebSocket history with optional direction/regex/substring/host filters",
                schema);
        return new SyncToolSpecification(tool, this::handle);
    }

    private CallToolResult handle(McpSyncServerExchange ex, Map<String, Object> args) {
        String client = ex.getClientInfo().name() + " " + ex.getClientInfo().version();
        burpMCP.writeToServerLog("To server", client, "websocket-history",
                new GsonBuilder().disableHtmlEscaping().create().toJson(args));
        CallToolResult result;
        try {
            String dirFilter = strArg(args, "direction");
            String contains = strArg(args, "contains");
            String regex = strArg(args, "regex");
            Pattern compiled = regex == null ? null : Pattern.compile(regex);
            String hostSub = strArg(args, "host_contains");
            int limit = Math.max(1, Math.min(1000, intArgOr(args, "limit", 100)));
            int maxPayload = Math.max(0, intArgOr(args, "max_payload_bytes", 8192));
            boolean includePayload = args.containsKey("include_payload") && args.get("include_payload") != null
                    ? Boolean.parseBoolean(args.get("include_payload").toString()) : true;

            List<ProxyWebSocketMessage> all = api.proxy().webSocketHistory();
            List<Map<String, Object>> out = new ArrayList<>();
            for (ProxyWebSocketMessage m : all) {
                String dir = m.direction() == null ? "" : m.direction().name();
                if (dirFilter != null && !dirFilter.equalsIgnoreCase(dir)) continue;
                String payloadStr = m.payload() == null ? "" : m.payload().toString();
                if (contains != null && compiled == null
                        && !payloadStr.toLowerCase().contains(contains.toLowerCase())) continue;
                if (compiled != null && !compiled.matcher(payloadStr).find()) continue;
                String host = "";
                if (m.upgradeRequest() != null && m.upgradeRequest().httpService() != null) {
                    host = m.upgradeRequest().httpService().host();
                }
                if (hostSub != null && !host.toLowerCase().contains(hostSub.toLowerCase())) continue;

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("webSocketId", m.webSocketId());
                row.put("time", m.time().toString());
                row.put("direction", dir);
                row.put("listenerPort", m.listenerPort());
                row.put("host", host);
                row.put("upgradeUrl", m.upgradeRequest() == null ? null : m.upgradeRequest().url());
                row.put("payloadLength", payloadStr.length());
                if (includePayload) {
                    row.put("payload", maxPayload > 0 && payloadStr.length() > maxPayload
                            ? payloadStr.substring(0, maxPayload) : payloadStr);
                }
                out.add(row);
                if (out.size() >= limit) break;
            }
            Gson g = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
            result = new CallToolResult(Collections.singletonList(
                    new TextContent(g.toJson(Map.of("count", out.size(), "total", all.size(), "messages", out)))), false);
        } catch (Exception e) {
            result = new CallToolResult(Collections.singletonList(
                    new TextContent("ERROR: websocket-history failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()))), true);
        }
        burpMCP.writeToServerLog("To client", client, "websocket-history", result.toString());
        return result;
    }

    private static String strArg(Map<String, Object> a, String k) {
        if (!a.containsKey(k) || a.get(k) == null) return null;
        String s = a.get(k).toString();
        return s.isEmpty() ? null : s;
    }

    private static int intArgOr(Map<String, Object> a, String k, int def) {
        if (!a.containsKey(k) || a.get(k) == null) return def;
        if (a.get(k) instanceof Number) return ((Number) a.get(k)).intValue();
        try { return Integer.parseInt(a.get(k).toString()); } catch (Exception e) { return def; }
    }
}
