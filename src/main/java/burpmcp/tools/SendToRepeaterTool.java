package burpmcp.tools;

import java.util.Collections;
import java.util.Map;

import com.google.gson.GsonBuilder;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.requests.HttpRequest;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import burpmcp.BurpMCP;
import burpmcp.models.SavedRequestListModel;
import burpmcp.utils.HttpUtils;

/** Pushes a saved or freshly built HTTP/1.1 request into Burp Repeater. */
public class SendToRepeaterTool {

    private final MontoyaApi api;
    private final BurpMCP burpMCP;
    private final SavedRequestListModel savedRequestListModel;

    public SendToRepeaterTool(MontoyaApi api, BurpMCP burpMCP, SavedRequestListModel savedRequestListModel) {
        this.api = api;
        this.burpMCP = burpMCP;
        this.savedRequestListModel = savedRequestListModel;
    }

    public SyncToolSpecification createToolSpecification() {
        String schema = """
        {
            "type": "object",
            "properties": {
                "id":     {"type": "integer", "description": "Saved request id to send. If set, raw fields are ignored."},
                "data":   {"type": "string",  "description": "Raw HTTP/1.1 request bytes (used when id is not given)"},
                "host":   {"type": "string",  "description": "Target host (raw mode)"},
                "port":   {"type": "integer", "description": "Target port (raw mode)"},
                "secure": {"type": "boolean", "description": "Use HTTPS (raw mode)"},
                "tabName": {"type": "string", "description": "Optional tab name in Repeater"}
            }
        }
        """;

        Tool tool = new Tool(
                "send-to-repeater",
                "Sends a request to Burp Repeater. Provide either a saved-request id or raw HTTP/1.1 fields.",
                schema);
        return new SyncToolSpecification(tool, this::handleToolCall);
    }

    private CallToolResult handleToolCall(McpSyncServerExchange exchange, Map<String, Object> args) {
        String client = exchange.getClientInfo().name() + " " + exchange.getClientInfo().version();
        burpMCP.writeToServerLog("To server", client, "send-to-repeater",
                new GsonBuilder().disableHtmlEscaping().create().toJson(args));
        CallToolResult result;
        try {
            HttpRequest req = HttpUtils.buildHttpRequestFromArgsOrSaved(args, savedRequestListModel, burpMCP.crlfReplace);
            String tabName = args.containsKey("tabName") && args.get("tabName") != null ? args.get("tabName").toString() : null;
            if (tabName != null && !tabName.isEmpty()) {
                api.repeater().sendToRepeater(req, tabName);
            } else {
                api.repeater().sendToRepeater(req);
            }
            result = new CallToolResult(Collections.singletonList(
                    new TextContent("Sent to Repeater" + (tabName != null ? " (tab: " + tabName + ")" : ""))), false);
        } catch (Exception e) {
            result = new CallToolResult(Collections.singletonList(
                    new TextContent("ERROR: send-to-repeater failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()))), true);
        }
        burpMCP.writeToServerLog("To client", client, "send-to-repeater", result.toString());
        return result;
    }
}
