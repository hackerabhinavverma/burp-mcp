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

/** Generates random strings via Burp's RandomUtils. */
public class RandomStringTool {
    private final MontoyaApi api;
    private final BurpMCP burpMCP;

    public RandomStringTool(MontoyaApi api, BurpMCP burpMCP) {
        this.api = api;
        this.burpMCP = burpMCP;
    }

    public SyncToolSpecification createToolSpecification() {
        String schema = """
        {
            "type": "object",
            "properties": {
                "length": {"type": "integer", "description": "Output length"},
                "charset": {"type": "string", "description": "Optional explicit characters to draw from. If omitted, a printable ASCII alphabet is used."}
            },
            "required": ["length"]
        }
        """;
        Tool tool = new Tool("random-string",
                "Generates a random string of the given length using Burp's RandomUtils",
                schema);
        return new SyncToolSpecification(tool, this::handle);
    }

    private CallToolResult handle(McpSyncServerExchange ex, Map<String, Object> args) {
        String client = ex.getClientInfo().name() + " " + ex.getClientInfo().version();
        burpMCP.writeToServerLog("To server", client, "random-string",
                new GsonBuilder().disableHtmlEscaping().create().toJson(args));
        CallToolResult result;
        try {
            int length = ((Number) args.get("length")).intValue();
            String out;
            if (args.containsKey("charset") && args.get("charset") != null) {
                out = api.utilities().randomUtils().randomString(length, args.get("charset").toString());
            } else {
                out = api.utilities().randomUtils().randomString(length);
            }
            result = new CallToolResult(Collections.singletonList(new TextContent(out)), false);
        } catch (Exception e) {
            result = new CallToolResult(Collections.singletonList(
                    new TextContent("ERROR: random-string failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()))), true);
        }
        burpMCP.writeToServerLog("To client", client, "random-string", result.toString());
        return result;
    }
}
