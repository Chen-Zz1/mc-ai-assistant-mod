package dev.mcai.assistant;

import com.google.gson.Gson;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

final class WikiCache {
    static final int MAX_PAGES = 128;
    static final int MAX_CHUNKS = 5;
    static final int CHUNK_CHARACTERS = 1_000;
    static final Duration TTL = Duration.ofDays(7);
    static final String ATTRIBUTION = "Minecraft Wiki contributors — CC BY-NC-SA 3.0";
    static final String LICENSE_URL = "https://creativecommons.org/licenses/by-nc-sa/3.0/";
    private static final Gson GSON = new Gson();
    private static final Pattern HEADING = Pattern.compile("^(={2,6})\\s*(.*?)\\s*\\1\\s*$");
    private static final Pattern VERSION_PAGE = Pattern.compile(
            "(?i)^(?:Java|Bedrock) Edition (\\d+\\.\\d+(?:\\.\\d+)*)\\b");

    record Chunk(MediaWikiClient.Page page, String heading, String body, int score) {
    }

    record Grounding(String text, List<URI> sources) {
    }

    enum Filter { TOPIC_MISMATCH, EDITION_FILTERED, VERSION_FILTERED, HISTORY_FILTERED, NON_CONTENT_SECTION }

    record Ranking(List<Chunk> chunks, int examined, Map<Filter, Integer> excluded) {
        String failureReason() {
            return excluded.entrySet().stream().filter(entry -> examined > 0 && entry.getValue() == examined)
                    .map(entry -> entry.getKey().name()).findFirst().orElse("NO_RELEVANT_CHUNKS");
        }
    }

    private record State(int schemaVersion, String apiUrl, String attribution, String licenseUrl,
                         List<MediaWikiClient.Page> pages) {
    }

    private final Path file;
    private final String apiUrl;
    private final Clock clock;
    private final Map<String, MediaWikiClient.Page> pages = new LinkedHashMap<>();

    WikiCache(Path file, String apiUrl, Clock clock) {
        this.file = file;
        this.apiUrl = apiUrl;
        this.clock = clock;
        if (Files.notExists(file)) {
            return;
        }
        try {
            if (Files.size(file) > 20_971_520) {
                throw new IOException("Wiki cache exceeds size limit");
            }
            State loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), State.class);
            if (loaded == null || loaded.schemaVersion() != 1 || !apiUrl.equals(loaded.apiUrl())
                    || loaded.pages() == null) {
                return;
            }
            for (var page : loaded.pages()) {
                if (MediaWikiClient.validPage(page, apiUrl) && fresh(page)) {
                    pages.put(page.cacheKey(), page);
                }
            }
            prune();
        } catch (IOException | RuntimeException exception) {
            pages.clear();
            MinecraftAiAssistant.LOGGER.warn("Wiki cache unavailable; it will be rebuilt on demand");
        }
    }

    synchronized void putAll(List<MediaWikiClient.Page> additions) throws IOException {
        for (var page : additions) {
            if (MediaWikiClient.validPage(page, apiUrl) && fresh(page)) {
                pages.put(page.cacheKey(), page);
            }
        }
        prune();
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            var state = new State(1, apiUrl, ATTRIBUTION, LICENSE_URL, List.copyOf(pages.values()));
            Files.writeString(temporary, GSON.toJson(state), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanup) {
                exception.addSuppressed(cleanup);
            }
            throw exception;
        }
    }

    synchronized MediaWikiClient.Page page(long pageId) {
        var page = pages.get("id:" + pageId);
        return page != null && fresh(page) ? page : null;
    }

    synchronized boolean hasTopic(WikiQuery query) {
        return pages.values().stream().anyMatch(page -> fresh(page) && page.matchesTopic(query.topic()));
    }

    synchronized int size() {
        prune();
        return pages.size();
    }

    synchronized List<Chunk> search(WikiQuery query) {
        return rank(query).chunks();
    }

    synchronized Ranking rank(WikiQuery query) {
        prune();
        var terms = query.retrievalTerms();
        var topicTerms = WikiQuery.terms(query.topic());
        List<Chunk> ranked = new ArrayList<>();
        Map<Filter, Integer> excluded = new java.util.EnumMap<>(Filter.class);
        int examined = 0;
        // ponytail: linear scan is bounded to 128 pages; use a full-text index if this bound grows materially.
        for (var page : pages.values()) {
            for (var chunk : chunks(page)) {
                examined++;
                Filter filter = incompatibility(query, page.title(), chunk.heading());
                if (filter != null) {
                    excluded.merge(filter, 1, Integer::sum);
                    continue;
                }
                var title = WikiQuery.terms(page.title() + " " + String.join(" ", page.aliases()));
                var heading = WikiQuery.terms(chunk.heading());
                var body = WikiQuery.terms(chunk.body());
                if (topicTerms.isEmpty() || (!page.matchesTopic(query.topic()) && !topicTerms.stream().allMatch(term ->
                        title.contains(term) || heading.contains(term) || body.contains(term)))) {
                    excluded.merge(Filter.TOPIC_MISMATCH, 1, Integer::sum);
                    continue;
                }
                int score = 0;
                for (String term : terms) {
                    score += title.contains(term) ? 5 : 0;
                    score += heading.contains(term) ? 3 : 0;
                    score += body.contains(term) ? 1 : 0;
                }
                String phrase = WikiQuery.normalizeTitle(query.keywords());
                if (!phrase.isBlank() && WikiQuery.normalizeTitle(chunk.body()).contains(phrase)) {
                    score += 2;
                }
                if (score > 0) {
                    ranked.add(new Chunk(page, chunk.heading(), chunk.body(), score));
                }
            }
        }
        ranked.sort(Comparator.comparingInt(Chunk::score).reversed()
                .thenComparing(chunk -> chunk.page().title()).thenComparing(Chunk::heading));
        var seen = new HashSet<String>();
        return new Ranking(ranked.stream().filter(chunk -> seen.add(chunk.body())).limit(MAX_CHUNKS).toList(),
                examined, Map.copyOf(excluded));
    }

    static boolean compatible(WikiQuery query, String title, String heading) {
        return incompatibility(query, title, heading) == null;
    }

    static Filter incompatibility(WikiQuery query, String title, String heading) {
        String section = heading.split(" > ", 2)[0].toLowerCase(Locale.ROOT);
        if (java.util.Set.of("issues", "external links", "references", "navigation", "gallery", "see also")
                .contains(section)) {
            return Filter.NON_CONTENT_SECTION;
        }
        String scope = (title + " " + heading).toLowerCase(Locale.ROOT);
        boolean bedrock = scope.contains("bedrock edition");
        boolean java = scope.contains("java edition");
        if (query.edition().equals("java") && bedrock && !java
                || query.edition().equals("bedrock") && java && !bedrock) {
            return Filter.EDITION_FILTERED;
        }
        var version = VERSION_PAGE.matcher(title);
        if (!query.version().isBlank() && version.find() && !version.group(1).equals(query.version())) {
            return Filter.VERSION_FILTERED;
        }
        return query.history() || !heading.toLowerCase(Locale.ROOT).contains("history")
                ? null : Filter.HISTORY_FILTERED;
    }

    static List<Chunk> chunks(MediaWikiClient.Page page) {
        List<Chunk> chunks = new ArrayList<>();
        Map<Integer, String> headings = new TreeMap<>();
        StringBuilder section = new StringBuilder();
        for (String line : page.text().replace("\r\n", "\n").split("\n")) {
            var heading = HEADING.matcher(line.strip());
            if (heading.matches()) {
                appendChunks(chunks, page, String.join(" > ", headings.values()), section.toString());
                section.setLength(0);
                int level = heading.group(1).length();
                headings.keySet().removeIf(existing -> existing >= level);
                headings.put(level, heading.group(2));
            } else {
                section.append(line).append('\n');
            }
        }
        appendChunks(chunks, page, String.join(" > ", headings.values()), section.toString());
        return List.copyOf(chunks);
    }

    private static void appendChunks(List<Chunk> chunks, MediaWikiClient.Page page, String heading, String text) {
        String remaining = text.strip();
        while (!remaining.isBlank()) {
            int end = remaining.codePointCount(0, remaining.length()) <= CHUNK_CHARACTERS
                    ? remaining.length() : remaining.offsetByCodePoints(0, CHUNK_CHARACTERS);
            if (end < remaining.length()) {
                int boundary = Math.max(remaining.lastIndexOf('\n', end), remaining.lastIndexOf(' ', end));
                if (boundary > end / 2) {
                    end = boundary;
                }
            }
            chunks.add(new Chunk(page, heading.isBlank() ? "Overview" : heading, remaining.substring(0, end).strip(), 0));
            remaining = remaining.substring(end).strip();
        }
    }

    static Grounding grounding(List<Chunk> chunks) {
        Map<URI, Integer> sources = new LinkedHashMap<>();
        StringBuilder text = new StringBuilder();
        for (var chunk : chunks) {
            URI source = chunk.page().permalink();
            int index = sources.computeIfAbsent(source, ignored -> sources.size() + 1);
            text.append('[').append(index).append("] ").append(chunk.page().title())
                    .append(" — ").append(chunk.heading()).append('\n')
                    .append("Retrieval: ").append(chunk.page().sourceKind()).append("; Revision: ")
                    .append(chunk.page().revisionId() > 0 ? Long.toString(chunk.page().revisionId()) : "unavailable (current page)")
                    .append("; fetched: ").append(Instant.ofEpochMilli(chunk.page().fetchedAt())).append('\n')
                    .append("URL: ").append(source).append('\n')
                    .append(chunk.body()).append("\n\n");
        }
        return new Grounding(text.toString().strip(), List.copyOf(sources.keySet()));
    }

    private boolean fresh(MediaWikiClient.Page page) {
        long age = clock.millis() - page.fetchedAt();
        return age >= 0 && age < TTL.toMillis();
    }

    private void prune() {
        pages.values().removeIf(page -> !fresh(page));
        if (pages.size() > MAX_PAGES) {
            var oldest = pages.values().stream().sorted(Comparator.comparingLong(MediaWikiClient.Page::fetchedAt)
                    .thenComparingLong(MediaWikiClient.Page::pageId)).toList();
            for (int index = 0; index < oldest.size() - MAX_PAGES; index++) {
                pages.remove(oldest.get(index).cacheKey());
            }
        }
    }
}
