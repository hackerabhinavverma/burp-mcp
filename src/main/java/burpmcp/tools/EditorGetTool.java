package burpmcp.tools;

import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;

import com.google.gson.GsonBuilder;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import burpmcp.BurpMCP;

/** Reads whatever text component currently has keyboard focus in Burp. */
public class EditorGetTool {
    private final BurpMCP burpMCP;

    public EditorGetTool(BurpMCP burpMCP) {
        this.burpMCP = burpMCP;
    }

    public SyncToolSpecification createToolSpecification() {
        String schema = "{\"type\":\"object\",\"properties\":{}}";
        Tool tool = new Tool("editor-get",
                "Returns text from the Burp UI text component that currently has keyboard focus (Repeater/Decoder/Comparer/etc.)",
                schema);
        return new SyncToolSpecification(tool, this::handle);
    }

    private CallToolResult handle(McpSyncServerExchange ex, Map<String, Object> args) {
        String client = ex.getClientInfo().name() + " " + ex.getClientInfo().version();
        burpMCP.writeToServerLog("To server", client, "editor-get", "{}");
        CallToolResult result;
        try {
            AtomicReference<String> out = new AtomicReference<>(null);
            Runnable r = () -> {
                Component c = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                if (c instanceof JTextComponent) {
                    out.set(((JTextComponent) c).getText());
                }
            };
            if (SwingUtilities.isEventDispatchThread()) r.run();
            else SwingUtilities.invokeAndWait(r);
            String text = out.get();
            if (text == null) {
                result = new CallToolResult(Collections.singletonList(
                        new TextContent("ERROR: no focused JTextComponent found")), true);
            } else {
                result = new CallToolResult(Collections.singletonList(new TextContent(text)), false);
            }
        } catch (Exception e) {
            result = new CallToolResult(Collections.singletonList(
                    new TextContent("ERROR: editor-get failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()))), true);
        }
        burpMCP.writeToServerLog("To client", client, "editor-get",
                new GsonBuilder().disableHtmlEscaping().create().toJson(result.toString()));
        return result;
    }
}
