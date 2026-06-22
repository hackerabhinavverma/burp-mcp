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

/** Reports whether a URL is in Burp's target scope. */
public class ScopeCheckTool {

    private final MontoyaApi api;
    private final BurpMCP burpMCP;

    public ScopeCheckTool(MontoyaApi api, BurpMCP burpMCP) {
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
                    "description": "Absolute URL to test against Burp's target scope"
                }
            },
            "required": ["url"]
        }
        """;

        Tool tool = new Tool(
                "scope-check",
                "Checks whether a URL is in Burp's target scope",
                schema);

        return new SyncToolSpecification(tool, this::handleToolCall);
    }

    private CallToolResult handleToolCall(McpSyncServerExchange exchange, Map<String, Object> args) {
        String client = exchange.getClientInfo().name() + " " + exchange.getClientInfo().version();
        burpMCP.writeToServerLog("To server", client, "scope-check",
                new GsonBuilder().disableHtmlEscaping().create().toJson(args));
        CallToolResult result;
        try {
            if (!args.containsKey("url")) {
                result = new CallToolResult(Collections.singletonList(
                        new TextContent("ERROR: Missing required parameter: url")), true);
            } else {
                String url = args.get("url").toString();
                boolean inScope = api.scope().isInScope(url);
                result = new CallToolResult(Collections.singletonList(
                        new TextContent("{\"url\":\"" + url.replace("\"", "\\\"") + "\",\"inScope\":" + inScope + "}")),
                        false);
            }
        } catch (Exception e) {
            result = new CallToolResult(Collections.singletonList(
                    new TextContent("ERROR: scope-check failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()))), true);
        }
        burpMCP.writeToServerLog("To client", client, "scope-check", result.toString());
        return result;
    }
}
