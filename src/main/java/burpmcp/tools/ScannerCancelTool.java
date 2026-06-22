package burpmcp.tools;

import java.util.Collections;
import java.util.Map;

import com.google.gson.GsonBuilder;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import burp.api.montoya.scanner.Crawl;
import burp.api.montoya.scanner.audit.Audit;

import burpmcp.BurpMCP;
import burpmcp.scan.ScanRegistry;

/** Cancels a running audit or crawl by id. */
public class ScannerCancelTool {
    private final BurpMCP burpMCP;
    private final ScanRegistry registry;

    public ScannerCancelTool(BurpMCP burpMCP, ScanRegistry registry) {
        this.burpMCP = burpMCP;
        this.registry = registry;
    }

    public SyncToolSpecification createToolSpecification() {
        String schema = """
        {
            "type": "object",
            "properties": {
                "task_id": {"type": "string", "description": "audit_id or crawl_id returned by start-* tools"}
            },
            "required": ["task_id"]
        }
        """;
        Tool tool = new Tool("scanner-cancel",
                "Cancels a running audit or crawl by id",
                schema);
        return new SyncToolSpecification(tool, this::handle);
    }

    private CallToolResult handle(McpSyncServerExchange ex, Map<String, Object> args) {
        String client = ex.getClientInfo().name() + " " + ex.getClientInfo().version();
        burpMCP.writeToServerLog("To server", client, "scanner-cancel",
                new GsonBuilder().disableHtmlEscaping().create().toJson(args));
        CallToolResult result;
        try {
            String id = args.get("task_id").toString();
            Audit a = registry.getAudit(id);
            Crawl c = registry.getCrawl(id);
            if (a != null) {
                a.delete();
                registry.removeAudit(id);
                result = new CallToolResult(Collections.singletonList(new TextContent("Cancelled audit " + id)), false);
            } else if (c != null) {
                c.delete();
                registry.removeCrawl(id);
                result = new CallToolResult(Collections.singletonList(new TextContent("Cancelled crawl " + id)), false);
            } else {
                result = new CallToolResult(Collections.singletonList(
                        new TextContent("ERROR: unknown task_id " + id)), true);
            }
        } catch (Exception e) {
            result = new CallToolResult(Collections.singletonList(
                    new TextContent("ERROR: scanner-cancel failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()))), true);
        }
        burpMCP.writeToServerLog("To client", client, "scanner-cancel", result.toString());
        return result;
    }
}
