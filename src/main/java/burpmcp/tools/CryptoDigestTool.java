package burpmcp.tools;

import java.util.Collections;
import java.util.Map;

import com.google.gson.GsonBuilder;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.utilities.DigestAlgorithm;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import burpmcp.BurpMCP;

/** Generates a cryptographic digest via Burp's CryptoUtils. */
public class CryptoDigestTool {

    private final MontoyaApi api;
    private final BurpMCP burpMCP;

    public CryptoDigestTool(MontoyaApi api, BurpMCP burpMCP) {
        this.api = api;
        this.burpMCP = burpMCP;
    }

    public SyncToolSpecification createToolSpecification() {
        String schema = """
        {
            "type": "object",
            "properties": {
                "input": {
                    "type": "string",
                    "description": "Bytes to digest. Treated as UTF-8 unless input_encoding=base64."
                },
                "algorithm": {
                    "type": "string",
                    "description": "DigestAlgorithm enum name (e.g. MD5, SHA_1, SHA_256, SHA_512, SHA3_256). See Burp Montoya DigestAlgorithm."
                },
                "input_encoding": {
                    "type": "string",
                    "enum": ["utf8", "base64"],
                    "description": "Treat input as UTF-8 (default) or as base64-encoded bytes"
                }
            },
            "required": ["input", "algorithm"]
        }
        """;

        Tool tool = new Tool(
                "crypto-digest",
                "Computes a cryptographic digest (MD5/SHA family etc.) using Burp's CryptoUtils",
                schema);
        return new SyncToolSpecification(tool, this::handleToolCall);
    }

    private CallToolResult handleToolCall(McpSyncServerExchange exchange, Map<String, Object> args) {
        String client = exchange.getClientInfo().name() + " " + exchange.getClientInfo().version();
        burpMCP.writeToServerLog("To server", client, "crypto-digest",
                new GsonBuilder().disableHtmlEscaping().create().toJson(args));
        CallToolResult result;
        try {
            if (!args.containsKey("input") || !args.containsKey("algorithm")) {
                result = new CallToolResult(Collections.singletonList(
                        new TextContent("ERROR: input and algorithm are required")), true);
            } else {
                String input = args.get("input").toString();
                String algoName = args.get("algorithm").toString().toUpperCase();
                String inputEncoding = args.containsKey("input_encoding") && args.get("input_encoding") != null
                        ? args.get("input_encoding").toString().toLowerCase() : "utf8";

                DigestAlgorithm algo;
                try {
                    algo = DigestAlgorithm.valueOf(algoName);
                } catch (IllegalArgumentException ex) {
                    return new CallToolResult(Collections.singletonList(
                            new TextContent("ERROR: unknown algorithm '" + algoName + "'. Use a DigestAlgorithm enum name like MD5, SHA_1, SHA_256, SHA_512, SHA3_256.")), true);
                }

                ByteArray bytes;
                if ("base64".equals(inputEncoding)) {
                    bytes = api.utilities().base64Utils().decode(input);
                } else {
                    bytes = ByteArray.byteArray(input);
                }

                ByteArray digest = api.utilities().cryptoUtils().generateDigest(bytes, algo);
                byte[] raw = digest.getBytes();
                StringBuilder hex = new StringBuilder(raw.length * 2);
                for (byte b : raw) {
                    hex.append(String.format("%02x", b & 0xff));
                }
                String base64Out = api.utilities().base64Utils().encodeToString(digest);
                result = new CallToolResult(Collections.singletonList(
                        new TextContent("{\"algorithm\":\"" + algoName + "\",\"digest_hex\":\"" + hex
                                + "\",\"digest_base64\":\"" + base64Out + "\"}")), false);
            }
        } catch (Exception e) {
            result = new CallToolResult(Collections.singletonList(
                    new TextContent("ERROR: crypto-digest failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()))), true);
        }
        burpMCP.writeToServerLog("To client", client, "crypto-digest", result.toString());
        return result;
    }
}
