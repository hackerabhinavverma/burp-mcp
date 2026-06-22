package burpmcp.scan;

import burp.api.montoya.http.message.responses.analysis.ResponseKeywordsAnalyzer;
import burp.api.montoya.http.message.responses.analysis.ResponseVariationsAnalyzer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Holds long-lived Burp response analyzers keyed by an opaque id. */
public class AnalyzerRegistry {
    private final Map<String, ResponseKeywordsAnalyzer> keywords = new ConcurrentHashMap<>();
    private final Map<String, ResponseVariationsAnalyzer> variations = new ConcurrentHashMap<>();

    public String addKeywords(ResponseKeywordsAnalyzer a) {
        String id = "kw_" + UUID.randomUUID().toString().substring(0, 8);
        keywords.put(id, a);
        return id;
    }

    public String addVariations(ResponseVariationsAnalyzer a) {
        String id = "var_" + UUID.randomUUID().toString().substring(0, 8);
        variations.put(id, a);
        return id;
    }

    public ResponseKeywordsAnalyzer getKeywords(String id)  { return keywords.get(id); }
    public ResponseVariationsAnalyzer getVariations(String id) { return variations.get(id); }
    public void removeKeywords(String id)   { keywords.remove(id); }
    public void removeVariations(String id) { variations.remove(id); }
}
