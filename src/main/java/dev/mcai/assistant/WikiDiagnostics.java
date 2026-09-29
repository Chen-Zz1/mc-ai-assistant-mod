package dev.mcai.assistant;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Per-request bounded diagnostics; only summary() is safe for ordinary server logs. */
final class WikiDiagnostics {
    final String requestId = UUID.randomUUID().toString();
    private final Map<String, Long> stageNanos = new LinkedHashMap<>();
    private final Map<String, Integer> counts = new LinkedHashMap<>();
    private final Map<String, Integer> filters = new LinkedHashMap<>();
    private final JsonArray candidates = new JsonArray();
    private final JsonArray selected = new JsonArray();
    private String stage = "rewrite";
    private long started = System.nanoTime();
    private String reason = "PENDING";
    private String topic = "";
    private String keywords = "";
    private boolean cacheHit;

    synchronized void stage(String next) {
        long now = System.nanoTime();
        stageNanos.merge(stage, Math.max(0, now - started), Long::sum);
        started = now;
        stage = next;
    }

    synchronized void query(WikiQuery query) {
        topic = bounded(query.topic(), 150);
        keywords = bounded(query.keywords(), 300);
    }

    synchronized void hits(List<MediaWikiClient.Hit> hits) {
        counts.put("searchHits", hits.size());
        for (var hit : hits.stream().limit(3).toList()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("pageId", hit.pageId());
            entry.addProperty("title", bounded(hit.title(), 150));
            entry.addProperty("resolvedAlias", bounded(hit.resolvedAlias(), 150));
            candidates.add(entry);
        }
    }

    synchronized void count(String key, int value) {
        counts.merge(key, value, Integer::sum);
    }

    synchronized void filter(WikiCache.Filter filter) {
        filters.merge(filter.name(), 1, Integer::sum);
    }

    synchronized String filteredReason(int total) {
        return filters.entrySet().stream().filter(entry -> total > 0 && entry.getValue() == total)
                .map(Map.Entry::getKey).findFirst().orElse("NO_RELEVANT_CHUNKS");
    }

    synchronized void ranking(WikiCache.Ranking ranking, boolean hit) {
        cacheHit = hit;
        counts.put("examinedChunks", ranking.examined());
        counts.put("selectedChunks", ranking.chunks().size());
        ranking.excluded().forEach((key, value) -> filters.merge(key.name(), value, Integer::sum));
        for (var chunk : ranking.chunks().stream().limit(5).toList()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("pageId", chunk.page().pageId());
            entry.addProperty("revision", chunk.page().revisionId());
            entry.addProperty("sourceKind", chunk.page().sourceKind());
            entry.addProperty("title", bounded(chunk.page().title(), 150));
            entry.addProperty("heading", bounded(chunk.heading(), 200));
            entry.addProperty("score", chunk.score());
            selected.add(entry);
        }
    }

    synchronized void reason(String value) {
        reason = value;
    }

    synchronized void finish(String fallbackReason, int attempts) {
        stage("complete");
        counts.put("outboundAttempts", attempts);
        if (reason.equals("PENDING")) {
            reason = fallbackReason;
        }
    }

    synchronized JsonObject snapshot(boolean details) {
        JsonObject result = new JsonObject();
        result.addProperty("requestId", requestId);
        result.addProperty("reason", reason);
        result.addProperty("cacheHit", cacheHit);
        JsonObject times = new JsonObject();
        stageNanos.forEach((key, value) -> times.addProperty(key, value / 1_000_000L));
        result.add("stageMillis", times);
        JsonObject amounts = new JsonObject();
        counts.forEach(amounts::addProperty);
        result.add("counts", amounts);
        JsonObject excluded = new JsonObject();
        filters.forEach(excluded::addProperty);
        result.add("excluded", excluded);
        if (details) {
            result.addProperty("topic", topic);
            result.addProperty("keywords", keywords);
            result.add("candidates", candidates.deepCopy());
            result.add("selected", selected.deepCopy());
        }
        return result;
    }

    synchronized String summary() {
        return snapshot(false).toString();
    }

    private static String bounded(String value, int maximum) {
        String clean = value.replaceAll("[\\p{Cc}\\p{Cf}]", " ");
        return clean.codePointCount(0, clean.length()) <= maximum ? clean
                : clean.substring(0, clean.offsetByCodePoints(0, maximum));
    }
}
