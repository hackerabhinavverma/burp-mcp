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

/** Writes a message into Burp's Output / Error / Event log. */
public class BurpLogTool {
    private final MontoyaApi api;
    private final BurpMCP burpMCP;

    public BurpLogTool(MontoyaApi api, BurpMCP burpMCP) {
        this.api = api;
        this.burpMCP = burpMCP;
    }

    public SyncToolSpecification createToolSpecification() {
        String schema = """
        {
            "type": "object",
            "properties": {
                "level":   {"type": "string", "enum": ["output","error","debug_event","info_event","error_event","critical_event"], "description": "Default output"},
                "message": {"type": "string"}
            },
            "required": ["message"]
        }
        """;
        Tool tool = new Tool("burp-log",
                "Writes a message into Burp's Output, Error, or Event log so the operator can audit the LLM's commentary",
                schema);
        return new SyncToolSpecification(tool, this::handle);
    }

    private CallToolResult handle(McpSyncServerExchange ex, Map<String, Object> args) {
        String client = ex.getClientInfo().name() + " " + ex.getClientInfo().version();
        burpMCP.writeToServerLog("To server", client, "burp-log",
                new GsonBuilder().disableHtmlEscaping().create().toJson(args));
        CallToolResult result;
        try {
            String level = args.containsKey("level") && args.get("level") != null ? args.get("level").toString() : "output";
            String message = args.get("message").toString();
            switch (level) {
                case "output":           api.logging().logToOutput(message); break;
                case "error":            api.logging().logToError(message); break;
                case "debug_event":      api.logging().raiseDebugEvent(message); break;
                case "info_event":       api.logging().raiseInfoEvent(message); break;
                case "error_event":      api.logging().raiseErrorEvent(message); break;
                case "critical_event":   api.logging().raiseCriticalEvent(message); break;
                default:
                    return new CallToolResult(Collections.singletonList(
                            new TextContent("ERROR: unknown level " + level)), true);
            }
            result = new CallToolResult(Collections.singletonList(
                    new TextContent("logged " + level + ": " + message.length() + " chars")), false);
        } catch (Exception e) {
            result = new CallToolResult(Collections.singletonList(
                    new TextContent("ERROR: burp-log failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()))), true);
        }
        burpMCP.writeToServerLog("To client", client, "burp-log", result.toString());
        return result;
    }
}
