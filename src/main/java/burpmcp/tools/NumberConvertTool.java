package burpmcp.tools;

import java.util.Collections;
import java.util.Map;

import com.google.gson.GsonBuilder;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.utilities.NumberUtils;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import burpmcp.BurpMCP;

/** Base conversion via Burp's NumberUtils. */
public class NumberConvertTool {
    private final MontoyaApi api;
    private final BurpMCP burpMCP;

    public NumberConvertTool(MontoyaApi api, BurpMCP burpMCP) {
        this.api = api;
        this.burpMCP = burpMCP;
    }

    public SyncToolSpecification createToolSpecification() {
        String schema = """
        {
            "type": "object",
            "properties": {
                "from": {"type": "string", "enum": ["binary","octal","decimal","hex"], "description": "Source base"},
                "to":   {"type": "string", "enum": ["binary","octal","decimal","hex"], "description": "Target base"},
                "input": {"type": "string", "description": "Value in the source base"}
            },
            "required": ["from","to","input"]
        }
        """;
        Tool tool = new Tool("number-convert",
                "Converts a number between binary/octal/decimal/hex using Burp's NumberUtils",
                schema);
        return new SyncToolSpecification(tool, this::handle);
    }

    private CallToolResult handle(McpSyncServerExchange ex, Map<String, Object> args) {
        String client = ex.getClientInfo().name() + " " + ex.getClientInfo().version();
        burpMCP.writeToServerLog("To server", client, "number-convert",
                new GsonBuilder().disableHtmlEscaping().create().toJson(args));
        CallToolResult result;
        try {
            String from = args.get("from").toString().toLowerCase();
            String to = args.get("to").toString().toLowerCase();
            String input = args.get("input").toString();
            NumberUtils n = api.utilities().numberUtils();
            String out;
            if (from.equals(to)) {
                out = input;
            } else if (from.equals("binary") && to.equals("octal"))      out = n.convertBinaryToOctal(input);
            else if (from.equals("binary") && to.equals("decimal"))      out = n.convertBinaryToDecimal(input);
            else if (from.equals("binary") && to.equals("hex"))          out = n.convertBinaryToHex(input);
            else if (from.equals("octal") && to.equals("binary"))        out = n.convertOctalToBinary(input);
            else if (from.equals("octal") && to.equals("decimal"))       out = n.convertOctalToDecimal(input);
            else if (from.equals("octal") && to.equals("hex"))           out = n.convertOctalToHex(input);
            else if (from.equals("decimal") && to.equals("binary"))      out = n.convertDecimalToBinary(input);
            else if (from.equals("decimal") && to.equals("octal"))       out = n.convertDecimalToOctal(input);
            else if (from.equals("decimal") && to.equals("hex"))         out = n.convertDecimalToHex(input);
            else if (from.equals("hex") && to.equals("binary"))          out = n.convertHexToBinary(input);
            else if (from.equals("hex") && to.equals("octal"))           out = n.convertHexToOctal(input);
            else if (from.equals("hex") && to.equals("decimal"))         out = n.convertHexToDecimal(input);
            else {
                return new CallToolResult(Collections.singletonList(
                        new TextContent("ERROR: unsupported conversion: " + from + " -> " + to)), true);
            }
            result = new CallToolResult(Collections.singletonList(new TextContent(out)), false);
        } catch (Exception e) {
            result = new CallToolResult(Collections.singletonList(
                    new TextContent("ERROR: number-convert failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()))), true);
        }
        burpMCP.writeToServerLog("To client", client, "number-convert", result.toString());
        return result;
    }
}
