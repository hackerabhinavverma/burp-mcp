package burpmcp.tools;

import java.util.Collections;
import java.util.Map;

import com.google.gson.GsonBuilder;

import burp.api.montoya.MontoyaApi;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import burpmcp.BurpMCP;

/** Imports Burp project or user options from JSON. */
public class OptionsImportTool {
    private final MontoyaApi api;
    private final BurpMCP burpMCP;

    public OptionsImportTool(MontoyaApi api, BurpMCP burpMCP) {
        this.api = api;
        this.burpMCP = burpMCP;
    }

    public SyncToolSpecification createToolSpecification() {
        String schema = """
        {
            "type": "object",
            "properties": {
                "scope": {"type": "string", "enum": ["project","user"]},
                "json":  {"type": "string", "description": "Burp options JSON blob"}
            },
            "required": ["scope","json"]
        }
        """;
        Tool tool = new Tool("options-import",
                "Imports Burp project or user options from a JSON blob",
                schema);
        return new SyncToolSpecification(tool, this::handle);
    }

    private CallToolResult handle(McpSyncServerExchange ex, Map<String, Object> args) {
        String client = ex.getClientInfo().name() + " " + ex.getClientInfo().version();
        burpMCP.writeToServerLog("To server", client, "options-import",
                new GsonBuilder().disableHtmlEscaping().create().toJson(args));
        CallToolResult result;
        try {
            String scope = args.get("scope").toString();
            String json = args.get("json").toString();
            if ("project".equalsIgnoreCase(scope)) {
                api.burpSuite().importProjectOptionsFromJson(json);
            } else {
                api.burpSuite().importUserOptionsFromJson(json);
            }
            result = new CallToolResult(Collections.singletonList(
                    new TextContent("Imported " + scope + " options (" + json.length() + " chars)")), false);
        } catch (Exception e) {
            result = new CallToolResult(Collections.singletonList(
                    new TextContent("ERROR: options-import failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()))), true);
        }
        burpMCP.writeToServerLog("To client", client, "options-import", result.toString());
        return result;
    }
}
