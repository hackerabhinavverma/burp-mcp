package burpmcp.scan;

import burp.api.montoya.scanner.Crawl;
import burp.api.montoya.scanner.audit.Audit;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bookkeeping for in-flight Burp scanner tasks so MCP tool calls can poll
 * and cancel them by an opaque id rather than holding a Java reference.
 */
public class ScanRegistry {
    private final Map<String, Audit> audits = new ConcurrentHashMap<>();
    private final Map<String, Crawl> crawls = new ConcurrentHashMap<>();

    public String addAudit(Audit a) {
        String id = "audit_" + UUID.randomUUID().toString().substring(0, 8);
        audits.put(id, a);
        return id;
    }

    public Audit getAudit(String id) {
        return audits.get(id);
    }

    public String addCrawl(Crawl c) {
        String id = "crawl_" + UUID.randomUUID().toString().substring(0, 8);
        crawls.put(id, c);
        return id;
    }

    public Crawl getCrawl(String id) {
        return crawls.get(id);
    }

    public void removeAudit(String id) {
        audits.remove(id);
    }

    public void removeCrawl(String id) {
        crawls.remove(id);
    }

    public java.util.Set<String> auditIds() { return audits.keySet(); }
    public java.util.Set<String> crawlIds() { return crawls.keySet(); }
}
