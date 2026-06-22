package burpmcp.tools;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.google.gson.GsonBuilder;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.scanner.ReportFormat;
import burp.api.montoya.scanner.audit.issues.AuditIssue;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import burpmcp.BurpMCP;
import burpmcp.scan.ScanRegistry;

/** Writes a Burp scanner report (HTML or XML) covering all sitemap issues
 *  or the issues of a specific audit. */
public class ScannerGenerateReportTool {
    private final MontoyaApi api;
    private final BurpMCP burpMCP;
    private final ScanRegistry registry;

    public ScannerGenerateReportTool(MontoyaApi api, BurpMCP burpMCP, ScanRegistry registry) {
        this.api = api;
        this.burpMCP = burpMCP;
        this.registry = registry;
    }

    public SyncToolSpecification createToolSpecification() {
        String schema = """
        {
            "type": "object",
            "properties": {
                "format": {"type": "string", "enum": ["HTML","XML"]},
                "path":   {"type": "string", "description": "Absolute output path"},
                "audit_id": {"type": "string", "description": "Optional audit_id whose issues should be reported; if omitted, all sitemap issues are used"}
            },
            "required": ["format","path"]
        }
        """;
        Tool tool = new Tool("scanner-generate-report",
                "Writes a Burp scanner report to disk",
                schema);
        return new SyncToolSpecification(tool, this::handle);
    }

    private CallToolResult handle(McpSyncServerExchange ex, Map<String, Object> args) {
        String client = ex.getClientInfo().name() + " " + ex.getClientInfo().version();
        burpMCP.writeToServerLog("To server", client, "scanner-generate-report",
                new GsonBuilder().disableHtmlEscaping().create().toJson(args));
        CallToolResult result;
        try {
            ReportFormat fmt = ReportFormat.valueOf(args.get("format").toString().toUpperCase());
            Path p = Paths.get(args.get("path").toString());
            List<AuditIssue> issues;
            if (args.containsKey("audit_id") && args.get("audit_id") != null) {
                String id = args.get("audit_id").toString();
                if (registry.getAudit(id) == null) {
                    return new CallToolResult(Collections.singletonList(
                            new TextContent("ERROR: unknown audit_id " + id)), true);
                }
                issues = registry.getAudit(id).issues();
            } else {
                issues = api.siteMap().issues();
            }
            api.scanner().generateReport(issues, fmt, p);
            result = new CallToolResult(Collections.singletonList(
                    new TextContent("Wrote " + issues.size() + " issue(s) as " + fmt + " to " + p)), false);
        } catch (Exception e) {
            result = new CallToolResult(Collections.singletonList(
                    new TextContent("ERROR: scanner-generate-report failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()))), true);
        }
        burpMCP.writeToServerLog("To client", client, "scanner-generate-report", result.toString());
        return result;
    }
}
