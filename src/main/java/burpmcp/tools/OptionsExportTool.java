package burpmcp.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.google.gson.GsonBuilder;

import burp.api.montoya.MontoyaApi;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import burpmcp.BurpMCP;

/** Exports Burp project or user options as JSON. */
public class OptionsExportTool {
    private final MontoyaApi api;
    private final BurpMCP burpMCP;

    public OptionsExportTool(MontoyaApi api, BurpMCP burpMCP) {
        this.api = api;
        this.burpMCP = burpMCP;
    }

    public SyncToolSpecification createToolSpecification() {
        String schema = """
        {
            "type": "object",
            "properties": {
                "scope": {"type": "string", "enum": ["project","user"]},
                "paths": {"type": "array", "items": {"type": "string"}, "description": "Optional dotted-path filters (e.g. proxy.intercept_client_requests). Omit to export everything."}
            },
            "required": ["scope"]
        }
        """;
        Tool tool = new Tool("options-export",
                "Exports Burp project or user options as JSON. Optionally restrict to dotted paths.",
                schema);
        return new SyncToolSpecification(tool, this::handle);
    }

    @SuppressWarnings("unchecked")
    private CallToolResult handle(McpSyncServerExchange ex, Map<String, Object> args) {
        String client = ex.getClientInfo().name() + " " + ex.getClientInfo().version();
        burpMCP.writeToServerLog("To server", client, "options-export",
                new GsonBuilder().disableHtmlEscaping().create().toJson(args));
        CallToolResult result;
        try {
            String scope = args.get("scope").toString();
            List<String> paths = new ArrayList<>();
            if (args.containsKey("paths") && args.get("paths") instanceof List) {
                for (Object o : (List<Object>) args.get("paths")) {
                    if (o != null) paths.add(o.toString());
                }
            }
            String[] arr = paths.toArray(new String[0]);
            String json = "project".equalsIgnoreCase(scope)
                    ? api.burpSuite().exportProjectOptionsAsJson(arr)
                    : api.burpSuite().exportUserOptionsAsJson(arr);
            result = new CallToolResult(Collections.singletonList(new TextContent(json)), false);
        } catch (Exception e) {
            result = new CallToolResult(Collections.singletonList(
                    new TextContent("ERROR: options-export failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()))), true);
        }
        burpMCP.writeToServerLog("To client", client, "options-export", result.toString());
        return result;
    }
}
