package burpmcp;

import burp.api.montoya.MontoyaApi;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.WebFluxSseServerTransportProvider;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.LoggingLevel;
import io.modelcontextprotocol.spec.McpSchema.LoggingMessageNotification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import reactor.netty.http.server.HttpServer;

import com.fasterxml.jackson.databind.ObjectMapper;

import burpmcp.tools.Http1SendTool;
import burpmcp.tools.Http2SendTool;
import burpmcp.tools.Http1ResendTool;
import burpmcp.tools.Http2ResendTool;
import burpmcp.tools.GetSavedRequestTool;
import burpmcp.tools.UpdateNoteTool;
import burpmcp.tools.SaveHttp1RequestTool;
import burpmcp.tools.SaveHttp2RequestTool;
import burpmcp.models.SavedRequestListModel;
import burpmcp.tools.GenerateCollaboratorPayloadTool;
import burpmcp.tools.RetrieveCollaboratorInteractionsTool;
import burpmcp.tools.ListProxyTrafficTool;
import burpmcp.tools.GetProxyEntryTool;
import burpmcp.tools.SearchProxyTrafficTool;
import burpmcp.tools.TailProxyTrafficTool;
import burpmcp.tools.ScopeCheckTool;
import burpmcp.tools.ScopeUpdateTool;
import burpmcp.tools.SiteMapListTool;
import burpmcp.tools.SiteMapIssuesTool;
import burpmcp.tools.ProxyInterceptTool;
import burpmcp.tools.SendToRepeaterTool;
import burpmcp.tools.SendToIntruderTool;
import burpmcp.tools.SendToOrganizerTool;
import burpmcp.tools.SendToComparerTool;
import burpmcp.tools.DecodeEncodeTool;
import burpmcp.tools.CryptoDigestTool;
import burpmcp.tools.JsonQueryTool;
import burpmcp.tools.RandomStringTool;
import burpmcp.tools.NumberConvertTool;
import burpmcp.tools.StringHexTool;
import burpmcp.tools.CompressDataTool;
import burpmcp.tools.CookieListTool;
import burpmcp.tools.CookieSetTool;
import burpmcp.tools.ProxyHistoryTool;
import burpmcp.tools.SitemapAddTool;
import burpmcp.tools.ProjectInfoTool;
import burpmcp.tools.BurpLogTool;
import burpmcp.tools.ScannerStartAuditTool;
import burpmcp.tools.ScannerAuditStatusTool;
import burpmcp.tools.ScannerStartCrawlTool;
import burpmcp.tools.ScannerCrawlStatusTool;
import burpmcp.tools.ScannerCancelTool;
import burpmcp.tools.ScannerGenerateReportTool;
import burpmcp.tools.ScannerImportBcheckTool;
import burpmcp.tools.AnalyzerCreateKeywordsTool;
import burpmcp.tools.AnalyzerCreateVariationsTool;
import burpmcp.tools.AnalyzerFeedResponseTool;
import burpmcp.tools.WebSocketHistoryTool;
import burpmcp.tools.OptionsExportTool;
import burpmcp.tools.OptionsImportTool;
import burpmcp.tools.TaskEngineTool;
import burpmcp.tools.EditorGetTool;
import burpmcp.tools.EditorSetTool;
import burpmcp.scan.ScanRegistry;
import burpmcp.scan.AnalyzerRegistry;
import burpmcp.proxy.ProxyTrafficStore;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import io.netty.channel.ChannelOption;

public class MCPServer {
    private final MontoyaApi api;
    private final BurpMCP burpMCP;
    private final Logger logger = LoggerFactory.getLogger(MCPServer.class);
    private McpSyncServer syncServer;
    private WebFluxSseServerTransportProvider transportProvider;
    private boolean isRunning = false;
    private volatile String lastError = null;
    private String serverHost = "localhost";
    private int serverPort = 8181;
    private final String messagePath = "/mcp/message";
    private final String ssePath = "/mcp/sse";
    private reactor.netty.DisposableServer reactorServer;
    private SavedRequestListModel savedRequestListModel;
    private ProxyTrafficStore proxyTrafficStore;
    private Http1SendTool http1SendTool;
    private volatile long lastClientActivity = System.currentTimeMillis();
    private ScheduledExecutorService heartbeatExecutor;
    
    public MCPServer(MontoyaApi api, BurpMCP burpMCP) {
        this.api = api;
        this.burpMCP = burpMCP;
    }
    
    public void setSavedRequestListModel(SavedRequestListModel savedRequestListModel) {
        this.savedRequestListModel = savedRequestListModel;
    }

    public void setProxyTrafficStore(ProxyTrafficStore proxyTrafficStore) {
        this.proxyTrafficStore = proxyTrafficStore;
    }
    
    public void setServerConfig(String host, int port) {
        if (!isRunning) {
            this.serverHost = host;
            this.serverPort = port;
        }
    }
    
    public String getServerUrl() {
        return "http://" + serverHost + ":" + serverPort;
    }
    
    public void start() {
        if (isRunning) {
            throw new IllegalStateException("MCP Server is already running");
        }
        
        try {
            // Create a WebFlux-based SSE transport provider with a new ObjectMapper each time
            this.transportProvider = new WebFluxSseServerTransportProvider(
                new ObjectMapper(), 
                messagePath, 
                ssePath
            );
            
            // Create HTTP/1.1 Send tool
            http1SendTool = new Http1SendTool(api, burpMCP);
            SyncToolSpecification http1SendToolSpec = http1SendTool.createToolSpecification();

            // Create HTTP/2 Send tool
            Http2SendTool http2SendTool = new Http2SendTool(api, burpMCP);
            SyncToolSpecification http2SendToolSpec = http2SendTool.createToolSpecification();

            // Create HTTP/1.1 Resend tool
            Http1ResendTool http1ResendTool = new Http1ResendTool(api, burpMCP, savedRequestListModel);
            SyncToolSpecification http1ResendToolSpec = http1ResendTool.createToolSpecification();

            // Create HTTP/2 Resend tool
            Http2ResendTool http2ResendTool = new Http2ResendTool(api, burpMCP, savedRequestListModel);
            SyncToolSpecification http2ResendToolSpec = http2ResendTool.createToolSpecification();

            // Create Retrieve Saved Request tool
            GetSavedRequestTool retrieveSavedRequestTool = new GetSavedRequestTool(burpMCP, savedRequestListModel);
            SyncToolSpecification retrieveSavedRequestToolSpec = retrieveSavedRequestTool.createToolSpecification();

            // Create Update Note tool
            UpdateNoteTool updateNoteTool = new UpdateNoteTool(burpMCP, savedRequestListModel);
            SyncToolSpecification updateNoteToolSpec = updateNoteTool.createToolSpecification();
            
            // Create Save HTTP/1.1 Request tool
            SaveHttp1RequestTool saveHttp1RequestTool = new SaveHttp1RequestTool(api, burpMCP);
            SyncToolSpecification saveHttp1RequestToolSpec = saveHttp1RequestTool.createToolSpecification();

            // Create Save HTTP/2 Request tool
            SaveHttp2RequestTool saveHttp2RequestTool = new SaveHttp2RequestTool(api, burpMCP);
            SyncToolSpecification saveHttp2RequestToolSpec = saveHttp2RequestTool.createToolSpecification();

            // Create Generate Collaborator Payload tool
            GenerateCollaboratorPayloadTool generateCollaboratorPayloadTool = new GenerateCollaboratorPayloadTool(burpMCP);
            SyncToolSpecification generateCollaboratorPayloadToolSpec = generateCollaboratorPayloadTool.createToolSpecification();

            // Create List Collaborator Interactions tool
            RetrieveCollaboratorInteractionsTool retrieveCollaboratorInteractionsTool = new RetrieveCollaboratorInteractionsTool(burpMCP);
            SyncToolSpecification retrieveCollaboratorInteractionsToolSpec = retrieveCollaboratorInteractionsTool.createToolSpecification();

            // Live proxy traffic tools
            if (proxyTrafficStore == null) {
                throw new IllegalStateException("ProxyTrafficStore must be set before starting the server");
            }
            ListProxyTrafficTool listProxyTrafficTool = new ListProxyTrafficTool(burpMCP, proxyTrafficStore);
            SyncToolSpecification listProxyTrafficToolSpec = listProxyTrafficTool.createToolSpecification();
            GetProxyEntryTool getProxyEntryTool = new GetProxyEntryTool(burpMCP, proxyTrafficStore);
            SyncToolSpecification getProxyEntryToolSpec = getProxyEntryTool.createToolSpecification();
            SearchProxyTrafficTool searchProxyTrafficTool = new SearchProxyTrafficTool(burpMCP, proxyTrafficStore);
            SyncToolSpecification searchProxyTrafficToolSpec = searchProxyTrafficTool.createToolSpecification();
            TailProxyTrafficTool tailProxyTrafficTool = new TailProxyTrafficTool(burpMCP, proxyTrafficStore);
            SyncToolSpecification tailProxyTrafficToolSpec = tailProxyTrafficTool.createToolSpecification();

            // Additional Montoya-API tools
            ScopeCheckTool scopeCheckTool = new ScopeCheckTool(api, burpMCP);
            SyncToolSpecification scopeCheckToolSpec = scopeCheckTool.createToolSpecification();
            ScopeUpdateTool scopeUpdateTool = new ScopeUpdateTool(api, burpMCP);
            SyncToolSpecification scopeUpdateToolSpec = scopeUpdateTool.createToolSpecification();
            SiteMapListTool siteMapListTool = new SiteMapListTool(api, burpMCP);
            SyncToolSpecification siteMapListToolSpec = siteMapListTool.createToolSpecification();
            SiteMapIssuesTool siteMapIssuesTool = new SiteMapIssuesTool(api, burpMCP);
            SyncToolSpecification siteMapIssuesToolSpec = siteMapIssuesTool.createToolSpecification();
            ProxyInterceptTool proxyInterceptTool = new ProxyInterceptTool(api, burpMCP);
            SyncToolSpecification proxyInterceptToolSpec = proxyInterceptTool.createToolSpecification();
            SendToRepeaterTool sendToRepeaterTool = new SendToRepeaterTool(api, burpMCP, savedRequestListModel);
            SyncToolSpecification sendToRepeaterToolSpec = sendToRepeaterTool.createToolSpecification();
            SendToIntruderTool sendToIntruderTool = new SendToIntruderTool(api, burpMCP, savedRequestListModel);
            SyncToolSpecification sendToIntruderToolSpec = sendToIntruderTool.createToolSpecification();
            SendToOrganizerTool sendToOrganizerTool = new SendToOrganizerTool(api, burpMCP, savedRequestListModel);
            SyncToolSpecification sendToOrganizerToolSpec = sendToOrganizerTool.createToolSpecification();
            SendToComparerTool sendToComparerTool = new SendToComparerTool(api, burpMCP);
            SyncToolSpecification sendToComparerToolSpec = sendToComparerTool.createToolSpecification();
            DecodeEncodeTool decodeEncodeTool = new DecodeEncodeTool(api, burpMCP);
            SyncToolSpecification decodeEncodeToolSpec = decodeEncodeTool.createToolSpecification();
            CryptoDigestTool cryptoDigestTool = new CryptoDigestTool(api, burpMCP);
            SyncToolSpecification cryptoDigestToolSpec = cryptoDigestTool.createToolSpecification();
            JsonQueryTool jsonQueryTool = new JsonQueryTool(api, burpMCP);
            SyncToolSpecification jsonQueryToolSpec = jsonQueryTool.createToolSpecification();

            // Utilities
            RandomStringTool randomStringTool = new RandomStringTool(api, burpMCP);
            SyncToolSpecification randomStringToolSpec = randomStringTool.createToolSpecification();
            NumberConvertTool numberConvertTool = new NumberConvertTool(api, burpMCP);
            SyncToolSpecification numberConvertToolSpec = numberConvertTool.createToolSpecification();
            StringHexTool stringHexTool = new StringHexTool(api, burpMCP);
            SyncToolSpecification stringHexToolSpec = stringHexTool.createToolSpecification();
            CompressDataTool compressDataTool = new CompressDataTool(api, burpMCP);
            SyncToolSpecification compressDataToolSpec = compressDataTool.createToolSpecification();

            // Cookies + proxy history + sitemap + project + log
            CookieListTool cookieListTool = new CookieListTool(api, burpMCP);
            SyncToolSpecification cookieListToolSpec = cookieListTool.createToolSpecification();
            CookieSetTool cookieSetTool = new CookieSetTool(api, burpMCP);
            SyncToolSpecification cookieSetToolSpec = cookieSetTool.createToolSpecification();
            ProxyHistoryTool proxyHistoryTool = new ProxyHistoryTool(api, burpMCP);
            SyncToolSpecification proxyHistoryToolSpec = proxyHistoryTool.createToolSpecification();
            SitemapAddTool sitemapAddTool = new SitemapAddTool(api, burpMCP);
            SyncToolSpecification sitemapAddToolSpec = sitemapAddTool.createToolSpecification();
            ProjectInfoTool projectInfoTool = new ProjectInfoTool(api, burpMCP);
            SyncToolSpecification projectInfoToolSpec = projectInfoTool.createToolSpecification();
            BurpLogTool burpLogTool = new BurpLogTool(api, burpMCP);
            SyncToolSpecification burpLogToolSpec = burpLogTool.createToolSpecification();

            // Scanner — long-lived state goes in a shared registry
            ScanRegistry scanRegistry = new ScanRegistry();
            ScannerStartAuditTool scannerStartAuditTool = new ScannerStartAuditTool(api, burpMCP, savedRequestListModel, scanRegistry);
            SyncToolSpecification scannerStartAuditToolSpec = scannerStartAuditTool.createToolSpecification();
            ScannerAuditStatusTool scannerAuditStatusTool = new ScannerAuditStatusTool(burpMCP, scanRegistry);
            SyncToolSpecification scannerAuditStatusToolSpec = scannerAuditStatusTool.createToolSpecification();
            ScannerStartCrawlTool scannerStartCrawlTool = new ScannerStartCrawlTool(api, burpMCP, scanRegistry);
            SyncToolSpecification scannerStartCrawlToolSpec = scannerStartCrawlTool.createToolSpecification();
            ScannerCrawlStatusTool scannerCrawlStatusTool = new ScannerCrawlStatusTool(burpMCP, scanRegistry);
            SyncToolSpecification scannerCrawlStatusToolSpec = scannerCrawlStatusTool.createToolSpecification();
            ScannerCancelTool scannerCancelTool = new ScannerCancelTool(burpMCP, scanRegistry);
            SyncToolSpecification scannerCancelToolSpec = scannerCancelTool.createToolSpecification();
            ScannerGenerateReportTool scannerGenerateReportTool = new ScannerGenerateReportTool(api, burpMCP, scanRegistry);
            SyncToolSpecification scannerGenerateReportToolSpec = scannerGenerateReportTool.createToolSpecification();
            ScannerImportBcheckTool scannerImportBcheckTool = new ScannerImportBcheckTool(api, burpMCP);
            SyncToolSpecification scannerImportBcheckToolSpec = scannerImportBcheckTool.createToolSpecification();

            // Response analyzers
            AnalyzerRegistry analyzerRegistry = new AnalyzerRegistry();
            AnalyzerCreateKeywordsTool analyzerCreateKeywordsTool = new AnalyzerCreateKeywordsTool(api, burpMCP, analyzerRegistry);
            SyncToolSpecification analyzerCreateKeywordsToolSpec = analyzerCreateKeywordsTool.createToolSpecification();
            AnalyzerCreateVariationsTool analyzerCreateVariationsTool = new AnalyzerCreateVariationsTool(api, burpMCP, analyzerRegistry);
            SyncToolSpecification analyzerCreateVariationsToolSpec = analyzerCreateVariationsTool.createToolSpecification();
            AnalyzerFeedResponseTool analyzerFeedResponseTool = new AnalyzerFeedResponseTool(api, burpMCP, analyzerRegistry, proxyTrafficStore);
            SyncToolSpecification analyzerFeedResponseToolSpec = analyzerFeedResponseTool.createToolSpecification();

            // Parity-with-official tools
            WebSocketHistoryTool webSocketHistoryTool = new WebSocketHistoryTool(api, burpMCP);
            SyncToolSpecification webSocketHistoryToolSpec = webSocketHistoryTool.createToolSpecification();
            OptionsExportTool optionsExportTool = new OptionsExportTool(api, burpMCP);
            SyncToolSpecification optionsExportToolSpec = optionsExportTool.createToolSpecification();
            OptionsImportTool optionsImportTool = new OptionsImportTool(api, burpMCP);
            SyncToolSpecification optionsImportToolSpec = optionsImportTool.createToolSpecification();
            TaskEngineTool taskEngineTool = new TaskEngineTool(api, burpMCP);
            SyncToolSpecification taskEngineToolSpec = taskEngineTool.createToolSpecification();
            EditorGetTool editorGetTool = new EditorGetTool(burpMCP);
            SyncToolSpecification editorGetToolSpec = editorGetTool.createToolSpecification();
            EditorSetTool editorSetTool = new EditorSetTool(burpMCP);
            SyncToolSpecification editorSetToolSpec = editorSetTool.createToolSpecification();

            // Create request saved request specification
            // Ensure savedRequestListModel is set before starting the server
            if (savedRequestListModel == null) {
                throw new IllegalStateException("SavedRequestListModel must be set before starting the server");
            }
            
            // Create the MCP server with the WebFlux SSE transport and tools
            this.syncServer = McpServer.sync(transportProvider)
                .serverInfo("burp-mcp-server", "1.0.0")
                .capabilities(ServerCapabilities.builder()
                    .tools(true)               // Enable tool support
                    .logging()                 // Enable logging support
                    .build())
                .tool(http1SendToolSpec.tool(), http1SendToolSpec.call()) // Add HTTP/1.1 send tool
                .tool(http2SendToolSpec.tool(), http2SendToolSpec.call()) // Add HTTP/2 send tool
                .tool(http1ResendToolSpec.tool(), http1ResendToolSpec.call()) // Add HTTP/1.1 resend tool
                .tool(http2ResendToolSpec.tool(), http2ResendToolSpec.call()) // Add HTTP/2 resend tool
                .tool(retrieveSavedRequestToolSpec.tool(), retrieveSavedRequestToolSpec.call()) // Add retrieve saved request tool
                .tool(updateNoteToolSpec.tool(), updateNoteToolSpec.call()) // Add update note tool
                .tool(saveHttp1RequestToolSpec.tool(), saveHttp1RequestToolSpec.call()) // Add save HTTP/1.1 request tool
                .tool(saveHttp2RequestToolSpec.tool(), saveHttp2RequestToolSpec.call()) // Add save HTTP/2 request tool
                .tool(generateCollaboratorPayloadToolSpec.tool(), generateCollaboratorPayloadToolSpec.call()) // Add generate collaborator payload tool
                .tool(retrieveCollaboratorInteractionsToolSpec.tool(), retrieveCollaboratorInteractionsToolSpec.call()) // Add list collaborator interactions tool
                .tool(listProxyTrafficToolSpec.tool(), listProxyTrafficToolSpec.call())
                .tool(getProxyEntryToolSpec.tool(), getProxyEntryToolSpec.call())
                .tool(searchProxyTrafficToolSpec.tool(), searchProxyTrafficToolSpec.call())
                .tool(tailProxyTrafficToolSpec.tool(), tailProxyTrafficToolSpec.call())
                .tool(scopeCheckToolSpec.tool(), scopeCheckToolSpec.call())
                .tool(scopeUpdateToolSpec.tool(), scopeUpdateToolSpec.call())
                .tool(siteMapListToolSpec.tool(), siteMapListToolSpec.call())
                .tool(siteMapIssuesToolSpec.tool(), siteMapIssuesToolSpec.call())
                .tool(proxyInterceptToolSpec.tool(), proxyInterceptToolSpec.call())
                .tool(sendToRepeaterToolSpec.tool(), sendToRepeaterToolSpec.call())
                .tool(sendToIntruderToolSpec.tool(), sendToIntruderToolSpec.call())
                .tool(sendToOrganizerToolSpec.tool(), sendToOrganizerToolSpec.call())
                .tool(sendToComparerToolSpec.tool(), sendToComparerToolSpec.call())
                .tool(decodeEncodeToolSpec.tool(), decodeEncodeToolSpec.call())
                .tool(cryptoDigestToolSpec.tool(), cryptoDigestToolSpec.call())
                .tool(jsonQueryToolSpec.tool(), jsonQueryToolSpec.call())
                .tool(randomStringToolSpec.tool(), randomStringToolSpec.call())
                .tool(numberConvertToolSpec.tool(), numberConvertToolSpec.call())
                .tool(stringHexToolSpec.tool(), stringHexToolSpec.call())
                .tool(compressDataToolSpec.tool(), compressDataToolSpec.call())
                .tool(cookieListToolSpec.tool(), cookieListToolSpec.call())
                .tool(cookieSetToolSpec.tool(), cookieSetToolSpec.call())
                .tool(proxyHistoryToolSpec.tool(), proxyHistoryToolSpec.call())
                .tool(sitemapAddToolSpec.tool(), sitemapAddToolSpec.call())
                .tool(projectInfoToolSpec.tool(), projectInfoToolSpec.call())
                .tool(burpLogToolSpec.tool(), burpLogToolSpec.call())
                .tool(scannerStartAuditToolSpec.tool(), scannerStartAuditToolSpec.call())
                .tool(scannerAuditStatusToolSpec.tool(), scannerAuditStatusToolSpec.call())
                .tool(scannerStartCrawlToolSpec.tool(), scannerStartCrawlToolSpec.call())
                .tool(scannerCrawlStatusToolSpec.tool(), scannerCrawlStatusToolSpec.call())
                .tool(scannerCancelToolSpec.tool(), scannerCancelToolSpec.call())
                .tool(scannerGenerateReportToolSpec.tool(), scannerGenerateReportToolSpec.call())
                .tool(scannerImportBcheckToolSpec.tool(), scannerImportBcheckToolSpec.call())
                .tool(analyzerCreateKeywordsToolSpec.tool(), analyzerCreateKeywordsToolSpec.call())
                .tool(analyzerCreateVariationsToolSpec.tool(), analyzerCreateVariationsToolSpec.call())
                .tool(analyzerFeedResponseToolSpec.tool(), analyzerFeedResponseToolSpec.call())
                .tool(webSocketHistoryToolSpec.tool(), webSocketHistoryToolSpec.call())
                .tool(optionsExportToolSpec.tool(), optionsExportToolSpec.call())
                .tool(optionsImportToolSpec.tool(), optionsImportToolSpec.call())
                .tool(taskEngineToolSpec.tool(), taskEngineToolSpec.call())
                .tool(editorGetToolSpec.tool(), editorGetToolSpec.call())
                .tool(editorSetToolSpec.tool(), editorSetToolSpec.call())
                .build();
            
            // Get the router function from the transport provider
            RouterFunction<?> routerFunction = transportProvider.getRouterFunction();
            
            // Create a handler adapter for the router function
            org.springframework.web.server.WebHandler webHandler = 
                RouterFunctions.toWebHandler(routerFunction, HandlerStrategies.builder().build());
            
            // Create an HTTP handler
            org.springframework.http.server.reactive.HttpHandler httpHandler = 
                WebHttpHandlerBuilder.webHandler(webHandler).build();
            
            // Create the adapter
            ReactorHttpHandlerAdapter adapter = 
                new ReactorHttpHandlerAdapter(httpHandler);
            
            // Configure a new HttpServer with socket options
            HttpServer httpServer = HttpServer.create()
                .host(serverHost)
                .port(serverPort)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .idleTimeout(Duration.ofMinutes(30));  // Connection timeout
            
            // Bind and store the server instance
            this.reactorServer = httpServer
                .handle(adapter)
                .bindNow();
            
            // Send a log message to clients
            this.syncServer.loggingNotification(LoggingMessageNotification.builder()
                .level(LoggingLevel.INFO)
                .logger("burp-mcp-server")
                .data("MCP Server started with WebFlux SSE transport")
                .build());
            
            // Log server initialization with URLs
            String sseUrl = getServerUrl() + ssePath;
            String messageUrl = getServerUrl() + messagePath;
            api.logging().logToOutput("Burp MCP Server started:");
            api.logging().logToOutput("- SSE endpoint: " + sseUrl);
            api.logging().logToOutput("- Message endpoint: " + messageUrl);
            
            isRunning = true;
            
            // Schedule periodic heartbeat to keep connections alive
            ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "MCP-Heartbeat");
                t.setDaemon(true);
                return t;
            });

            heartbeatExecutor.scheduleAtFixedRate(() -> {
                try {
                    if (syncServer != null && isRunning) {
                        syncServer.loggingNotification(LoggingMessageNotification.builder()
                            .level(LoggingLevel.DEBUG)
                            .logger("burp-mcp-server")
                            .data("heartbeat-" + System.currentTimeMillis())
                            .build());
                    }
                } catch (Exception e) {
                    logger.debug("Heartbeat failed", e);
                }
            }, 60, 60, TimeUnit.SECONDS); // Send heartbeat every 60 seconds
            
        } catch (Exception e) {
            // Log the error for debugging
            lastError = e.getMessage();
            api.logging().logToError("Failed to start MCP Server: " + e.getMessage(), e);
            logger.error("Failed to start MCP Server", e);
            cleanup();  // Ensure cleanup on errors
            throw new RuntimeException("Failed to start MCP Server: " + e.getMessage(), e);
        }
    }

    public String getLastError() {
        return lastError;
    }

    public void clearLastError() {
        lastError = null;
    }
    
    public void stop() {
        if (!isRunning) {
            api.logging().logToOutput("MCP Server is not running");
            return;
        }
        
        try {
            cleanup();
            api.logging().logToOutput("Burp MCP Server stopped");
        } catch (Exception e) {
            api.logging().logToError("Error stopping MCP Server: " + e.getMessage(), e);
            logger.error("Error stopping MCP Server", e);
        }
    }
    
    private void cleanup() {
        try {
            // Close the MCP server first
            if (syncServer != null) {
                syncServer.close(); // Use immediate close to ensure quick shutdown
                syncServer = null;
            }
            
            // Dispose the Reactor server
            if (reactorServer != null) {
                try {
                    reactorServer.disposeNow();
                } catch (Exception e) {
                    logger.error("Error disposing reactor server", e);
                }
                reactorServer = null;
            }
            
            // Release reference to transport provider
            transportProvider = null;
            
            // Force garbage collection to release resources
            System.gc();
            
            isRunning = false;
        } catch (Exception e) {
            logger.error("Error during cleanup", e);
        }
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdown();
            try {
                if (!heartbeatExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    heartbeatExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                heartbeatExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            heartbeatExecutor = null;
        }
    }

    public boolean isRunning() {
        return isRunning;
    }
    
    public String getSSEEndpoint() {
        return ssePath;
    }
    
    public String getMessageEndpoint() {
        return messagePath;
    }

    public Http1SendTool getHttp1SendTool() {
        return http1SendTool;
    }

    public void restart() {
        if (isRunning) {
            api.logging().logToOutput("Restarting MCP Server...");
            stop();
            try {
                Thread.sleep(1000); // Brief pause
                start();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}