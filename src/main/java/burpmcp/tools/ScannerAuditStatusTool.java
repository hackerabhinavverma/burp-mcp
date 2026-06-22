package burpmcp.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import burp.api.montoya.scanner.audit.Audit;
import burp.api.montoya.scanner.audit.issues.AuditIssue;

import burpmcp.BurpMCP;
import burpmcp.scan.ScanRegistry;

/** Polls a running Burp audit by id; optionally returns issues. */
public class ScannerAuditStatusTool {
    private final BurpMCP burpMCP;
    private final ScanRegistry registry;

    public ScannerAuditStatusTool(BurpMCP burpMCP, ScanRegistry registry) {
        this.burpMCP = burpMCP;
        this.registry = registry;
    }

    public SyncToolSpecification createToolSpecification() {
        String schema = """
        {
            "type": "object",
            "properties": {
                "audit_id": {"type": "string"},
                "include_issues": {"type": "boolean", "description": "Default true"},
                "issue_limit":    {"type": "integer", "description": "Default 100"}
            },
            "required": ["audit_id"]
        }
        """;
        Tool tool = new Tool("scanner-audit-status",
                "Polls a running Burp audit by id; returns status, counts, and (optionally) current issues",
                schema);
        return new SyncToolSpecification(tool, this::handle);
    }

    private CallToolResult handle(McpSyncServerExchange ex, Map<String, Object> args) {
        String client = ex.getClientInfo().name() + " " + ex.getClientInfo().version();
        burpMCP.writeToServerLog("To server", client, "scanner-audit-status",
                new GsonBuilder().disableHtmlEscaping().create().toJson(args));
        CallToolResult result;
        try {
            String id = args.get("audit_id").toString();
            Audit a = registry.getAudit(id);
            if (a == null) {
                result = new CallToolResult(Collections.singletonList(
                        new TextContent("ERROR: unknown audit_id " + id)), true);
            } else {
                boolean includeIssues = !args.containsKey("include_issues") || args.get("include_issues") == null
                        || Boolean.parseBoolean(args.get("include_issues").toString());
                int limit = args.containsKey("issue_limit") && args.get("issue_limit") instanceof Number
                        ? ((Number) args.get("issue_limit")).intValue() : 100;
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("audit_id", id);
                out.put("statusMessage", a.statusMessage());
                out.put("requestCount", a.requestCount());
                out.put("errorCount", a.errorCount());
                out.put("insertionPointCount", a.insertionPointCount());
                if (includeIssues) {
                    List<Map<String, Object>> issues = new ArrayList<>();
                    List<AuditIssue> all = a.issues();
                    int n = Math.min(limit, all.size());
                    for (int i = 0; i < n; i++) {
                        AuditIssue issue = all.get(i);
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("name", issue.name());
                        row.put("severity", issue.severity() == null ? null : issue.severity().name());
                        row.put("confidence", issue.confidence() == null ? null : issue.confidence().name());
                        row.put("baseUrl", issue.baseUrl());
                        row.put("detail", issue.detail());
                        issues.add(row);
                    }
                    out.put("issuesReturned", issues.size());
                    out.put("issuesTotal", all.size());
                    out.put("issues", issues);
                }
                Gson gson = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
                result = new CallToolResult(Collections.singletonList(new TextContent(gson.toJson(out))), false);
            }
        } catch (Exception e) {
            result = new CallToolResult(Collections.singletonList(
                    new TextContent("ERROR: scanner-audit-status failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()))), true);
        }
        burpMCP.writeToServerLog("To client", client, "scanner-audit-status", result.toString());
        return result;
    }
}
