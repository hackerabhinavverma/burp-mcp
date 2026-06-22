package burpmcp;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;
import burp.api.montoya.collaborator.CollaboratorClient;
import javax.swing.*;
import java.awt.*;
import java.util.Collections;
import java.util.List;
import java.time.ZonedDateTime;
import java.util.ArrayList;

import burpmcp.ui.SentRequestLogsPanel;
import burpmcp.ui.SavedRequestLogsPanel;
import burpmcp.ui.ServerLogsPanel;
import burpmcp.models.SavedRequestListModel;
import burpmcp.models.SentRequestListModel;
import burpmcp.models.ServerLogListModel;
import burpmcp.proxy.ProxyTrafficStore;
import burpmcp.proxy.ProxyTapHandler;
import burpmcp.proxy.AutoSaveHttpHandler;
import burpmcp.ui.StatusDot;

public class BurpMCP implements BurpExtension {
    private MontoyaApi api;
    private SavedRequestListModel savedRequestListModel;
    private ServerLogListModel serverLogListModel;
    private SentRequestListModel sentRequestListModel;
    private JPanel extensionPanel;
    private SavedRequestLogsPanel savedRequestLogsPanel;
    private ServerLogsPanel serverLogsPanel;
    private SentRequestLogsPanel requestLogsPanel;
    private JToggleButton mcpServerToggleButton;
    private JToggleButton crlfToggleButton;
    private JButton registerClaudeButton;
    private JButton registerCursorButton;
    private JLabel connectedClaudeLabel;
    private JLabel connectedCursorLabel;
    private StatusDot serverStatusDot;
    private JLabel serverStatusLabel;
    private Timer serverStatusTimer;
    private boolean mcpServerEnabled;
    public boolean crlfReplace;
    public boolean autoSaveInScope;
    private boolean clientAutoPrompted;
    private JToggleButton autoSaveToggleButton;
    private MCPServer mcpServer;
    private JTextField hostField;
    private JTextField portField;
    private String serverHost;
    private Integer serverPort;
    private BurpMCPPersistence persistence;
    public CollaboratorClient collaboratorClient;
    public List<String[]> retrievedInteractions;
    public ProxyTrafficStore proxyTrafficStore;

    @Override
    public void initialize(MontoyaApi api) {
        this.api = api;
        api.extension().setName("BurpMCP");
        persistence = new BurpMCPPersistence(api);
        
        // Initialize our models
        savedRequestListModel = new SavedRequestListModel();
        serverLogListModel = new ServerLogListModel();
        sentRequestListModel = new SentRequestListModel();
        
        // Restore state from persistence
        persistence.restoreState(savedRequestListModel, sentRequestListModel, serverLogListModel);
        
        // Initialize live proxy traffic store and register proxy hooks
        proxyTrafficStore = new ProxyTrafficStore();
        new ProxyTapHandler(api, proxyTrafficStore, this).register();

        // Auto-save in-scope traffic from non-proxy Burp tools (Target, Scanner,
        // Repeater, Intruder, Crawler …) into the saved-requests list when the
        // auto-save toggle is on.
        new AutoSaveHttpHandler(api, this).register();

        // Initialize MCP server
        mcpServer = new MCPServer(api, this);
        mcpServer.setSavedRequestListModel(savedRequestListModel);
        mcpServer.setProxyTrafficStore(proxyTrafficStore);
        
        // Register unloading handler to stop server
        api.extension().registerUnloadingHandler(() -> {
            if (serverStatusTimer != null) {
                serverStatusTimer.stop();
            }
            if (mcpServer.isRunning()) {
                mcpServer.stop();
            }
            // Save the current state before unloading
            persistence.saveState(savedRequestListModel, sentRequestListModel, serverLogListModel);
        });
        
        // Register context menu item
        api.userInterface().registerContextMenuItemsProvider(createContextMenuItemsProvider());
        
        // Create the extension UI tab
        extensionPanel = createExtensionPanel();
        api.userInterface().registerSuiteTab("BurpMCP", extensionPanel);

        collaboratorClient = persistence.restoreCollaboratorClient();
        retrievedInteractions = persistence.restoreRetrievedInteractions();
        
        api.logging().logToOutput("BurpMCP loaded successfully.");

        // Log existing registrations + offer one-time auto-prompt for any client still missing.
        SwingUtilities.invokeLater(() -> {
            logClientRegistrationStatus();
            maybePromptClientRegistration();
        });
    }

    private void logClientRegistrationStatus() {
        String url = computeSseUrl();
        ClaudeCodeConfig[] targets = { ClaudeCodeConfig.CLAUDE_CODE, ClaudeCodeConfig.CURSOR };
        java.util.List<String> installed = new java.util.ArrayList<>();
        java.util.List<String> missing = new java.util.ArrayList<>();
        for (ClaudeCodeConfig t : targets) {
            if (t.isRegistered(url)) {
                installed.add(t.displayName() + " (" + t.configPath() + ")");
            } else {
                missing.add(t.displayName());
            }
        }
        if (installed.isEmpty()) {
            api.logging().logToOutput("BurpMCP not yet installed in any MCP client. Use the Register buttons on the BurpMCP tab.");
        } else {
            api.logging().logToOutput("BurpMCP installed in: " + String.join(", ", installed));
        }
        if (!missing.isEmpty()) {
            api.logging().logToOutput("BurpMCP not installed in: " + String.join(", ", missing));
        }
        refreshClientButtons();
    }

    private void refreshClientButtons() {
        String url = computeSseUrl();
        boolean claudeReg = ClaudeCodeConfig.CLAUDE_CODE.isRegistered(url);
        boolean cursorReg = ClaudeCodeConfig.CURSOR.isRegistered(url);
        if (registerClaudeButton != null) registerClaudeButton.setVisible(!claudeReg);
        if (connectedClaudeLabel != null) connectedClaudeLabel.setVisible(claudeReg);
        if (registerCursorButton != null) registerCursorButton.setVisible(!cursorReg);
        if (connectedCursorLabel != null) connectedCursorLabel.setVisible(cursorReg);
    }

    private JLabel makeConnectedPill(String clientName) {
        JLabel l = new JLabel("✓ Connected with " + clientName);
        l.setOpaque(true);
        l.setBackground(new Color(0xE6F4EA));
        l.setForeground(new Color(0x137333));
        l.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        l.setToolTipText("BurpMCP entry present in this client's MCP config. Re-register only if host/port changes.");
        l.setVisible(false);
        return l;
    }
    
    private ContextMenuItemsProvider createContextMenuItemsProvider() {
        return new ContextMenuItemsProvider() {
            @Override
            public List<Component> provideMenuItems(ContextMenuEvent event) {
                java.util.List<HttpRequestResponse> targets = new ArrayList<>();
                if (!event.selectedRequestResponses().isEmpty()) {
                    targets.addAll(event.selectedRequestResponses());
                } else if (event.messageEditorRequestResponse().isPresent()) {
                    targets.add(event.messageEditorRequestResponse().get().requestResponse());
                }
                if (targets.isEmpty()) {
                    return Collections.emptyList();
                }
                boolean fromSiteMapTree = false;
                try {
                    fromSiteMapTree = event.isFrom(burp.api.montoya.ui.contextmenu.InvocationType.SITE_MAP_TREE);
                } catch (Exception ignore) {
                }
                return buildContextMenu(targets, fromSiteMapTree);
            }
        };
    }

    private List<Component> buildContextMenu(java.util.List<HttpRequestResponse> targets, boolean fromSiteMapTree) {
        JMenu root = new JMenu("BurpMCP");

        // On the site-map tree, a "folder" selection still surfaces just the
        // single request-response Burp picked for that node — so auto-expand
        // to every sitemap entry under the same URL prefix.
        String sendLabel = fromSiteMapTree ? "Send entire branch to BurpMCP" : "Send to BurpMCP";
        JMenuItem sendItem = new JMenuItem(sendLabel);
        sendItem.addActionListener(e -> {
            java.util.List<HttpRequestResponse> finalTargets =
                    fromSiteMapTree ? expandToBranch(targets) : targets;
            int saved = 0;
            for (HttpRequestResponse rr : finalTargets) {
                savedRequestListModel.addRequest(rr, ZonedDateTime.now(), "");
                saved++;
            }
            savedRequestLogsPanel.getRequestTable().updateUI();
            api.logging().logToOutput("BurpMCP: saved " + saved + " request(s)" +
                    (fromSiteMapTree ? " (sitemap branch)" : ""));
        });
        root.add(sendItem);

        JMenuItem sendWithNoteItem = new JMenuItem(fromSiteMapTree
                ? "Send entire branch to BurpMCP with note…"
                : "Send to BurpMCP with note…");
        sendWithNoteItem.addActionListener(e -> {
            java.util.List<HttpRequestResponse> finalTargets =
                    fromSiteMapTree ? expandToBranch(targets) : targets;
            String note = JOptionPane.showInputDialog(extensionPanel,
                    "Note for " + finalTargets.size() + " saved request(s):",
                    "BurpMCP", JOptionPane.PLAIN_MESSAGE);
            if (note == null) return;
            for (HttpRequestResponse rr : finalTargets) {
                savedRequestListModel.addRequest(rr, ZonedDateTime.now(), note);
            }
            savedRequestLogsPanel.getRequestTable().updateUI();
        });
        root.add(sendWithNoteItem);

        // Always offer an explicit branch send (works from Proxy history too —
        // user can right-click any request and grab every URL under its prefix).
        if (!fromSiteMapTree) {
            JMenuItem branchItem = new JMenuItem("Send entire URL branch to BurpMCP");
            branchItem.setToolTipText("Walk Burp's site map and save every entry whose URL starts with the selected request's URL (without query string).");
            branchItem.addActionListener(e -> {
                java.util.List<HttpRequestResponse> branch = expandToBranch(targets);
                for (HttpRequestResponse rr : branch) {
                    savedRequestListModel.addRequest(rr, ZonedDateTime.now(), "");
                }
                savedRequestLogsPanel.getRequestTable().updateUI();
                api.logging().logToOutput("BurpMCP: saved " + branch.size() + " request(s) (URL branch)");
            });
            root.add(branchItem);
        }

        for (JMenuItem mi : buildBurpMcpActionItems(targets)) {
            if (mi == null) root.addSeparator();
            else root.add(mi);
        }
        return Collections.singletonList(root);
    }

    /**
     * The action-portion of the BurpMCP context menu — copy, scope, send-to-X
     * — reused by the table popup menus in the Saved Requests / Request Logs
     * panels. Null entries represent a separator. Save-into-saved-requests
     * items are NOT included here; the Burp-side context-menu provider
     * prepends them itself.
     */
    public java.util.List<JMenuItem> buildBurpMcpActionItems(java.util.List<HttpRequestResponse> targets) {
        java.util.List<JMenuItem> items = new ArrayList<>();

        JMenuItem copyUrlItem = new JMenuItem("Copy URL");
        copyUrlItem.addActionListener(e -> {
            StringBuilder sb = new StringBuilder();
            for (HttpRequestResponse rr : targets) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(rr.request().url());
            }
            copyToClipboard(sb.toString());
        });
        items.add(copyUrlItem);

        JMenuItem copyJsonItem = new JMenuItem("Copy as MCP http1-send JSON");
        copyJsonItem.addActionListener(e -> {
            StringBuilder sb = new StringBuilder();
            for (HttpRequestResponse rr : targets) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(buildHttp1SendJson(rr));
            }
            copyToClipboard(sb.toString());
        });
        items.add(copyJsonItem);

        JMenuItem copyCurlItem = new JMenuItem("Copy as curl");
        copyCurlItem.addActionListener(e -> {
            StringBuilder sb = new StringBuilder();
            for (HttpRequestResponse rr : targets) {
                if (sb.length() > 0) sb.append("\n\n");
                sb.append(buildCurlCommand(rr));
            }
            copyToClipboard(sb.toString());
        });
        items.add(copyCurlItem);

        items.add(null); // separator

        JMenuItem includeScopeItem = new JMenuItem("Include host in scope");
        includeScopeItem.addActionListener(e -> {
            for (HttpRequestResponse rr : targets) {
                api.scope().includeInScope(rr.request().url());
            }
        });
        items.add(includeScopeItem);

        JMenuItem excludeScopeItem = new JMenuItem("Exclude host from scope");
        excludeScopeItem.addActionListener(e -> {
            for (HttpRequestResponse rr : targets) {
                api.scope().excludeFromScope(rr.request().url());
            }
        });
        items.add(excludeScopeItem);

        items.add(null); // separator

        JMenuItem sendRepeaterItem = new JMenuItem("Send to Repeater");
        sendRepeaterItem.addActionListener(e -> {
            for (HttpRequestResponse rr : targets) {
                api.repeater().sendToRepeater(rr.request(), "BurpMCP");
            }
        });
        items.add(sendRepeaterItem);

        JMenuItem sendIntruderItem = new JMenuItem("Send to Intruder");
        sendIntruderItem.addActionListener(e -> {
            for (HttpRequestResponse rr : targets) {
                api.intruder().sendToIntruder(rr.request(), "BurpMCP");
            }
        });
        items.add(sendIntruderItem);

        JMenuItem sendOrganizerItem = new JMenuItem("Send to Organizer");
        sendOrganizerItem.addActionListener(e -> {
            for (HttpRequestResponse rr : targets) {
                api.organizer().sendToOrganizer(rr.request());
            }
        });
        items.add(sendOrganizerItem);

        JMenuItem sendComparerReqItem = new JMenuItem("Send request bytes to Comparer");
        sendComparerReqItem.addActionListener(e -> {
            burp.api.montoya.core.ByteArray[] arr = new burp.api.montoya.core.ByteArray[targets.size()];
            for (int i = 0; i < targets.size(); i++) {
                arr[i] = targets.get(i).request().toByteArray();
            }
            api.comparer().sendToComparer(arr);
        });
        items.add(sendComparerReqItem);

        JMenuItem sendComparerRespItem = new JMenuItem("Send response bytes to Comparer");
        sendComparerRespItem.addActionListener(e -> {
            java.util.List<burp.api.montoya.core.ByteArray> arr = new ArrayList<>();
            for (HttpRequestResponse rr : targets) {
                if (rr.response() != null) arr.add(rr.response().toByteArray());
            }
            if (!arr.isEmpty()) {
                api.comparer().sendToComparer(arr.toArray(new burp.api.montoya.core.ByteArray[0]));
            }
        });
        items.add(sendComparerRespItem);

        return items;
    }

    /**
     * Expand selected sitemap entries to every request/response in Burp's
     * site map whose URL starts with the same prefix. Query strings are
     * stripped from the prefix so /api/v1/users?id=1 still pulls in the
     * whole /api/v1/users/* branch. Results are deduplicated by URL+method.
     */
    private java.util.List<HttpRequestResponse> expandToBranch(java.util.List<HttpRequestResponse> selected) {
        java.util.LinkedHashMap<String, HttpRequestResponse> dedup = new java.util.LinkedHashMap<>();
        java.util.Set<String> prefixesTried = new java.util.HashSet<>();
        for (HttpRequestResponse rr : selected) {
            if (rr == null || rr.request() == null) continue;
            String url = rr.request().url();
            if (url == null || url.isEmpty()) continue;
            int q = url.indexOf('?');
            String prefix = q >= 0 ? url.substring(0, q) : url;
            if (!prefixesTried.add(prefix)) continue;
            try {
                java.util.List<HttpRequestResponse> branch =
                        api.siteMap().requestResponses(burp.api.montoya.sitemap.SiteMapFilter.prefixFilter(prefix));
                for (HttpRequestResponse e : branch) {
                    if (e == null || e.request() == null) continue;
                    String key = e.request().method() + " " + e.request().url();
                    dedup.putIfAbsent(key, e);
                }
            } catch (Exception ex) {
                api.logging().logToError("expandToBranch failed for prefix " + prefix + ": " + ex.getMessage());
            }
        }
        if (dedup.isEmpty()) {
            // Fallback: keep the original selection so the user always gets something
            for (HttpRequestResponse rr : selected) {
                if (rr == null || rr.request() == null) continue;
                String key = rr.request().method() + " " + rr.request().url();
                dedup.putIfAbsent(key, rr);
            }
        }
        return new ArrayList<>(dedup.values());
    }

    void copyToClipboard(String text) {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(text), null);
            api.logging().logToOutput("Copied " + text.length() + " chars to clipboard.");
        } catch (Exception ex) {
            api.logging().logToError("Clipboard copy failed: " + ex.getMessage());
        }
    }

    private String buildHttp1SendJson(HttpRequestResponse rr) {
        com.google.gson.JsonObject o = new com.google.gson.JsonObject();
        o.addProperty("tool", "http1-send");
        com.google.gson.JsonObject args = new com.google.gson.JsonObject();
        args.addProperty("data", rr.request().toString());
        if (rr.request().httpService() != null) {
            args.addProperty("host", rr.request().httpService().host());
            args.addProperty("port", rr.request().httpService().port());
            args.addProperty("secure", rr.request().httpService().secure());
        }
        o.add("arguments", args);
        return new com.google.gson.GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(o);
    }

    private String buildCurlCommand(HttpRequestResponse rr) {
        StringBuilder sb = new StringBuilder();
        sb.append("curl -i -k -X ").append(shellQuote(rr.request().method())).append(" ");
        sb.append(shellQuote(rr.request().url()));
        try {
            for (burp.api.montoya.http.message.HttpHeader h : rr.request().headers()) {
                String name = h.name();
                if (name == null) continue;
                String lower = name.toLowerCase();
                if (lower.equals("host") || lower.startsWith(":")) continue;
                sb.append(" -H ").append(shellQuote(name + ": " + h.value()));
            }
        } catch (Exception ignore) {
        }
        String body = rr.request().bodyToString();
        if (body != null && !body.isEmpty()) {
            sb.append(" --data-binary ").append(shellQuote(body));
        }
        return sb.toString();
    }

    private static String shellQuote(String s) {
        if (s == null) return "''";
        return "'" + s.replace("'", "'\\''") + "'";
    }
    
    private JPanel createExtensionPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        
        // Create tabbed pane
        JTabbedPane tabbedPane = new JTabbedPane();
        
        // Create Saved Requests tab
        savedRequestLogsPanel = new SavedRequestLogsPanel(api, savedRequestListModel, this);
        tabbedPane.addTab("Saved Requests", savedRequestLogsPanel);
        
        // Create Request Logs tab
        requestLogsPanel = new SentRequestLogsPanel(api, sentRequestListModel, this);
        tabbedPane.addTab("Request Logs", requestLogsPanel);
        
        // Create Server Logs tab - now passing the api parameter
        serverLogsPanel = new ServerLogsPanel(api, serverLogListModel);
        tabbedPane.addTab("Server Logs", serverLogsPanel);
        
        // Restore table sorting states after all panels are created and data is loaded
        SwingUtilities.invokeLater(() -> {
            savedRequestLogsPanel.restoreTableSortingState();
            requestLogsPanel.restoreTableSortingState();
            serverLogsPanel.restoreTableSortingState();
        });
        
        // Add the control panel at the top
        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        
        // Create server configuration panel
        JPanel configPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        configPanel.add(new JLabel("Host:"));
        
        // Initialize with default values that will be replaced if persisted values are found
        serverHost = "localhost";
        serverPort = 8181;
        crlfReplace = true;
        mcpServerEnabled = false;
        clientAutoPrompted = false;
        autoSaveInScope = false;
        // Try to restore saved configuration
        Object[] savedConfig = persistence.restoreServerConfig();
        if (savedConfig != null) {
            serverHost = (String) savedConfig[0];
            serverPort = (Integer) savedConfig[1];
            crlfReplace = (Boolean) savedConfig[2];
            mcpServerEnabled = (Boolean) savedConfig[3];
            if (savedConfig.length >= 5 && savedConfig[4] != null) {
                clientAutoPrompted = (Boolean) savedConfig[4];
            }
            if (savedConfig.length >= 6 && savedConfig[5] != null) {
                autoSaveInScope = (Boolean) savedConfig[5];
            }
        }
        
        hostField = new JTextField(serverHost, 15);
        hostField.setToolTipText("Host the MCP server binds to. Editable when the server is stopped.");
        configPanel.add(hostField);
        configPanel.add(new JLabel("Port:"));
        portField = new JTextField(String.valueOf(serverPort), 5);
        portField.setToolTipText("Port the MCP server binds to. Editable when the server is stopped.");
        configPanel.add(portField);

        // Status dot + label (traffic-light health indicator)
        serverStatusDot = new StatusDot(12);
        serverStatusLabel = new JLabel("Stopped");
        serverStatusLabel.setToolTipText("MCP server health: green = running, amber = recent error / transitioning, red = stopped or bind failure.");
        configPanel.add(new JLabel("  Status:"));
        configPanel.add(serverStatusDot);
        configPanel.add(serverStatusLabel);
        
        // Create toggle button
        mcpServerToggleButton = new JToggleButton("MCP Server: Disabled");
        mcpServerToggleButton.addActionListener(e -> {
            if (mcpServerToggleButton.isSelected()) {
                try {
                    // Update server configuration
                    serverHost = hostField.getText().trim();
                    serverPort = Integer.parseInt(portField.getText().trim());
                    mcpServerEnabled = true;
                    mcpServer.setServerConfig(serverHost, serverPort);
                    
                    // Save server configuration
                    persistence.saveServerConfig(serverHost, serverPort, crlfReplace, mcpServerEnabled, clientAutoPrompted, autoSaveInScope);
                    
                    // Start server
                    mcpServer.clearLastError();
                    mcpServer.start();
                    mcpServerToggleButton.setText("MCP Server: Enabled");
                    serverLogsPanel.getServerLogTable().updateUI();
                    refreshServerStatusIndicator();
                    
                    // Disable configuration fields
                    hostField.setEnabled(false);
                    portField.setEnabled(false);
                } catch (NumberFormatException ex) {
                    JOptionPane.showMessageDialog(extensionPanel, 
                        "Invalid port number. Please enter a valid integer.", 
                        "Configuration Error", 
                        JOptionPane.ERROR_MESSAGE);
                    mcpServerToggleButton.setSelected(false);
                } catch (IllegalStateException ex) {
                    JOptionPane.showMessageDialog(extensionPanel, 
                        ex.getMessage(), 
                        "Server Error", 
                        JOptionPane.ERROR_MESSAGE);
                    mcpServerToggleButton.setSelected(false);
                } catch (Exception ex) {
                    String errorMessage = ex.getMessage();
                    if (errorMessage == null || errorMessage.isEmpty()) {
                        errorMessage = "An unknown error occurred while starting the server.";
                    }
                    JOptionPane.showMessageDialog(extensionPanel, 
                        errorMessage, 
                        "Server Error", 
                        JOptionPane.ERROR_MESSAGE);
                    mcpServerToggleButton.setSelected(false);
                }
            } else {
                try {
                    mcpServer.stop();
                    mcpServerToggleButton.setText("MCP Server: Disabled");
                    serverLogsPanel.getServerLogTable().updateUI();
                    refreshServerStatusIndicator();

                    // Enable configuration fields
                    hostField.setEnabled(true);
                    portField.setEnabled(true);
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(extensionPanel, 
                        "Error stopping server: " + ex.getMessage(), 
                        "Server Error", 
                        JOptionPane.ERROR_MESSAGE);
                    mcpServerToggleButton.setSelected(true); // Keep server running if stop failed
                }
            }
        });
        
        // Create CRLF toggle button with tooltip
        crlfToggleButton = new JToggleButton("Replace LF with CRLF");
        crlfToggleButton.setToolTipText("Replace all LF \"\\n\" with CRLF \"\\r\\n\" in HTTP/1.1 requests.\nSome LLMs are not capable of sending CRLFs in their MCP\nmessages, which are required for HTTP/1.1.");
        crlfToggleButton.setSelected(crlfReplace);  
        
        // Add action listener to save state when toggled
        crlfToggleButton.addActionListener(e -> {
            crlfReplace = crlfToggleButton.isSelected();
            persistence.saveServerConfig(serverHost, serverPort, crlfReplace, mcpServerEnabled, clientAutoPrompted, autoSaveInScope);
        });

        // Auto-save in-scope traffic toggle
        autoSaveToggleButton = new JToggleButton("Auto-save in-scope: " + (autoSaveInScope ? "ON" : "OFF"));
        autoSaveToggleButton.setSelected(autoSaveInScope);
        autoSaveToggleButton.setToolTipText("When ON, any proxy traffic whose URL is in Burp's target scope is auto-saved into the BurpMCP Saved Requests list (with a 60s per-URL dedup).");
        autoSaveToggleButton.addActionListener(e -> {
            autoSaveInScope = autoSaveToggleButton.isSelected();
            autoSaveToggleButton.setText("Auto-save in-scope: " + (autoSaveInScope ? "ON" : "OFF"));
            persistence.saveServerConfig(serverHost, serverPort, crlfReplace, mcpServerEnabled, clientAutoPrompted, autoSaveInScope);
        });

        // Create the Claude Code registration button
        registerClaudeButton = new JButton("Register in Claude Code");
        registerClaudeButton.setToolTipText("Add or update the BurpMCP entry in ~/.claude.json so Claude Code can connect to this server.");
        registerClaudeButton.addActionListener(e -> registerWithClient(ClaudeCodeConfig.CLAUDE_CODE, true));

        // Create the Cursor registration button
        registerCursorButton = new JButton("Register in Cursor");
        registerCursorButton.setToolTipText("Add or update the BurpMCP entry in ~/.cursor/mcp.json so Cursor can connect to this server.");
        registerCursorButton.addActionListener(e -> registerWithClient(ClaudeCodeConfig.CURSOR, true));

        // Per-client "Connected" pills, hidden until isRegistered() returns true
        connectedClaudeLabel = makeConnectedPill("Claude Code");
        connectedCursorLabel = makeConnectedPill("Cursor");

        // Add components to control panel — pill sits next to its button so visibility-swap reads naturally
        controlPanel.add(configPanel);
        controlPanel.add(mcpServerToggleButton);
        controlPanel.add(crlfToggleButton);
        controlPanel.add(autoSaveToggleButton);
        controlPanel.add(registerClaudeButton);
        controlPanel.add(connectedClaudeLabel);
        controlPanel.add(registerCursorButton);
        controlPanel.add(connectedCursorLabel);

        panel.add(controlPanel, BorderLayout.NORTH);
        panel.add(tabbedPane, BorderLayout.CENTER);

        // If the server was previously enabled, restart it
        if (mcpServerEnabled) {
            mcpServerToggleButton.doClick();
        }

        // Periodic health refresh
        serverStatusTimer = new Timer(2000, e -> {
            refreshServerStatusIndicator();
            refreshClientButtons();
        });
        serverStatusTimer.setRepeats(true);
        serverStatusTimer.start();
        refreshServerStatusIndicator();

        return panel;
    }

    private void refreshServerStatusIndicator() {
        if (serverStatusDot == null) return;
        boolean running = mcpServer != null && mcpServer.isRunning();
        String err = mcpServer != null ? mcpServer.getLastError() : null;
        StatusDot.Status s;
        String label;
        if (running && err == null) {
            s = StatusDot.Status.GREEN;
            label = "Running on " + serverHost + ":" + serverPort;
        } else if (running) {
            s = StatusDot.Status.AMBER;
            label = "Running (warning: " + err + ")";
        } else if (err != null) {
            s = StatusDot.Status.RED;
            label = "Stopped (error: " + err + ")";
        } else {
            s = StatusDot.Status.RED;
            label = "Stopped";
        }
        serverStatusDot.setStatus(s);
        serverStatusLabel.setText(label);
    }

    public void writeToServerLog(String direction, String client, String tool, String messageData) {
        serverLogListModel.addLog(ZonedDateTime.now(), direction, client, tool, messageData);
        serverLogsPanel.getServerLogTable().updateUI();
    }
    
    public void addSentRequest(HttpRequestResponse requestResponse) {
        sentRequestListModel.addRequest(requestResponse, ZonedDateTime.now());
        requestLogsPanel.getSentRequestTable().updateUI();
    }

    public void addSavedRequest(HttpRequestResponse requestResponse, String notes) {
        savedRequestListModel.addRequest(requestResponse, ZonedDateTime.now(), notes);
        savedRequestLogsPanel.getRequestTable().updateUI();
    }

    public void saveRetrievedInteractions(List<String[]> interactions) {
        retrievedInteractions.addAll(interactions);
        persistence.saveRetrievedInteractions(retrievedInteractions);
    }

    private String computeSseUrl() {
        return "http://" + serverHost + ":" + serverPort + "/mcp/sse";
    }

    private void registerWithClient(ClaudeCodeConfig target, boolean showSuccessDialog) {
        String url = computeSseUrl();
        try {
            target.register(url);
            api.logging().logToOutput("BurpMCP registered in " + target.displayName() + " at " + target.configPath() + " with url " + url);
            refreshClientButtons();
            if (showSuccessDialog) {
                JOptionPane.showMessageDialog(extensionPanel,
                        "BurpMCP registered in " + target.displayName() + ":\n" + url + "\n\nFile: " + target.configPath(),
                        "BurpMCP",
                        JOptionPane.INFORMATION_MESSAGE);
            }
        } catch (Exception ex) {
            api.logging().logToError("Failed to register BurpMCP in " + target.displayName() + ": " + ex.getMessage(), ex);
            JOptionPane.showMessageDialog(extensionPanel,
                    "Failed to update " + target.configPath() + "\n\n" + ex.getMessage(),
                    "BurpMCP — registration failed",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void maybePromptClientRegistration() {
        if (clientAutoPrompted) {
            return;
        }
        String url = computeSseUrl();
        ClaudeCodeConfig[] targets = { ClaudeCodeConfig.CLAUDE_CODE, ClaudeCodeConfig.CURSOR };

        java.util.List<ClaudeCodeConfig> missing = new java.util.ArrayList<>();
        for (ClaudeCodeConfig t : targets) {
            if (!t.isRegistered(url)) {
                missing.add(t);
            }
        }
        if (missing.isEmpty()) {
            clientAutoPrompted = true;
            persistence.saveServerConfig(serverHost, serverPort, crlfReplace, mcpServerEnabled, clientAutoPrompted, autoSaveInScope);
            return;
        }

        StringBuilder bodies = new StringBuilder();
        for (ClaudeCodeConfig t : missing) {
            bodies.append("• ").append(t.displayName()).append(" — ").append(t.configPath()).append("\n");
        }
        int choice = JOptionPane.showConfirmDialog(extensionPanel,
                "Register BurpMCP with the following MCP clients now?\n\n" + bodies +
                        "\nURL to add: " + url,
                "BurpMCP",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE);
        if (choice == JOptionPane.YES_OPTION) {
            for (ClaudeCodeConfig t : missing) {
                registerWithClient(t, false);
            }
        }
        clientAutoPrompted = true;
        persistence.saveServerConfig(serverHost, serverPort, crlfReplace, mcpServerEnabled, clientAutoPrompted, autoSaveInScope);
    }
}