package burpmcp.tools;

import java.time.ZonedDateTime;
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

/** Adds or updates a cookie in Burp's cookie jar. */
public class CookieSetTool {
    private final MontoyaApi api;
    private final BurpMCP burpMCP;

    public CookieSetTool(MontoyaApi api, BurpMCP burpMCP) {
        this.api = api;
        this.burpMCP = burpMCP;
    }

    public SyncToolSpecification createToolSpecification() {
        String schema = """
        {
            "type": "object",
            "properties": {
                "name":   {"type": "string"},
                "value":  {"type": "string"},
                "domain": {"type": "string"},
                "path":   {"type": "string", "description": "Default /"},
                "expires_iso": {"type": "string", "description": "Optional ISO-8601 zoned date-time; null means session cookie."}
            },
            "required": ["name","value","domain"]
        }
        """;
        Tool tool = new Tool("cookie-set",
                "Adds or updates a cookie in Burp's cookie jar",
                schema);
        return new SyncToolSpecification(tool, this::handle);
    }

    private CallToolResult handle(McpSyncServerExchange ex, Map<String, Object> args) {
        String client = ex.getClientInfo().name() + " " + ex.getClientInfo().version();
        burpMCP.writeToServerLog("To server", client, "cookie-set",
                new GsonBuilder().disableHtmlEscaping().create().toJson(args));
        CallToolResult result;
        try {
            String name = args.get("name").toString();
            String value = args.get("value").toString();
            String domain = args.get("domain").toString();
            String path = args.containsKey("path") && args.get("path") != null ? args.get("path").toString() : "/";
            ZonedDateTime expires = null;
            if (args.containsKey("expires_iso") && args.get("expires_iso") != null) {
                expires = ZonedDateTime.parse(args.get("expires_iso").toString());
            }
            api.http().cookieJar().setCookie(name, value, path, domain, expires);
            result = new CallToolResult(Collections.singletonList(
                    new TextContent("Cookie set: " + name + " for " + domain + path)), false);
        } catch (Exception e) {
            result = new CallToolResult(Collections.singletonList(
                    new TextContent("ERROR: cookie-set failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()))), true);
        }
        burpMCP.writeToServerLog("To client", client, "cookie-set", result.toString());
        return result;
    }
}
