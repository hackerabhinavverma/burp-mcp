package burpmcp.tools;

import java.util.Collections;
import java.util.Map;

import com.google.gson.GsonBuilder;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.utilities.json.JsonUtils;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import burpmcp.BurpMCP;

/** Reads, adds, updates, or removes values in a JSON document via Burp's JsonUtils. */
public class JsonQueryTool {

    private final MontoyaApi api;
    private final BurpMCP burpMCP;

    public JsonQueryTool(MontoyaApi api, BurpMCP burpMCP) {
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
                    "enum": ["read", "read_string", "read_boolean", "read_long", "read_double", "is_valid", "add", "update", "remove"],
                    "description": "JSON operation to perform"
                },
                "json": {"type": "string", "description": "Input JSON document"},
                "path": {"type": "string", "description": "JSON path (Burp JsonUtils syntax). Not required for is_valid."},
                "value": {"type": "string", "description": "Value (for add/update). Must be a valid JSON literal e.g. \\"foo\\" or 42 or true."}
            },
            "required": ["op", "json"]
        }
        """;

        Tool tool = new Tool(
                "json-query",
                "Reads/edits a JSON document via Burp's JsonUtils (read, read_string/boolean/long/double, is_valid, add, update, remove)",
                schema);
        return new SyncToolSpecification(tool, this::handleToolCall);
    }

    private CallToolResult handleToolCall(McpSyncServerExchange exchange, Map<String, Object> args) {
        String client = exchange.getClientInfo().name() + " " + exchange.getClientInfo().version();
        burpMCP.writeToServerLog("To server", client, "json-query",
                new GsonBuilder().disableHtmlEscaping().create().toJson(args));
        CallToolResult result;
        try {
            if (!args.containsKey("op") || !args.containsKey("json")) {
                return logAndReturn(client, new CallToolResult(Collections.singletonList(
                        new TextContent("ERROR: op and json are required")), true));
            }
            JsonUtils u = api.utilities().jsonUtils();
            String op = args.get("op").toString();
            String json = args.get("json").toString();
            String path = args.containsKey("path") && args.get("path") != null ? args.get("path").toString() : null;
            String value = args.containsKey("value") && args.get("value") != null ? args.get("value").toString() : null;

            switch (op) {
                case "is_valid":
                    result = new CallToolResult(Collections.singletonList(
                            new TextContent("{\"valid\":" + u.isValidJson(json) + "}")), false);
                    break;
                case "read":
                    requirePath(op, path);
                    result = new CallToolResult(Collections.singletonList(
                            new TextContent(String.valueOf(u.read(json, path)))), false);
                    break;
                case "read_string":
                    requirePath(op, path);
                    result = new CallToolResult(Collections.singletonList(
                            new TextContent(String.valueOf(u.readString(json, path)))), false);
                    break;
                case "read_boolean":
                    requirePath(op, path);
                    result = new CallToolResult(Collections.singletonList(
                            new TextContent(String.valueOf(u.readBoolean(json, path)))), false);
                    break;
                case "read_long":
                    requirePath(op, path);
                    result = new CallToolResult(Collections.singletonList(
                            new TextContent(String.valueOf(u.readLong(json, path)))), false);
                    break;
                case "read_double":
                    requirePath(op, path);
                    result = new CallToolResult(Collections.singletonList(
                            new TextContent(String.valueOf(u.readDouble(json, path)))), false);
                    break;
                case "add":
                    requirePath(op, path);
                    if (value == null) throw new IllegalArgumentException("value required for add");
                    result = new CallToolResult(Collections.singletonList(
                            new TextContent(u.add(json, path, value))), false);
                    break;
                case "update":
                    requirePath(op, path);
                    if (value == null) throw new IllegalArgumentException("value required for update");
                    result = new CallToolResult(Collections.singletonList(
                            new TextContent(u.update(json, path, value))), false);
                    break;
                case "remove":
                    requirePath(op, path);
                    result = new CallToolResult(Collections.singletonList(
                            new TextContent(u.remove(json, path))), false);
                    break;
                default:
                    result = new CallToolResult(Collections.singletonList(
                            new TextContent("ERROR: unknown op: " + op)), true);
            }
        } catch (Exception e) {
            result = new CallToolResult(Collections.singletonList(
                    new TextContent("ERROR: json-query failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()))), true);
        }
        return logAndReturn(client, result);
    }

    private void requirePath(String op, String path) {
        if (path == null) {
            throw new IllegalArgumentException("path required for op '" + op + "'");
        }
    }

    private CallToolResult logAndReturn(String client, CallToolResult result) {
        burpMCP.writeToServerLog("To client", client, "json-query", result.toString());
        return result;
    }
}
