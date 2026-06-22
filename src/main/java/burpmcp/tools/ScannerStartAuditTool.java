package burpmcp.tools;

import java.util.Collections;
import java.util.Map;

import com.google.gson.GsonBuilder;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.scanner.AuditConfiguration;
import burp.api.montoya.scanner.BuiltInAuditConfiguration;
import burp.api.montoya.scanner.audit.Audit;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import burpmcp.BurpMCP;
import burpmcp.models.SavedRequestListModel;
import burpmcp.scan.ScanRegistry;

/** Starts an active or passive audit against a saved request or supplied URL. */
public class ScannerStartAuditTool {
    private final MontoyaApi api;
    private final BurpMCP burpMCP;
    private final SavedRequestListModel saved;
    private final ScanRegistry registry;

    public ScannerStartAuditTool(MontoyaApi api, BurpMCP burpMCP, SavedRequestListModel saved, ScanRegistry registry) {
        this.api = api;
        this.burpMCP = burpMCP;
        this.saved = saved;
        this.registry = registry;
    }

    public SyncToolSpecification createToolSpecification() {
        String schema = """
        {
            "type": "object",
            "properties": {
                "configuration": {
                    "type": "string",
                    "enum": ["LEGACY_ACTIVE_AUDIT_CHECKS","LEGACY_PASSIVE_AUDIT_CHECKS"],
                    "description": "Built-in audit configuration name"
                },
                "saved_ids": {
                    "type": "array",
                    "description": "List of saved-request IDs to feed into the audit",
                    "items": {"type": "integer"}
                },
                "urls": {
                    "type": "array",
                    "description": "Optional URLs to fetch and audit (httpRequestFromUrl will be used)",
                    "items": {"type": "string"}
                }
            },
            "required": ["configuration"]
        }
        """;
        Tool tool = new Tool("scanner-start-audit",
                "Starts a Burp Scanner audit and returns an audit_id for polling via scanner-audit-status",
                schema);
        return new SyncToolSpecification(tool, this::handle);
    }

    @SuppressWarnings("unchecked")
    private CallToolResult handle(McpSyncServerExchange ex, Map<String, Object> args) {
        String client = ex.getClientInfo().name() + " " + ex.getClientInfo().version();
        burpMCP.writeToServerLog("To server", client, "scanner-start-audit",
                new GsonBuilder().disableHtmlEscaping().create().toJson(args));
        CallToolResult result;
        try {
            BuiltInAuditConfiguration cfg = BuiltInAuditConfiguration.valueOf(args.get("configuration").toString());
            Audit audit = api.scanner().startAudit(AuditConfiguration.auditConfiguration(cfg));
            int requestsAdded = 0;
            if (args.containsKey("saved_ids") && args.get("saved_ids") instanceof java.util.List) {
                for (Object o : (java.util.List<Object>) args.get("saved_ids")) {
                    int id = ((Number) o).intValue();
                    for (int i = 0; i < saved.getRowCount(); i++) {
                        if (saved.getEntry(i).getId() == id) {
                            audit.addRequestResponse(saved.getEntry(i).getRequestResponse());
                            requestsAdded++;
                            break;
                        }
                    }
                }
            }
            if (args.containsKey("urls") && args.get("urls") instanceof java.util.List) {
                for (Object o : (java.util.List<Object>) args.get("urls")) {
                    String url = o.toString();
                    burp.api.montoya.http.message.requests.HttpRequest req =
                            burp.api.montoya.http.message.requests.HttpRequest.httpRequestFromUrl(url);
                    HttpRequestResponse rr = api.http().sendRequest(req);
                    audit.addRequestResponse(rr);
                    requestsAdded++;
                }
            }
            String id = registry.addAudit(audit);
            result = new CallToolResult(Collections.singletonList(
                    new TextContent("{\"audit_id\":\"" + id + "\",\"requests_added\":" + requestsAdded + ",\"configuration\":\"" + cfg.name() + "\"}")),
                    false);
        } catch (Exception e) {
            result = new CallToolResult(Collections.singletonList(
                    new TextContent("ERROR: scanner-start-audit failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()))), true);
        }
        burpMCP.writeToServerLog("To client", client, "scanner-start-audit", result.toString());
        return result;
    }
}
