package burpmcp.proxy;

import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory ring buffer of live Burp Proxy traffic. Entries are keyed by a
 * Burp messageId (long) so the response handler can attach to the right
 * request. Capacity is bounded; oldest entries are evicted when full.
 */
public class ProxyTrafficStore {
    public static final int DEFAULT_CAPACITY = 2000;

    public static class Entry {
        public final long id;
        public final long burpMessageId;
        public final ZonedDateTime requestTime;
        public volatile ZonedDateTime responseTime;
        public final String method;
        public final String url;
        public final String host;
        public final int port;
        public final boolean secure;
        public final String httpVersion;
        public final byte[] requestBytes;
        public volatile byte[] responseBytes;
        public volatile Integer statusCode;
        public volatile String responseReason;
        public volatile Integer responseLength;
        public volatile String mimeType;

        Entry(long id, long burpMessageId, HttpRequest req, ZonedDateTime when) {
            this.id = id;
            this.burpMessageId = burpMessageId;
            this.requestTime = when;
            this.method = safe(req.method());
            this.url = safe(req.url());
            this.host = req.httpService() != null ? req.httpService().host() : "";
            this.port = req.httpService() != null ? req.httpService().port() : -1;
            this.secure = req.httpService() != null && req.httpService().secure();
            this.httpVersion = safe(req.httpVersion());
            this.requestBytes = req.toByteArray() != null ? req.toByteArray().getBytes() : new byte[0];
        }

        void attachResponse(HttpResponse resp, ZonedDateTime when) {
            this.responseTime = when;
            this.responseBytes = resp.toByteArray() != null ? resp.toByteArray().getBytes() : new byte[0];
            this.statusCode = (int) resp.statusCode();
            this.responseReason = safe(resp.reasonPhrase());
            this.responseLength = this.responseBytes.length;
            this.mimeType = resp.mimeType() != null ? resp.mimeType().name() : "";
        }

        private static String safe(String s) {
            return s == null ? "" : s;
        }
    }

    private final int capacity;
    private final ArrayList<Entry> ring;
    private final ConcurrentHashMap<Long, Entry> byBurpId = new ConcurrentHashMap<>();
    private final AtomicLong idSeq = new AtomicLong(1);

    public ProxyTrafficStore() {
        this(DEFAULT_CAPACITY);
    }

    public ProxyTrafficStore(int capacity) {
        this.capacity = capacity;
        this.ring = new ArrayList<>(capacity);
    }

    public synchronized Entry addRequest(long burpMessageId, HttpRequest req) {
        long id = idSeq.getAndIncrement();
        Entry e = new Entry(id, burpMessageId, req, ZonedDateTime.now());
        if (ring.size() >= capacity) {
            Entry evicted = ring.remove(0);
            byBurpId.remove(evicted.burpMessageId);
        }
        ring.add(e);
        byBurpId.put(burpMessageId, e);
        return e;
    }

    public void attachResponse(long burpMessageId, HttpResponse resp) {
        Entry e = byBurpId.get(burpMessageId);
        if (e != null) {
            e.attachResponse(resp, ZonedDateTime.now());
        }
    }

    public synchronized List<Entry> snapshot() {
        return new ArrayList<>(ring);
    }

    public synchronized Entry getById(long id) {
        for (int i = ring.size() - 1; i >= 0; i--) {
            Entry e = ring.get(i);
            if (e.id == id) return e;
        }
        return null;
    }

    public synchronized List<Entry> latest(int limit) {
        if (limit <= 0) return Collections.emptyList();
        int from = Math.max(0, ring.size() - limit);
        return new ArrayList<>(ring.subList(from, ring.size()));
    }

    public synchronized List<Entry> sinceId(long sinceId, int limit) {
        ArrayList<Entry> out = new ArrayList<>();
        for (Entry e : ring) {
            if (e.id > sinceId) out.add(e);
        }
        if (limit > 0 && out.size() > limit) {
            return new ArrayList<>(out.subList(0, limit));
        }
        return out;
    }

    public synchronized int size() {
        return ring.size();
    }

    public synchronized long highestId() {
        if (ring.isEmpty()) return 0;
        return ring.get(ring.size() - 1).id;
    }

    public synchronized void clear() {
        ring.clear();
        byBurpId.clear();
    }
}
