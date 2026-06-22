package burpmcp.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.google.gson.GsonBuilder;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.responses.analysis.ResponseKeywordsAnalyzer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import burpmcp.BurpMCP;
import burpmcp.scan.AnalyzerRegistry;

/** Creates a keywords analyzer that classifies tracked keywords as variant/invariant across feeds. */
public class AnalyzerCreateKeywordsTool {
    private final MontoyaApi api;
    private final BurpMCP burpMCP;
    private final AnalyzerRegistry registry;

    public AnalyzerCreateKeywordsTool(MontoyaApi api, BurpMCP burpMCP, AnalyzerRegistry registry) {
        this.api = api;
        this.burpMCP = burpMCP;
        this.registry = registry;
    }

    public SyncToolSpecification createToolSpecification() {
        String schema = """
        {
            "type": "object",
            "properties": {
                "keywords": {"type": "array", "items": {"type": "string"}, "minItems": 1}
            },
            "required": ["keywords"]
        }
        """;
        Tool tool = new Tool("analyzer-create-keywords",
                "Creates a Burp ResponseKeywordsAnalyzer; feed responses with analyzer-feed-response",
                schema);
        return new SyncToolSpecification(tool, this::handle);
    }

    @SuppressWarnings("unchecked")
    private CallToolResult handle(McpSyncServerExchange ex, Map<String, Object> args) {
        String client = ex.getClientInfo().name() + " " + ex.getClientInfo().version();
        burpMCP.writeToServerLog("To server", client, "analyzer-create-keywords",
                new GsonBuilder().disableHtmlEscaping().create().toJson(args));
        CallToolResult result;
        try {
            List<Object> raw = (List<Object>) args.get("keywords");
            List<String> kw = new ArrayList<>();
            for (Object o : raw) kw.add(o.toString());
            ResponseKeywordsAnalyzer a = api.http().createResponseKeywordsAnalyzer(kw);
            String id = registry.addKeywords(a);
            result = new CallToolResult(Collections.singletonList(
                    new TextContent("{\"analyzer_id\":\"" + id + "\",\"keywords\":" + kw.size() + "}")), false);
        } catch (Exception e) {
            result = new CallToolResult(Collections.singletonList(
                    new TextContent("ERROR: analyzer-create-keywords failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()))), true);
        }
        burpMCP.writeToServerLog("To client", client, "analyzer-create-keywords", result.toString());
        return result;
    }
}
