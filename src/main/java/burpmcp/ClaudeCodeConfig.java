package burpmcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Reads and merges the burpmcp MCP server entry into an MCP client's
 * configuration file. Used for both Claude Code (~/.claude.json) and
 * Cursor (~/.cursor/mcp.json) — both clients share the same
 * {@code mcpServers.<name> = {type, url}} schema.
 *
 * Class name kept as ClaudeCodeConfig for source-control friendliness;
 * each instance represents a target client.
 */
public final class ClaudeCodeConfig {

    public static final String SERVER_KEY = "burpmcp";

    public static final ClaudeCodeConfig CLAUDE_CODE = new ClaudeCodeConfig(
            "Claude Code",
            Paths.get(System.getProperty("user.home"), ".claude.json"));

    public static final ClaudeCodeConfig CURSOR = new ClaudeCodeConfig(
            "Cursor",
            Paths.get(System.getProperty("user.home"), ".cursor", "mcp.json"));

    private final String displayName;
    private final Path configPath;

    private ClaudeCodeConfig(String displayName, Path configPath) {
        this.displayName = displayName;
        this.configPath = configPath;
    }

    public String displayName() {
        return displayName;
    }

    public Path configPath() {
        return configPath;
    }

    /** True when the existing config already has an entry for SERVER_KEY whose url matches. */
    public boolean isRegistered(String sseUrl) {
        try {
            if (!Files.exists(configPath)) {
                return false;
            }
            String text = new String(Files.readAllBytes(configPath), StandardCharsets.UTF_8);
            JsonElement root = JsonParser.parseString(text);
            if (root == null || !root.isJsonObject()) return false;
            JsonObject obj = root.getAsJsonObject();
            if (!obj.has("mcpServers") || !obj.get("mcpServers").isJsonObject()) return false;
            JsonObject servers = obj.getAsJsonObject("mcpServers");
            if (!servers.has(SERVER_KEY) || !servers.get(SERVER_KEY).isJsonObject()) return false;
            JsonObject entry = servers.getAsJsonObject(SERVER_KEY);
            String url = entry.has("url") && !entry.get("url").isJsonNull() ? entry.get("url").getAsString() : null;
            return sseUrl.equals(url);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Adds or updates the burpmcp entry. Atomic write where supported,
     * with fallback to non-atomic replace if ATOMIC_MOVE is unavailable.
     * Creates parent directories if missing (Cursor's ~/.cursor may not exist).
     */
    public void register(String sseUrl) throws IOException {
        if (configPath.getParent() != null) {
            Files.createDirectories(configPath.getParent());
        }

        JsonObject root;
        if (Files.exists(configPath)) {
            String text = new String(Files.readAllBytes(configPath), StandardCharsets.UTF_8);
            JsonElement parsed = text.isBlank() ? null : JsonParser.parseString(text);
            if (parsed != null && parsed.isJsonObject()) {
                root = parsed.getAsJsonObject();
            } else {
                root = new JsonObject();
            }
        } else {
            root = new JsonObject();
        }

        JsonObject servers;
        if (root.has("mcpServers") && root.get("mcpServers").isJsonObject()) {
            servers = root.getAsJsonObject("mcpServers");
        } else {
            servers = new JsonObject();
            root.add("mcpServers", servers);
        }

        JsonObject entry = new JsonObject();
        entry.addProperty("type", "sse");
        entry.addProperty("url", sseUrl);
        servers.add(SERVER_KEY, entry);

        Gson gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
        String out = gson.toJson(root);
        Path tmp = configPath.resolveSibling(configPath.getFileName().toString() + ".burpmcp.tmp");
        Files.write(tmp, out.getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(tmp, configPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            Files.move(tmp, configPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
