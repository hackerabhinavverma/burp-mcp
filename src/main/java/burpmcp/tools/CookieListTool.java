package burpmcp.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.Cookie;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import burpmcp.BurpMCP;

/** Lists cookies currently held by Burp's cookie jar. */
public class CookieListTool {
    private final MontoyaApi api;
    private final BurpMCP burpMCP;

    public CookieListTool(MontoyaApi api, BurpMCP burpMCP) {
        this.api = api;
        this.burpMCP = burpMCP;
    }

    public SyncToolSpecification createToolSpecification() {
        String schema = """
        {
            "type": "object",
            "properties": {
                "domain_contains": {"type": "string", "description": "Optional substring filter on domain"},
                "name_contains":   {"type": "string", "description": "Optional substring filter on name"}
            }
        }
        """;
        Tool tool = new Tool("cookie-list",
                "Lists cookies from Burp's cookie jar with optional substring filters",
                schema);
        return new SyncToolSpecification(tool, this::handle);
    }

    private CallToolResult handle(McpSyncServerExchange ex, Map<String, Object> args) {
        String client = ex.getClientInfo().name() + " " + ex.getClientInfo().version();
        burpMCP.writeToServerLog("To server", client, "cookie-list",
                new GsonBuilder().disableHtmlEscaping().create().toJson(args));
        CallToolResult result;
        try {
            String domSub = args.containsKey("domain_contains") && args.get("domain_contains") != null
                    ? args.get("domain_contains").toString().toLowerCase() : null;
            String nameSub = args.containsKey("name_contains") && args.get("name_contains") != null
                    ? args.get("name_contains").toString().toLowerCase() : null;
            List<Cookie> all = api.http().cookieJar().cookies();
            List<Map<String, Object>> out = new ArrayList<>();
            for (Cookie c : all) {
                String dom = c.domain() == null ? "" : c.domain();
                String name = c.name() == null ? "" : c.name();
                if (domSub != null && !dom.toLowerCase().contains(domSub)) continue;
                if (nameSub != null && !name.toLowerCase().contains(nameSub)) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", name);
                row.put("value", c.value());
                row.put("domain", dom);
                row.put("path", c.path());
                row.put("expiration", c.expiration().map(Object::toString).orElse(null));
                out.add(row);
            }
            Gson gson = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
            String json = gson.toJson(Map.of("count", out.size(), "cookies", out));
            result = new CallToolResult(Collections.singletonList(new TextContent(json)), false);
        } catch (Exception e) {
            result = new CallToolResult(Collections.singletonList(
                    new TextContent("ERROR: cookie-list failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()))), true);
        }
        burpMCP.writeToServerLog("To client", client, "cookie-list", result.toString());
        return result;
    }
}
