package burpmcp.tools;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.google.gson.GsonBuilder;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.scanner.Crawl;
import burp.api.montoya.scanner.CrawlConfiguration;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import burpmcp.BurpMCP;
import burpmcp.scan.ScanRegistry;

/** Starts a Burp Scanner crawl from one or more seed URLs. */
public class ScannerStartCrawlTool {
    private final MontoyaApi api;
    private final BurpMCP burpMCP;
    private final ScanRegistry registry;

    public ScannerStartCrawlTool(MontoyaApi api, BurpMCP burpMCP, ScanRegistry registry) {
        this.api = api;
        this.burpMCP = burpMCP;
        this.registry = registry;
    }

    public SyncToolSpecification createToolSpecification() {
        String schema = """
        {
            "type": "object",
            "properties": {
                "seed_urls": {"type": "array", "items": {"type": "string"}, "minItems": 1}
            },
            "required": ["seed_urls"]
        }
        """;
        Tool tool = new Tool("scanner-start-crawl",
                "Starts a Burp crawler from the supplied seed URLs and returns a crawl_id",
                schema);
        return new SyncToolSpecification(tool, this::handle);
    }

    @SuppressWarnings("unchecked")
    private CallToolResult handle(McpSyncServerExchange ex, Map<String, Object> args) {
        String client = ex.getClientInfo().name() + " " + ex.getClientInfo().version();
        burpMCP.writeToServerLog("To server", client, "scanner-start-crawl",
                new GsonBuilder().disableHtmlEscaping().create().toJson(args));
        CallToolResult result;
        try {
            List<Object> raw = (List<Object>) args.get("seed_urls");
            String[] urls = new String[raw.size()];
            for (int i = 0; i < raw.size(); i++) urls[i] = raw.get(i).toString();
            Crawl c = api.scanner().startCrawl(CrawlConfiguration.crawlConfiguration(urls));
            String id = registry.addCrawl(c);
            result = new CallToolResult(Collections.singletonList(
                    new TextContent("{\"crawl_id\":\"" + id + "\",\"seed_urls\":" + urls.length + "}")), false);
        } catch (Exception e) {
            result = new CallToolResult(Collections.singletonList(
                    new TextContent("ERROR: scanner-start-crawl failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()))), true);
        }
        burpMCP.writeToServerLog("To client", client, "scanner-start-crawl", result.toString());
        return result;
    }
}
