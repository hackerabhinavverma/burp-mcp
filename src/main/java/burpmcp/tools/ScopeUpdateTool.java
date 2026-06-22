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

/** Adds or removes a URL prefix from Burp's target scope. */
public class ScopeUpdateTool {

    private final MontoyaApi api;
    private final BurpMCP burpMCP;

    public ScopeUpdateTool(MontoyaApi api, BurpMCP burpMCP) {
        this.api = api;
        this.burpMCP = burpMCP;
    }

    public SyncToolSpecification createToolSpecification() {
        String schema = """
        {
            "type": "object",
            "properties": {
                "url": {
                    "type": "string",
                    "description": "Absolute URL or URL prefix to include/exclude in scope"
                },
                "action": {
                    "type": "string",
                    "enum": ["include", "exclude"],
                    "description": "Whether to include the URL in scope or exclude it"
                }
            },
            "required": ["url", "action"]
        }
        """;

        Tool tool = new Tool(
                "scope-update",
                "Adds (include) or removes (exclude) a URL from Burp's target scope",
                schema);

        return new SyncToolSpecification(tool, this::handleToolCall);
    }

    private CallToolResult handleToolCall(McpSyncServerExchange exchange, Map<String, Object> args) {
        String client = exchange.getClientInfo().name() + " " + exchange.getClientInfo().version();
        burpMCP.writeToServerLog("To server", client, "scope-update",
                new GsonBuilder().disableHtmlEscaping().create().toJson(args));
        CallToolResult result;
        try {
            if (!args.containsKey("url") || !args.containsKey("action")) {
                result = new CallToolResult(Collections.singletonList(
                        new TextContent("ERROR: Missing required parameter(s): url, action")), true);
            } else {
                String url = args.get("url").toString();
                String action = args.get("action").toString();
                if ("include".equalsIgnoreCase(action)) {
                    api.scope().includeInScope(url);
                    result = new CallToolResult(Collections.singletonList(
                            new TextContent("Included in scope: " + url)), false);
                } else if ("exclude".equalsIgnoreCase(action)) {
                    api.scope().excludeFromScope(url);
                    result = new CallToolResult(Collections.singletonList(
                            new TextContent("Excluded from scope: " + url)), false);
                } else {
                    result = new CallToolResult(Collections.singletonList(
                            new TextContent("ERROR: action must be 'include' or 'exclude'")), true);
                }
            }
        } catch (Exception e) {
            result = new CallToolResult(Collections.singletonList(
                    new TextContent("ERROR: scope-update failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()))), true);
        }
        burpMCP.writeToServerLog("To client", client, "scope-update", result.toString());
        return result;
    }
}
