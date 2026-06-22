package burpmcp.tools;

import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;

import com.google.gson.GsonBuilder;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import burpmcp.BurpMCP;

/** Writes text into the Burp UI text component that currently has keyboard focus. */
public class EditorSetTool {
    private final BurpMCP burpMCP;

    public EditorSetTool(BurpMCP burpMCP) {
        this.burpMCP = burpMCP;
    }

    public SyncToolSpecification createToolSpecification() {
        String schema = """
        {
            "type": "object",
            "properties": {
                "text": {"type": "string"}
            },
            "required": ["text"]
        }
        """;
        Tool tool = new Tool("editor-set",
                "Replaces the contents of the Burp UI text component that currently has keyboard focus",
                schema);
        return new SyncToolSpecification(tool, this::handle);
    }

    private CallToolResult handle(McpSyncServerExchange ex, Map<String, Object> args) {
        String client = ex.getClientInfo().name() + " " + ex.getClientInfo().version();
        burpMCP.writeToServerLog("To server", client, "editor-set",
                new GsonBuilder().disableHtmlEscaping().create().toJson(args));
        CallToolResult result;
        try {
            String text = args.get("text").toString();
            AtomicBoolean ok = new AtomicBoolean(false);
            Runnable r = () -> {
                Component c = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                if (c instanceof JTextComponent) {
                    JTextComponent jtc = (JTextComponent) c;
                    if (jtc.isEditable()) {
                        jtc.setText(text);
                        ok.set(true);
                    }
                }
            };
            if (SwingUtilities.isEventDispatchThread()) r.run();
            else SwingUtilities.invokeAndWait(r);
            if (!ok.get()) {
                result = new CallToolResult(Collections.singletonList(
                        new TextContent("ERROR: no editable focused JTextComponent")), true);
            } else {
                result = new CallToolResult(Collections.singletonList(
                        new TextContent("Set " + text.length() + " chars")), false);
            }
        } catch (Exception e) {
            result = new CallToolResult(Collections.singletonList(
                    new TextContent("ERROR: editor-set failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()))), true);
        }
        burpMCP.writeToServerLog("To client", client, "editor-set", result.toString());
        return result;
    }
}
