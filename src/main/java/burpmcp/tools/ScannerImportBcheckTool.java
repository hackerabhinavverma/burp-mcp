package burpmcp.tools;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.scanner.bchecks.BCheckImportResult;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import burpmcp.BurpMCP;

/** Imports a BCheck script into Burp Scanner. */
public class ScannerImportBcheckTool {
    private final MontoyaApi api;
    private final BurpMCP burpMCP;

    public ScannerImportBcheckTool(MontoyaApi api, BurpMCP burpMCP) {
        this.api = api;
        this.burpMCP = burpMCP;
    }

    public SyncToolSpecification createToolSpecification() {
        String schema = """
        {
            "type": "object",
            "properties": {
                "bcheck": {"type": "string", "description": "Full BCheck YAML script"},
                "enabled": {"type": "boolean", "description": "Default true"}
            },
            "required": ["bcheck"]
        }
        """;
        Tool tool = new Tool("scanner-import-bcheck",
                "Imports a BCheck script into Burp Scanner so it runs alongside built-in checks",
                schema);
        return new SyncToolSpecification(tool, this::handle);
    }

    private CallToolResult handle(McpSyncServerExchange ex, Map<String, Object> args) {
        String client = ex.getClientInfo().name() + " " + ex.getClientInfo().version();
        burpMCP.writeToServerLog("To server", client, "scanner-import-bcheck",
                new GsonBuilder().disableHtmlEscaping().create().toJson(args));
        CallToolResult result;
        try {
            String script = args.get("bcheck").toString();
            boolean enabled = !args.containsKey("enabled") || args.get("enabled") == null
                    || Boolean.parseBoolean(args.get("enabled").toString());
            BCheckImportResult r = api.scanner().bChecks().importBCheck(script, enabled);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", r.status() == null ? null : r.status().toString());
            out.put("errors", r.importErrors());
            result = new CallToolResult(Collections.singletonList(
                    new TextContent(new Gson().toJson(out))), false);
        } catch (Exception e) {
            result = new CallToolResult(Collections.singletonList(
                    new TextContent("ERROR: scanner-import-bcheck failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()))), true);
        }
        burpMCP.writeToServerLog("To client", client, "scanner-import-bcheck", result.toString());
        return result;
    }
}
