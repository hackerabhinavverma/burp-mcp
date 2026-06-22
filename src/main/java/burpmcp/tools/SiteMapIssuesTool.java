package burpmcp.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.scanner.audit.issues.AuditIssue;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import burpmcp.BurpMCP;

/** Lists audit issues recorded in Burp's Target site map. */
public class SiteMapIssuesTool {

    private final MontoyaApi api;
    private final BurpMCP burpMCP;

    public SiteMapIssuesTool(MontoyaApi api, BurpMCP burpMCP) {
        this.api = api;
        this.burpMCP = burpMCP;
    }

    public SyncToolSpecification createToolSpecification() {
        String schema = """
        {
            "type": "object",
            "properties": {
                "severity": {
                    "type": "string",
                    "enum": ["HIGH", "MEDIUM", "LOW", "INFORMATION", "FALSE_POSITIVE"],
                    "description": "Optional severity filter (case-insensitive)"
                },
                "url_prefix": {
                    "type": "string",
                    "description": "Optional URL prefix filter applied to baseUrl"
                },
                "limit": {
                    "type": "integer",
                    "description": "Maximum issues to return (default 100, max 1000)"
                }
            }
        }
        """;

        Tool tool = new Tool(
                "sitemap-issues",
                "Lists audit issues from Burp's site map. Optionally filter by severity or URL prefix.",
                schema);

        return new SyncToolSpecification(tool, this::handleToolCall);
    }

    private CallToolResult handleToolCall(McpSyncServerExchange exchange, Map<String, Object> args) {
        String client = exchange.getClientInfo().name() + " " + exchange.getClientInfo().version();
        burpMCP.writeToServerLog("To server", client, "sitemap-issues",
                new GsonBuilder().disableHtmlEscaping().create().toJson(args));
        CallToolResult result;
        try {
            String severityFilter = args.containsKey("severity") && args.get("severity") != null
                    ? args.get("severity").toString().toUpperCase()
                    : null;
            String urlPrefix = args.containsKey("url_prefix") && args.get("url_prefix") != null
                    ? args.get("url_prefix").toString()
                    : null;
            int limit = 100;
            if (args.containsKey("limit") && args.get("limit") instanceof Number) {
                limit = Math.max(1, Math.min(1000, ((Number) args.get("limit")).intValue()));
            }

            List<AuditIssue> all = api.siteMap().issues();
            List<Map<String, Object>> out = new ArrayList<>();
            for (AuditIssue issue : all) {
                String severity = issue.severity() == null ? "" : issue.severity().name();
                if (severityFilter != null && !severity.equalsIgnoreCase(severityFilter)) continue;
                String baseUrl = issue.baseUrl() == null ? "" : issue.baseUrl();
                if (urlPrefix != null && !baseUrl.startsWith(urlPrefix)) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", issue.name());
                row.put("severity", severity);
                row.put("confidence", issue.confidence() == null ? null : issue.confidence().name());
                row.put("baseUrl", baseUrl);
                row.put("detail", issue.detail());
                row.put("remediation", issue.remediation());
                out.add(row);
                if (out.size() >= limit) break;
            }
            Gson gson = new GsonBuilder().disableHtmlEscaping().create();
            String json = gson.toJson(Map.of("count", out.size(), "issues", out));
            result = new CallToolResult(Collections.singletonList(new TextContent(json)), false);
        } catch (Exception e) {
            result = new CallToolResult(Collections.singletonList(
                    new TextContent("ERROR: sitemap-issues failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()))), true);
        }
        burpMCP.writeToServerLog("To client", client, "sitemap-issues", result.toString());
        return result;
    }
}
