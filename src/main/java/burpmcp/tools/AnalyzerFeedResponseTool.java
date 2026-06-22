package burpmcp.tools;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.http.message.responses.analysis.ResponseKeywordsAnalyzer;
import burp.api.montoya.http.message.responses.analysis.ResponseVariationsAnalyzer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import burpmcp.BurpMCP;
import burpmcp.scan.AnalyzerRegistry;

/**
 * Feeds an HTTP response (raw bytes or proxy-entry id) into a previously
 * created analyzer and returns its current variant/invariant state.
 */
public class AnalyzerFeedResponseTool {
    private final MontoyaApi api;
    private final BurpMCP burpMCP;
    private final AnalyzerRegistry registry;
    private final burpmcp.proxy.ProxyTrafficStore proxyStore;

    public AnalyzerFeedResponseTool(MontoyaApi api, BurpMCP burpMCP, AnalyzerRegistry registry,
                                    burpmcp.proxy.ProxyTrafficStore proxyStore) {
        this.api = api;
        this.burpMCP = burpMCP;
        this.registry = registry;
        this.proxyStore = proxyStore;
    }

    public SyncToolSpecification createToolSpecification() {
        String schema = """
        {
            "type": "object",
            "properties": {
                "analyzer_id":     {"type": "string"},
                "response":        {"type": "string", "description": "Raw HTTP response (UTF-8) — used if proxy_entry_id not given"},
                "proxy_entry_id":  {"type": "integer", "description": "Optional: pull the response from list-proxy-traffic entry"},
                "input_encoding":  {"type": "string", "enum": ["utf8","base64"], "description": "Default utf8"}
            },
            "required": ["analyzer_id"]
        }
        """;
        Tool tool = new Tool("analyzer-feed-response",
                "Feeds a response into a keyword/variations analyzer and returns its current state",
                schema);
        return new SyncToolSpecification(tool, this::handle);
    }

    private CallToolResult handle(McpSyncServerExchange ex, Map<String, Object> args) {
        String client = ex.getClientInfo().name() + " " + ex.getClientInfo().version();
        burpMCP.writeToServerLog("To server", client, "analyzer-feed-response",
                new GsonBuilder().disableHtmlEscaping().create().toJson(args));
        CallToolResult result;
        try {
            String id = args.get("analyzer_id").toString();
            HttpResponse resp = resolveResponse(args);
            if (resp == null) {
                return new CallToolResult(Collections.singletonList(
                        new TextContent("ERROR: provide either 'response' or 'proxy_entry_id'")), true);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("analyzer_id", id);
            ResponseKeywordsAnalyzer kw = registry.getKeywords(id);
            ResponseVariationsAnalyzer var = registry.getVariations(id);
            if (kw != null) {
                kw.updateWith(resp);
                out.put("kind", "keywords");
                out.put("variant", asList(kw.variantKeywords()));
                out.put("invariant", asList(kw.invariantKeywords()));
            } else if (var != null) {
                var.updateWith(resp);
                out.put("kind", "variations");
                out.put("variant_attributes", asList(var.variantAttributes()));
                out.put("invariant_attributes", asList(var.invariantAttributes()));
            } else {
                return new CallToolResult(Collections.singletonList(
                        new TextContent("ERROR: unknown analyzer_id " + id)), true);
            }
            result = new CallToolResult(Collections.singletonList(
                    new TextContent(new Gson().toJson(out))), false);
        } catch (Exception e) {
            result = new CallToolResult(Collections.singletonList(
                    new TextContent("ERROR: analyzer-feed-response failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()))), true);
        }
        burpMCP.writeToServerLog("To client", client, "analyzer-feed-response", result.toString());
        return result;
    }

    private HttpResponse resolveResponse(Map<String, Object> args) {
        if (args.containsKey("proxy_entry_id") && args.get("proxy_entry_id") != null) {
            long pid = ((Number) args.get("proxy_entry_id")).longValue();
            burpmcp.proxy.ProxyTrafficStore.Entry e = proxyStore.getById(pid);
            if (e != null && e.responseBytes != null) {
                return HttpResponse.httpResponse(ByteArray.byteArray(e.responseBytes));
            }
        }
        if (args.containsKey("response") && args.get("response") != null) {
            String raw = args.get("response").toString();
            String enc = args.containsKey("input_encoding") && args.get("input_encoding") != null
                    ? args.get("input_encoding").toString() : "utf8";
            ByteArray b = "base64".equals(enc) ? api.utilities().base64Utils().decode(raw) : ByteArray.byteArray(raw);
            return HttpResponse.httpResponse(b);
        }
        return null;
    }

    private static java.util.List<String> asList(Set<?> s) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (Object o : s) out.add(String.valueOf(o));
        return out;
    }
}
