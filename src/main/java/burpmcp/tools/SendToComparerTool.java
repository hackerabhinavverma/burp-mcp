package burpmcp.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.google.gson.GsonBuilder;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import burpmcp.BurpMCP;

/** Sends one or more raw strings to Burp Comparer. */
public class SendToComparerTool {

    private final MontoyaApi api;
    private final BurpMCP burpMCP;

    public SendToComparerTool(MontoyaApi api, BurpMCP burpMCP) {
        this.api = api;
        this.burpMCP = burpMCP;
    }

    public SyncToolSpecification createToolSpecification() {
        String schema = """
        {
            "type": "object",
            "properties": {
                "items": {
                    "type": "array",
                    "description": "Strings to load into Comparer. At least one required.",
                    "items": {"type": "string"},
                    "minItems": 1
                }
            },
            "required": ["items"]
        }
        """;

        Tool tool = new Tool(
                "send-to-comparer",
                "Loads one or more strings into Burp Comparer",
                schema);
        return new SyncToolSpecification(tool, this::handleToolCall);
    }

    @SuppressWarnings("unchecked")
    private CallToolResult handleToolCall(McpSyncServerExchange exchange, Map<String, Object> args) {
        String client = exchange.getClientInfo().name() + " " + exchange.getClientInfo().version();
        burpMCP.writeToServerLog("To server", client, "send-to-comparer",
                new GsonBuilder().disableHtmlEscaping().create().toJson(args));
        CallToolResult result;
        try {
            Object raw = args.get("items");
            if (!(raw instanceof List)) {
                result = new CallToolResult(Collections.singletonList(
                        new TextContent("ERROR: items must be an array of strings")), true);
            } else {
                List<Object> items = (List<Object>) raw;
                if (items.isEmpty()) {
                    result = new CallToolResult(Collections.singletonList(
                            new TextContent("ERROR: items must contain at least one string")), true);
                } else {
                    List<ByteArray> byteArrays = new ArrayList<>(items.size());
                    for (Object item : items) {
                        byteArrays.add(ByteArray.byteArray(item == null ? "" : item.toString()));
                    }
                    api.comparer().sendToComparer(byteArrays.toArray(new ByteArray[0]));
                    result = new CallToolResult(Collections.singletonList(
                            new TextContent("Sent " + byteArrays.size() + " item(s) to Comparer")), false);
                }
            }
        } catch (Exception e) {
            result = new CallToolResult(Collections.singletonList(
                    new TextContent("ERROR: send-to-comparer failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()))), true);
        }
        burpMCP.writeToServerLog("To client", client, "send-to-comparer", result.toString());
        return result;
    }
}
