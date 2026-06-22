package burpmcp.proxy;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ToolType;
import burp.api.montoya.http.handler.HttpHandler;
import burp.api.montoya.http.handler.HttpRequestToBeSent;
import burp.api.montoya.http.handler.HttpResponseReceived;
import burp.api.montoya.http.handler.RequestToBeSentAction;
import burp.api.montoya.http.handler.ResponseReceivedAction;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;

import burpmcp.BurpMCP;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Burp-wide HTTP observer that auto-saves in-scope traffic into BurpMCP's
 * saved-requests list, regardless of which Burp tool issued the request
 * (Target, Scanner, Repeater, Intruder, Crawler …). Proxy traffic is
 * already handled by {@link ProxyTapHandler}; we skip ToolType.PROXY here
 * to avoid double-saving. Extension-initiated requests (including BurpMCP
 * itself, via http1-send / http2-send / etc.) are skipped too, to avoid
 * recursion and noise.
 *
 * Same 60-second per-(method+url) dedup window as ProxyTapHandler.
 */
public class AutoSaveHttpHandler {
    private static final long DEDUP_WINDOW_MS = 60_000L;

    private final MontoyaApi api;
    private final BurpMCP burpMCP;
    private final Map<String, Long> lastSavedAt = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > 4096;
        }
    };

    public AutoSaveHttpHandler(MontoyaApi api, BurpMCP burpMCP) {
        this.api = api;
        this.burpMCP = burpMCP;
    }

    public void register() {
        api.http().registerHttpHandler(new HttpHandler() {
            @Override
            public RequestToBeSentAction handleHttpRequestToBeSent(HttpRequestToBeSent req) {
                return RequestToBeSentAction.continueWith(req);
            }

            @Override
            public ResponseReceivedAction handleHttpResponseReceived(HttpResponseReceived resp) {
                try {
                    maybeSave(resp);
                } catch (Throwable t) {
                    api.logging().logToError("AutoSaveHttpHandler failed: " + t.getMessage());
                }
                return ResponseReceivedAction.continueWith(resp);
            }
        });
    }

    private void maybeSave(HttpResponseReceived resp) {
        if (!burpMCP.autoSaveInScope) return;
        ToolType src = resp.toolSource().toolType();
        // ProxyTapHandler already auto-saves PROXY traffic
        if (src == ToolType.PROXY) return;
        // Skip extension-initiated calls (BurpMCP itself + other extensions)
        if (src == ToolType.EXTENSIONS) return;

        HttpRequest req = resp.initiatingRequest();
        if (req == null) return;
        String url;
        try {
            url = req.url();
        } catch (Exception e) {
            return;
        }
        if (url == null || url.isEmpty()) return;
        try {
            if (!api.scope().isInScope(url)) return;
        } catch (Exception e) {
            return;
        }

        String dedupKey = req.method() + " " + url;
        long now = System.currentTimeMillis();
        synchronized (lastSavedAt) {
            Long last = lastSavedAt.get(dedupKey);
            if (last != null && now - last < DEDUP_WINDOW_MS) {
                return;
            }
            lastSavedAt.put(dedupKey, now);
        }

        HttpRequestResponse rr = HttpRequestResponse.httpRequestResponse(req, resp);
        String tool = src.toolName();
        javax.swing.SwingUtilities.invokeLater(() ->
                burpMCP.addSavedRequest(rr, "auto-saved (" + tool + ", in-scope)"));
    }
}
