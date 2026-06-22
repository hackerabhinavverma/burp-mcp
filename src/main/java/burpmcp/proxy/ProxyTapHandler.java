package burpmcp.proxy;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.proxy.http.InterceptedRequest;
import burp.api.montoya.proxy.http.InterceptedResponse;
import burp.api.montoya.proxy.http.ProxyRequestHandler;
import burp.api.montoya.proxy.http.ProxyRequestReceivedAction;
import burp.api.montoya.proxy.http.ProxyRequestToBeSentAction;
import burp.api.montoya.proxy.http.ProxyResponseHandler;
import burp.api.montoya.proxy.http.ProxyResponseReceivedAction;
import burp.api.montoya.proxy.http.ProxyResponseToBeSentAction;

import burpmcp.BurpMCP;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registers passive proxy hooks that copy every intercepted request and
 * response into a {@link ProxyTrafficStore}. Optionally also auto-saves
 * in-scope traffic into BurpMCP's saved-requests list so the LLM can see
 * everything the operator sees in Burp's Proxy → HTTP history.
 *
 * Auto-save is gated by {@link BurpMCP#autoSaveInScope}. Duplicate URLs
 * are throttled by a small last-seen map (method + url => epoch ms) so
 * page reloads/polling don't flood the saved list.
 */
public class ProxyTapHandler {
    private static final long DEDUP_WINDOW_MS = 60_000L;

    private final MontoyaApi api;
    private final ProxyTrafficStore store;
    private final BurpMCP burpMCP;
    private final ConcurrentHashMap<Integer, InterceptedRequest> pendingRequests = new ConcurrentHashMap<>();
    private final Map<String, Long> lastSavedAt = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > 4096;
        }
    };

    public ProxyTapHandler(MontoyaApi api, ProxyTrafficStore store, BurpMCP burpMCP) {
        this.api = api;
        this.store = store;
        this.burpMCP = burpMCP;
    }

    /** Back-compat overload (no auto-save) — used during unit-style construction. */
    public ProxyTapHandler(MontoyaApi api, ProxyTrafficStore store) {
        this(api, store, null);
    }

    public void register() {
        api.proxy().registerRequestHandler(new ProxyRequestHandler() {
            @Override
            public ProxyRequestReceivedAction handleRequestReceived(InterceptedRequest req) {
                try {
                    store.addRequest(req.messageId(), req);
                    if (burpMCP != null) {
                        pendingRequests.put(req.messageId(), req);
                    }
                } catch (Throwable t) {
                    api.logging().logToError("ProxyTap addRequest failed: " + t.getMessage());
                }
                return ProxyRequestReceivedAction.continueWith(req);
            }

            @Override
            public ProxyRequestToBeSentAction handleRequestToBeSent(InterceptedRequest req) {
                return ProxyRequestToBeSentAction.continueWith(req);
            }
        });

        api.proxy().registerResponseHandler(new ProxyResponseHandler() {
            @Override
            public ProxyResponseReceivedAction handleResponseReceived(InterceptedResponse resp) {
                try {
                    store.attachResponse(resp.messageId(), resp);
                    if (burpMCP != null) {
                        maybeAutoSaveInScope(resp);
                    }
                } catch (Throwable t) {
                    api.logging().logToError("ProxyTap attachResponse failed: " + t.getMessage());
                }
                return ProxyResponseReceivedAction.continueWith(resp);
            }

            @Override
            public ProxyResponseToBeSentAction handleResponseToBeSent(InterceptedResponse resp) {
                return ProxyResponseToBeSentAction.continueWith(resp);
            }
        });
    }

    private void maybeAutoSaveInScope(InterceptedResponse resp) {
        InterceptedRequest req = pendingRequests.remove(resp.messageId());
        if (req == null || !burpMCP.autoSaveInScope) return;
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

        HttpRequest snapshotRequest = HttpRequest.httpRequest(req.httpService(), req.toByteArray());
        HttpRequestResponse rr = HttpRequestResponse.httpRequestResponse(snapshotRequest, resp);
        // Hop to EDT so the saved-requests table refresh is safe.
        javax.swing.SwingUtilities.invokeLater(() -> burpMCP.addSavedRequest(rr, "auto-saved (in-scope)"));
    }
}
