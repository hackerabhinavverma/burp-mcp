package burpmcp.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.proxy.ProxyHttpRequestResponse;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import burpmcp.BurpMCP;

/**
 * Reads Burp's full Proxy → HTTP history (everything that flowed through
 * the proxy, persisted across the project). Complements the live-tap
 * ProxyTrafficStore which only retains in-memory entries since extension
 * load. Filters in Java-side; the Montoya filter API needs a Predicate.
 */
public class ProxyHistoryTool {
    private final MontoyaApi api;
    private final BurpMCP burpMCP;

    public ProxyHistoryTool(MontoyaApi api, BurpMCP burpMCP) {
        this.api = api;
        this.burpMCP = burpMCP;
    }

    public SyncToolSpecification createToolSpecification() {
        String schema = """
        {
            "type": "object",
            "properties": {
                "host_contains": {"type": "string", "description": "Optional substring filter on host"},
                "url_contains":  {"type": "string", "description": "Optional substring filter on URL"},
                "host_regex":    {"type": "string", "description": "Optional regex (overrides host_contains)"},
                "url_regex":     {"type": "string", "description": "Optional regex (overrides url_contains)"},
                "status_min":    {"type": "integer"},
                "status_max":    {"type": "integer"},
                "limit": {"type": "integer", "description": "Default 100, max 2000"},
                "offset": {"type": "integer", "description": "Skip first N entries"}
            }
        }
        """;
        Tool tool = new Tool("proxy-history",
                "Reads Burp's full Proxy → HTTP history with optional filters",
                schema);
        return new SyncToolSpecification(tool, this::handle);
    }

    private CallToolResult handle(McpSyncServerExchange ex, Map<String, Object> args) {
        String client = ex.getClientInfo().name() + " " + ex.getClientInfo().version();
        burpMCP.writeToServerLog("To server", client, "proxy-history",
                new GsonBuilder().disableHtmlEscaping().create().toJson(args));
        CallToolResult result;
        try {
            String hostSub = strArg(args, "host_contains");
            String urlSub  = strArg(args, "url_contains");
            String hostRegex = strArg(args, "host_regex");
            String urlRegex  = strArg(args, "url_regex");
            java.util.regex.Pattern hostPat = hostRegex == null ? null : java.util.regex.Pattern.compile(hostRegex);
            java.util.regex.Pattern urlPat  = urlRegex  == null ? null : java.util.regex.Pattern.compile(urlRegex);
            Integer min = intArg(args, "status_min");
            Integer max = intArg(args, "status_max");
            int limit = intArgOr(args, "limit", 100);
            limit = Math.max(1, Math.min(2000, limit));
            int offset = Math.max(0, intArgOr(args, "offset", 0));

            List<ProxyHttpRequestResponse> all = api.proxy().history();
            List<Map<String, Object>> out = new ArrayList<>();
            int skipped = 0;
            for (ProxyHttpRequestResponse rr : all) {
                burp.api.montoya.http.HttpService svc = rr.httpService();
                String host = svc == null || svc.host() == null ? "" : svc.host();
                int port = svc == null ? -1 : svc.port();
                boolean secure = svc != null && svc.secure();
                String url = rr.request() == null || rr.request().url() == null ? "" : rr.request().url();
                String method = rr.request() == null ? null : rr.request().method();
                int status = rr.hasResponse() ? rr.response().statusCode() : -1;
                if (hostPat != null) { if (!hostPat.matcher(host).find()) continue; }
                else if (hostSub != null && !host.toLowerCase().contains(hostSub.toLowerCase())) continue;
                if (urlPat != null) { if (!urlPat.matcher(url).find()) continue; }
                else if (urlSub != null && !url.toLowerCase().contains(urlSub.toLowerCase())) continue;
                if (min != null && status < min) continue;
                if (max != null && status > max) continue;
                if (skipped < offset) { skipped++; continue; }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("time", rr.time().toString());
                row.put("method", method);
                row.put("url", url);
                row.put("host", host);
                row.put("port", port);
                row.put("secure", secure);
                row.put("statusCode", status == -1 ? null : status);
                row.put("edited", rr.edited());
                row.put("mimeType", rr.mimeType() == null ? null : rr.mimeType().name());
                out.add(row);
                if (out.size() >= limit) break;
            }
            Gson gson = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
            String json = gson.toJson(Map.of("count", out.size(), "total", all.size(), "entries", out));
            result = new CallToolResult(Collections.singletonList(new TextContent(json)), false);
        } catch (Exception e) {
            result = new CallToolResult(Collections.singletonList(
                    new TextContent("ERROR: proxy-history failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()))), true);
        }
        burpMCP.writeToServerLog("To client", client, "proxy-history", result.toString());
        return result;
    }

    private static String strArg(Map<String, Object> a, String k) {
        if (!a.containsKey(k) || a.get(k) == null) return null;
        String s = a.get(k).toString();
        return s.isEmpty() ? null : s;
    }

    private static Integer intArg(Map<String, Object> a, String k) {
        if (!a.containsKey(k) || a.get(k) == null) return null;
        if (a.get(k) instanceof Number) return ((Number) a.get(k)).intValue();
        try { return Integer.parseInt(a.get(k).toString()); } catch (Exception e) { return null; }
    }

    private static int intArgOr(Map<String, Object> a, String k, int def) {
        Integer v = intArg(a, k);
        return v == null ? def : v;
    }
}
