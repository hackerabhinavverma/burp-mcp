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

/** Controls Burp Proxy's intercept toggle. */
public class ProxyInterceptTool {

    private final MontoyaApi api;
    private final BurpMCP burpMCP;

    public ProxyInterceptTool(MontoyaApi api, BurpMCP burpMCP) {
        this.api = api;
        this.burpMCP = burpMCP;
    }

    public SyncToolSpecification createToolSpecification() {
        String schema = """
        {
            "type": "object",
            "properties": {
                "action": {
                    "type": "string",
                    "enum": ["enable", "disable", "status"],
                    "description": "enable/disable Proxy intercept, or just report current state"
                }
            },
            "required": ["action"]
        }
        """;

        Tool tool = new Tool(
                "proxy-intercept",
                "Enable, disable, or read the state of Burp Proxy interception",
                schema);

        return new SyncToolSpecification(tool, this::handleToolCall);
    }

    private CallToolResult handleToolCall(McpSyncServerExchange exchange, Map<String, Object> args) {
        String client = exchange.getClientInfo().name() + " " + exchange.getClientInfo().version();
        burpMCP.writeToServerLog("To server", client, "proxy-intercept",
                new GsonBuilder().disableHtmlEscaping().create().toJson(args));
        CallToolResult result;
        try {
            if (!args.containsKey("action")) {
                result = new CallToolResult(Collections.singletonList(
                        new TextContent("ERROR: Missing required parameter: action")), true);
            } else {
                String action = args.get("action").toString().toLowerCase();
                switch (action) {
                    case "enable":
                        api.proxy().enableIntercept();
                        break;
                    case "disable":
                        api.proxy().disableIntercept();
                        break;
                    case "status":
                        break;
                    default:
                        return new CallToolResult(Collections.singletonList(
                                new TextContent("ERROR: action must be enable|disable|status")), true);
                }
                boolean enabled = api.proxy().isInterceptEnabled();
                result = new CallToolResult(Collections.singletonList(
                        new TextContent("{\"enabled\":" + enabled + "}")), false);
            }
        } catch (Exception e) {
            result = new CallToolResult(Collections.singletonList(
                    new TextContent("ERROR: proxy-intercept failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()))), true);
        }
        burpMCP.writeToServerLog("To client", client, "proxy-intercept", result.toString());
        return result;
    }
}
