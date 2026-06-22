package burpmcp.tools;

import java.util.Collections;
import java.util.Map;

import com.google.gson.GsonBuilder;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.responses.analysis.ResponseVariationsAnalyzer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import burpmcp.BurpMCP;
import burpmcp.scan.AnalyzerRegistry;

/** Creates a Burp ResponseVariationsAnalyzer for detecting attribute-level changes across responses. */
public class AnalyzerCreateVariationsTool {
    private final MontoyaApi api;
    private final BurpMCP burpMCP;
    private final AnalyzerRegistry registry;

    public AnalyzerCreateVariationsTool(MontoyaApi api, BurpMCP burpMCP, AnalyzerRegistry registry) {
        this.api = api;
        this.burpMCP = burpMCP;
        this.registry = registry;
    }

    public SyncToolSpecification createToolSpecification() {
        String schema = "{\"type\":\"object\",\"properties\":{}}";
        Tool tool = new Tool("analyzer-create-variations",
                "Creates a Burp ResponseVariationsAnalyzer; feed responses with analyzer-feed-response",
                schema);
        return new SyncToolSpecification(tool, this::handle);
    }

    private CallToolResult handle(McpSyncServerExchange ex, Map<String, Object> args) {
        String client = ex.getClientInfo().name() + " " + ex.getClientInfo().version();
        burpMCP.writeToServerLog("To server", client, "analyzer-create-variations", "{}");
        CallToolResult result;
        try {
            ResponseVariationsAnalyzer a = api.http().createResponseVariationsAnalyzer();
            String id = registry.addVariations(a);
            result = new CallToolResult(Collections.singletonList(
                    new TextContent("{\"analyzer_id\":\"" + id + "\"}")), false);
        } catch (Exception e) {
            result = new CallToolResult(Collections.singletonList(
                    new TextContent("ERROR: analyzer-create-variations failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()))), true);
        }
        burpMCP.writeToServerLog("To client", client, "analyzer-create-variations", result.toString());
        return result;
    }
}
