package burpmcp.tools;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import burp.api.montoya.scanner.Crawl;

import burpmcp.BurpMCP;
import burpmcp.scan.ScanRegistry;

/** Polls a running Burp crawl by id. */
public class ScannerCrawlStatusTool {
    private final BurpMCP burpMCP;
    private final ScanRegistry registry;

    public ScannerCrawlStatusTool(BurpMCP burpMCP, ScanRegistry registry) {
        this.burpMCP = burpMCP;
        this.registry = registry;
    }

    public SyncToolSpecification createToolSpecification() {
        String schema = """
        {
            "type": "object",
            "properties": {"crawl_id": {"type": "string"}},
            "required": ["crawl_id"]
        }
        """;
        Tool tool = new Tool("scanner-crawl-status",
                "Polls a running Burp crawl by id",
                schema);
        return new SyncToolSpecification(tool, this::handle);
    }

    private CallToolResult handle(McpSyncServerExchange ex, Map<String, Object> args) {
        String client = ex.getClientInfo().name() + " " + ex.getClientInfo().version();
        burpMCP.writeToServerLog("To server", client, "scanner-crawl-status",
                new GsonBuilder().disableHtmlEscaping().create().toJson(args));
        CallToolResult result;
        try {
            String id = args.get("crawl_id").toString();
            Crawl c = registry.getCrawl(id);
            if (c == null) {
                result = new CallToolResult(Collections.singletonList(
                        new TextContent("ERROR: unknown crawl_id " + id)), true);
            } else {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("crawl_id", id);
                out.put("statusMessage", c.statusMessage());
                out.put("requestCount", c.requestCount());
                out.put("errorCount", c.errorCount());
                result = new CallToolResult(Collections.singletonList(
                        new TextContent(new Gson().toJson(out))), false);
            }
        } catch (Exception e) {
            result = new CallToolResult(Collections.singletonList(
                    new TextContent("ERROR: scanner-crawl-status failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()))), true);
        }
        burpMCP.writeToServerLog("To client", client, "scanner-crawl-status", result.toString());
        return result;
    }
}
