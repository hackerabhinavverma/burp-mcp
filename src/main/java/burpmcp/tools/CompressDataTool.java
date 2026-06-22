package burpmcp.tools;

import java.util.Collections;
import java.util.Map;

import com.google.gson.GsonBuilder;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.utilities.CompressionType;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import burpmcp.BurpMCP;

/** Compress/decompress via Burp's CompressionUtils. Output always base64. */
public class CompressDataTool {
    private final MontoyaApi api;
    private final BurpMCP burpMCP;

    public CompressDataTool(MontoyaApi api, BurpMCP burpMCP) {
        this.api = api;
        this.burpMCP = burpMCP;
    }

    public SyncToolSpecification createToolSpecification() {
        String schema = """
        {
            "type": "object",
            "properties": {
                "op": {"type": "string", "enum": ["compress","decompress"]},
                "type": {"type": "string", "enum": ["GZIP","DEFLATE","BROTLI"]},
                "input": {"type": "string", "description": "Input bytes. Treated as UTF-8 unless input_encoding=base64."},
                "input_encoding": {"type": "string", "enum": ["utf8","base64"], "description": "Default utf8"}
            },
            "required": ["op","type","input"]
        }
        """;
        Tool tool = new Tool("compress-data",
                "Compresses or decompresses bytes (GZIP/DEFLATE/BROTLI) via Burp's CompressionUtils. Output is base64.",
                schema);
        return new SyncToolSpecification(tool, this::handle);
    }

    private CallToolResult handle(McpSyncServerExchange ex, Map<String, Object> args) {
        String client = ex.getClientInfo().name() + " " + ex.getClientInfo().version();
        burpMCP.writeToServerLog("To server", client, "compress-data",
                new GsonBuilder().disableHtmlEscaping().create().toJson(args));
        CallToolResult result;
        try {
            String op = args.get("op").toString();
            CompressionType ct = CompressionType.valueOf(args.get("type").toString().toUpperCase());
            String input = args.get("input").toString();
            String enc = args.containsKey("input_encoding") && args.get("input_encoding") != null
                    ? args.get("input_encoding").toString() : "utf8";
            ByteArray bytes = "base64".equals(enc) ? api.utilities().base64Utils().decode(input) : ByteArray.byteArray(input);
            ByteArray out = "compress".equals(op)
                    ? api.utilities().compressionUtils().compress(bytes, ct)
                    : api.utilities().compressionUtils().decompress(bytes, ct);
            String b64 = api.utilities().base64Utils().encodeToString(out);
            result = new CallToolResult(Collections.singletonList(new TextContent(b64)), false);
        } catch (Exception e) {
            result = new CallToolResult(Collections.singletonList(
                    new TextContent("ERROR: compress-data failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()))), true);
        }
        burpMCP.writeToServerLog("To client", client, "compress-data", result.toString());
        return result;
    }
}
