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

/** Encode/decode helpers backed by Burp's utility APIs. */
public class DecodeEncodeTool {

    private final MontoyaApi api;
    private final BurpMCP burpMCP;

    public DecodeEncodeTool(MontoyaApi api, BurpMCP burpMCP) {
        this.api = api;
        this.burpMCP = burpMCP;
    }

    public SyncToolSpecification createToolSpecification() {
        String schema = """
        {
            "type": "object",
            "properties": {
                "op": {
                    "type": "string",
                    "enum": ["base64_encode", "base64_decode", "url_encode", "url_decode", "html_encode", "html_decode"],
                    "description": "Operation to perform"
                },
                "input": {
                    "type": "string",
                    "description": "Input string. For base64_decode the input is the base64 text; the decoded output is returned as a UTF-8 string."
                }
            },
            "required": ["op", "input"]
        }
        """;

        Tool tool = new Tool(
                "decode-encode",
                "Encodes or decodes a string using Burp's base64, URL or HTML utilities",
                schema);
        return new SyncToolSpecification(tool, this::handleToolCall);
    }

    private CallToolResult handleToolCall(McpSyncServerExchange exchange, Map<String, Object> args) {
        String client = exchange.getClientInfo().name() + " " + exchange.getClientInfo().version();
        burpMCP.writeToServerLog("To server", client, "decode-encode",
                new GsonBuilder().disableHtmlEscaping().create().toJson(args));
        CallToolResult result;
        try {
            if (!args.containsKey("op") || !args.containsKey("input")) {
                result = new CallToolResult(Collections.singletonList(
                        new TextContent("ERROR: op and input are required")), true);
            } else {
                String op = args.get("op").toString();
                String input = args.get("input").toString();
                String output;
                switch (op) {
                    case "base64_encode":
                        output = api.utilities().base64Utils().encodeToString(input);
                        break;
                    case "base64_decode":
                        output = api.utilities().base64Utils().decode(input).toString();
                        break;
                    case "url_encode":
                        output = api.utilities().urlUtils().encode(input);
                        break;
                    case "url_decode":
                        output = api.utilities().urlUtils().decode(input);
                        break;
                    case "html_encode":
                        output = api.utilities().htmlUtils().encode(input);
                        break;
                    case "html_decode":
                        output = api.utilities().htmlUtils().decode(input);
                        break;
                    default:
                        return new CallToolResult(Collections.singletonList(
                                new TextContent("ERROR: unknown op: " + op)), true);
                }
                result = new CallToolResult(Collections.singletonList(new TextContent(output)), false);
            }
        } catch (Exception e) {
            result = new CallToolResult(Collections.singletonList(
                    new TextContent("ERROR: decode-encode failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()))), true);
        }
        burpMCP.writeToServerLog("To client", client, "decode-encode", result.toString());
        return result;
    }
}
