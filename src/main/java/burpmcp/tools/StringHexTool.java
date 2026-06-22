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

/** ASCII <-> hex via Burp's StringUtils. */
public class StringHexTool {
    private final MontoyaApi api;
    private final BurpMCP burpMCP;

    public StringHexTool(MontoyaApi api, BurpMCP burpMCP) {
        this.api = api;
        this.burpMCP = burpMCP;
    }

    public SyncToolSpecification createToolSpecification() {
        String schema = """
        {
            "type": "object",
            "properties": {
                "op": {"type": "string", "enum": ["ascii_to_hex","hex_to_ascii"]},
                "input": {"type": "string"}
            },
            "required": ["op","input"]
        }
        """;
        Tool tool = new Tool("string-hex",
                "Converts ASCII to hex string or hex string to ASCII via Burp's StringUtils",
                schema);
        return new SyncToolSpecification(tool, this::handle);
    }

    private CallToolResult handle(McpSyncServerExchange ex, Map<String, Object> args) {
        String client = ex.getClientInfo().name() + " " + ex.getClientInfo().version();
        burpMCP.writeToServerLog("To server", client, "string-hex",
                new GsonBuilder().disableHtmlEscaping().create().toJson(args));
        CallToolResult result;
        try {
            String op = args.get("op").toString();
            String input = args.get("input").toString();
            String out;
            if ("ascii_to_hex".equals(op)) {
                out = api.utilities().stringUtils().convertAsciiToHexString(input);
            } else if ("hex_to_ascii".equals(op)) {
                out = api.utilities().stringUtils().convertHexStringToAscii(input);
            } else {
                return new CallToolResult(Collections.singletonList(
                        new TextContent("ERROR: unknown op " + op)), true);
            }
            result = new CallToolResult(Collections.singletonList(new TextContent(out)), false);
        } catch (Exception e) {
            result = new CallToolResult(Collections.singletonList(
                    new TextContent("ERROR: string-hex failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()))), true);
        }
        burpMCP.writeToServerLog("To client", client, "string-hex", result.toString());
        return result;
    }
}
