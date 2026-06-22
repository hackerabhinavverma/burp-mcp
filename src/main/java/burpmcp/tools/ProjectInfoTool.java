package burpmcp.tools;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.Version;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import burpmcp.BurpMCP;

/** Returns Burp project + version metadata for orientation. */
public class ProjectInfoTool {
    private final MontoyaApi api;
    private final BurpMCP burpMCP;

    public ProjectInfoTool(MontoyaApi api, BurpMCP burpMCP) {
        this.api = api;
        this.burpMCP = burpMCP;
    }

    public SyncToolSpecification createToolSpecification() {
        String schema = "{\"type\":\"object\",\"properties\":{}}";
        Tool tool = new Tool("project-info",
                "Returns Burp version + project name/id so the LLM has session context",
                schema);
        return new SyncToolSpecification(tool, this::handle);
    }

    private CallToolResult handle(McpSyncServerExchange ex, Map<String, Object> args) {
        String client = ex.getClientInfo().name() + " " + ex.getClientInfo().version();
        burpMCP.writeToServerLog("To server", client, "project-info", "{}");
        CallToolResult result;
        try {
            Version v = api.burpSuite().version();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("burp_name", v.name());
            out.put("burp_version", v.toString());
            out.put("burp_build_number", v.buildNumber());
            out.put("burp_edition", v.edition() == null ? null : v.edition().toString());
            out.put("project_name", api.project().name());
            out.put("project_id", api.project().id());
            out.put("extension_filename", api.extension().filename());
            String json = new GsonBuilder().disableHtmlEscaping().serializeNulls().create().toJson(out);
            result = new CallToolResult(Collections.singletonList(new TextContent(json)), false);
        } catch (Exception e) {
            result = new CallToolResult(Collections.singletonList(
                    new TextContent("ERROR: project-info failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()))), true);
        }
        burpMCP.writeToServerLog("To client", client, "project-info", result.toString());
        return result;
    }
}
