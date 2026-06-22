package burpmcp.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import burpmcp.BurpMCP;

/**
 * Lists Burp's Target site map. Results are filtered Java-side because
 * SiteMapFilter requires a Java Predicate the LLM cannot supply over MCP.
 */
public class SiteMapListTool {

    private final MontoyaApi api;
    private final BurpMCP burpMCP;

    public SiteMapListTool(MontoyaApi api, BurpMCP burpMCP) {
        this.api = api;
        this.burpMCP = burpMCP;
    }

    public SyncToolSpecification createToolSpecification() {
        String schema = """
        {
            "type": "object",
            "properties": {
                "prefix": {
                    "type": "string",
                    "description": "Optional URL prefix; only entries whose URL starts with this string are returned"
                },
                "limit": {
                    "type": "integer",
                    "description": "Maximum number of entries to return (default 100, max 1000)"
                }
            }
        }
        """;

        Tool tool = new Tool(
                "sitemap-list",
                "Lists request/response entries from Burp's Target site map, optionally filtered by URL prefix",
                schema);

        return new SyncToolSpecification(tool, this::handleToolCall);
    }

    private CallToolResult handleToolCall(McpSyncServerExchange exchange, Map<String, Object> args) {
        String client = exchange.getClientInfo().name() + " " + exchange.getClientInfo().version();
        burpMCP.writeToServerLog("To server", client, "sitemap-list",
                new GsonBuilder().disableHtmlEscaping().create().toJson(args));
        CallToolResult result;
        try {
            String prefix = args.containsKey("prefix") && args.get("prefix") != null
                    ? args.get("prefix").toString()
                    : null;
            int limit = 100;
            if (args.containsKey("limit") && args.get("limit") instanceof Number) {
                limit = Math.max(1, Math.min(1000, ((Number) args.get("limit")).intValue()));
            }

            List<HttpRequestResponse> entries = api.siteMap().requestResponses();
            List<Map<String, Object>> out = new ArrayList<>();
            for (HttpRequestResponse rr : entries) {
                HttpRequest req = rr.request();
                String url = req == null ? "" : req.url();
                if (prefix != null && !url.startsWith(prefix)) continue;
                Map<String, Object> row = new java.util.LinkedHashMap<>();
                row.put("url", url);
                row.put("method", req == null ? null : req.method());
                HttpResponse resp = rr.response();
                row.put("statusCode", resp == null ? null : (int) resp.statusCode());
                row.put("mimeType", resp == null ? null : (resp.statedMimeType() == null ? null : resp.statedMimeType().toString()));
                out.add(row);
                if (out.size() >= limit) break;
            }
            Gson gson = new GsonBuilder().disableHtmlEscaping().create();
            String json = gson.toJson(Map.of("count", out.size(), "entries", out));
            result = new CallToolResult(Collections.singletonList(new TextContent(json)), false);
        } catch (Exception e) {
            result = new CallToolResult(Collections.singletonList(
                    new TextContent("ERROR: sitemap-list failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()))), true);
        }
        burpMCP.writeToServerLog("To client", client, "sitemap-list", result.toString());
        return result;
    }
}
