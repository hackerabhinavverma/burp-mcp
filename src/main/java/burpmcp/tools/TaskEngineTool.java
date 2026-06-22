package burpmcp.tools;

import java.util.Collections;
import java.util.Map;

import com.google.gson.GsonBuilder;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.burpsuite.TaskExecutionEngine;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import burpmcp.BurpMCP;

/** Reads or mutates Burp's task execution engine state (RUNNING / PAUSED). */
public class TaskEngineTool {
    private final MontoyaApi api;
    private final BurpMCP burpMCP;

    public TaskEngineTool(MontoyaApi api, BurpMCP burpMCP) {
        this.api = api;
        this.burpMCP = burpMCP;
    }

    public SyncToolSpecification createToolSpecification() {
        String schema = """
        {
            "type": "object",
            "properties": {
                "action": {"type": "string", "enum": ["status","run","pause"], "description": "status = read; run = RUNNING; pause = PAUSED"}
            },
            "required": ["action"]
        }
        """;
        Tool tool = new Tool("task-engine",
                "Reads or sets Burp's task execution engine state (controls Scanner/Crawler work).",
                schema);
        return new SyncToolSpecification(tool, this::handle);
    }

    private CallToolResult handle(McpSyncServerExchange ex, Map<String, Object> args) {
        String client = ex.getClientInfo().name() + " " + ex.getClientInfo().version();
        burpMCP.writeToServerLog("To server", client, "task-engine",
                new GsonBuilder().disableHtmlEscaping().create().toJson(args));
        CallToolResult result;
        try {
            String action = args.get("action").toString().toLowerCase();
            TaskExecutionEngine eng = api.burpSuite().taskExecutionEngine();
            switch (action) {
                case "run":
                    eng.setState(TaskExecutionEngine.TaskExecutionEngineState.RUNNING);
                    break;
                case "pause":
                    eng.setState(TaskExecutionEngine.TaskExecutionEngineState.PAUSED);
                    break;
                case "status":
                    break;
                default:
                    return new CallToolResult(Collections.singletonList(
                            new TextContent("ERROR: action must be status|run|pause")), true);
            }
            String state = eng.getState().name();
            result = new CallToolResult(Collections.singletonList(
                    new TextContent("{\"state\":\"" + state + "\"}")), false);
        } catch (Exception e) {
            result = new CallToolResult(Collections.singletonList(
                    new TextContent("ERROR: task-engine failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()))), true);
        }
        burpMCP.writeToServerLog("To client", client, "task-engine", result.toString());
        return result;
    }
}
