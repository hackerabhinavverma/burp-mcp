package burpmcp.tools;

import java.util.Collections;
import java.util.Map;

import com.google.gson.GsonBuilder;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import burpmcp.BurpMCP;

/** Adds a manually-built HTTP/1.1 request (and optional response) to Burp's site map. */
public class SitemapAddTool {
    private final MontoyaApi api;
    private final BurpMCP burpMCP;

    public SitemapAddTool(MontoyaApi api, BurpMCP burpMCP) {
        this.api = api;
        this.burpMCP = burpMCP;
    }

    public SyncToolSpecification createToolSpecification() {
        String schema = """
        {
            "type": "object",
            "properties": {
                "data":   {"type": "string", "description": "Raw HTTP/1.1 request"},
                "host":   {"type": "string"},
                "port":   {"type": "integer"},
                "secure": {"type": "boolean"},
                "response": {"type": "string", "description": "Optional raw HTTP/1.1 response"}
            },
            "required": ["data","host","port","secure"]
        }
        """;
        Tool tool = new Tool("sitemap-add",
                "Adds a request (and optional response) to Burp's site map so it appears in Target → Site map",
                schema);
        return new SyncToolSpecification(tool, this::handle);
    }

    private CallToolResult handle(McpSyncServerExchange ex, Map<String, Object> args) {
        String client = ex.getClientInfo().name() + " " + ex.getClientInfo().version();
        burpMCP.writeToServerLog("To server", client, "sitemap-add",
                new GsonBuilder().disableHtmlEscaping().create().toJson(args));
        CallToolResult result;
        try {
            String data = args.get("data").toString();
            String host = args.get("host").toString();
            int port = ((Number) args.get("port")).intValue();
            boolean secure = Boolean.parseBoolean(args.get("secure").toString());
            HttpService svc = HttpService.httpService(host, port, secure);
            HttpRequest req = HttpRequest.httpRequest(svc, ByteArray.byteArray(data));
            HttpRequestResponse rr;
            if (args.containsKey("response") && args.get("response") != null && !args.get("response").toString().isEmpty()) {
                HttpResponse resp = HttpResponse.httpResponse(ByteArray.byteArray(args.get("response").toString()));
                rr = HttpRequestResponse.httpRequestResponse(req, resp);
            } else {
                rr = HttpRequestResponse.httpRequestResponse(req, null);
            }
            api.siteMap().add(rr);
            result = new CallToolResult(Collections.singletonList(
                    new TextContent("Added to site map: " + req.url())), false);
        } catch (Exception e) {
            result = new CallToolResult(Collections.singletonList(
                    new TextContent("ERROR: sitemap-add failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()))), true);
        }
        burpMCP.writeToServerLog("To client", client, "sitemap-add", result.toString());
        return result;
    }
}
