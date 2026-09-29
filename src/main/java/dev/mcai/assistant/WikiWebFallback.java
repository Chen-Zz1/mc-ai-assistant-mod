package dev.mcai.assistant;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** A restricted source adapter, not a general web-search answer path. */
final class WikiWebFallback {
    private WikiWebFallback() { }

    static URI topicUri(String topic) {
        return articleUri("https://minecraft.wiki/w/" + URLEncoder.encode(topic.replace(' ', '_'), StandardCharsets.UTF_8)
                .replace("+", "%20"));
    }

    static URI articleUri(String value) {
        URI uri = SourceUrls.parse(value);
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())
                || !"minecraft.wiki".equalsIgnoreCase(uri.getHost())
                || (uri.getPort() != -1 && uri.getPort() != 443)
                || uri.getQuery() != null || uri.getFragment() != null) return null;
        String path = uri.getPath();
        if (path == null || !path.startsWith("/w/") || path.length() <= 3 || path.contains(":")
                || path.contains("\\") || path.contains("..") || path.codePoints().anyMatch(Character::isISOControl)) return null;
        return uri;
    }

    static List<MediaWikiClient.Page> parseScrape(String body, URI requested, long fetchedAt) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        if (!root.has("success") || !root.get("success").getAsBoolean()
                || !root.has("data") || !root.get("data").isJsonObject()) return List.of();
        var data = root.getAsJsonObject("data");
        if (!data.has("metadata") || !data.get("metadata").isJsonObject()) return List.of();
        var metadata = data.getAsJsonObject("metadata");
        URI source = articleUri(text(metadata, "sourceURL"));
        URI canonical = articleUri(text(metadata, "url"));
        if (requested == null || source == null || canonical == null || !source.equals(requested)
                || !metadata.has("statusCode") || metadata.get("statusCode").getAsInt() != 200) return List.of();
        String title = canonical.getPath().substring(3).replace('_', ' ');
        String prose = prose(text(data, "markdown"), title);
        if (prose.isBlank()) return List.of();
        // An actual requested-URL -> final-URL mapping proves an alias; search rank alone does not.
        String alias = source.getPath().substring(3).replace('_', ' ');
        List<String> aliases = WikiQuery.normalizeTitle(alias).equals(WikiQuery.normalizeTitle(title)) ? List.of() : List.of(alias);
        var page = new MediaWikiClient.Page(0, title, canonical.toString(), 0, fetchedAt, prose, aliases, "firecrawl");
        return MediaWikiClient.validPage(page, "https://minecraft.wiki/api.php") ? List.of(page) : List.of();
    }

    static List<MediaWikiClient.Page> parse(String body, long fetchedAt) throws OpenRouterClient.ApiException {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        if (!root.has("success") || !root.get("success").getAsBoolean()) {
            throw new OpenRouterClient.ApiException(OpenRouterClient.FailureKind.SERVER, "Wiki fallback search unsuccessful");
        }
        var data = root.get("data");
        var web = data != null && data.isJsonObject() ? data.getAsJsonObject().getAsJsonArray("web") : null;
        if (web == null) return List.of();
        List<MediaWikiClient.Page> pages = new ArrayList<>();
        var seen = new HashSet<String>();
        for (var item : web) {
            if (pages.size() == MediaWikiClient.MAX_PAGES_PER_QUERY) break;
            if (!item.isJsonObject()) continue;
            var entry = item.getAsJsonObject();
            URI uri = articleUri(text(entry, "url"));
            if (uri == null) continue;
            if (entry.has("metadata") && entry.get("metadata").isJsonObject()) {
                var metadata = entry.getAsJsonObject("metadata");
                if (metadata.has("statusCode") && (metadata.get("statusCode").getAsInt() < 200
                        || metadata.get("statusCode").getAsInt() >= 300)) continue;
                boolean wrongOrigin = false;
                for (String field : List.of("sourceURL", "url")) {
                    String source = text(metadata, field);
                    if (!source.isBlank() && (articleUri(source) == null
                            || !articleUri(source).getPath().equals(uri.getPath()))) wrongOrigin = true;
                }
                if (wrongOrigin) continue;
            }
            String title = uri.getPath().substring(3).replace('_', ' ');
            String prose = prose(text(entry, "markdown"), title);
            // Search snippets and challenge pages never count as encyclopedia text.
            if (prose.isBlank() || !seen.add(uri.toString())) continue;
            var page = new MediaWikiClient.Page(0, title, uri.toString(), 0, fetchedAt, prose, List.of(), "firecrawl");
            if (MediaWikiClient.validPage(page, "https://minecraft.wiki/api.php")) pages.add(page);
        }
        return List.copyOf(pages);
    }

    static String prose(String markdown, String title) {
        boolean inArticle = false;
        boolean contents = false;
        boolean fencedCode = false;
        StringBuilder result = new StringBuilder();
        for (String raw : markdown.replace("\r\n", "\n").split("\n")) {
            String line = raw.strip();
            if (!inArticle) {
                if (line.startsWith("# ") && WikiQuery.normalizeTitle(line.substring(2)
                        .replace("  Share article feedback", "")).equals(WikiQuery.normalizeTitle(title))) inArticle = true;
                continue;
            }
            if (line.startsWith("```")) {
                fencedCode = !fencedCode;
                continue;
            }
            // Flattened recipe/infobox tables lose column meaning; keep the prose contract.
            if (fencedCode || line.startsWith("|")) continue;
            line = line.replaceAll("!\\[[^\\]\\r\\n]*\\]\\([^\\r\\n]*?\\)", "")
                    .replaceAll("\\[([^\\]\\r\\n]*)\\]\\([^\\r\\n]*?\\)", "$1")
                    .replaceAll("<[^>\\r\\n]{1,500}>", "").replace("**", "").replace("__", "");
            var heading = java.util.regex.Pattern.compile("^(#{2,6})\\s+(.+)$").matcher(line);
            if (heading.matches()) {
                String label = heading.group(2).replaceAll("\\[edit.*?\\]", "")
                        .replaceAll("^[_*]+|[_*]+$", "").strip();
                contents = label.equalsIgnoreCase("Contents");
                if (contents) continue;
                String marks = "=".repeat(heading.group(1).length());
                line = marks + " " + label + " " + marks;
            }
            String chrome = line.replace("\\", "").strip();
            if (contents || line.isBlank() || chrome.equals("[edit | edit source]")
                    || chrome.equals("From Minecraft Wiki") || chrome.equals("Jump to navigation Jump to search")
                    || chrome.equals(title)) continue;
            result.append(line).append('\n');
            if (result.codePointCount(0, result.length()) >= MediaWikiClient.MAX_PAGE_CHARACTERS) break;
        }
        String value = result.toString().strip();
        return value.codePointCount(0, value.length()) <= MediaWikiClient.MAX_PAGE_CHARACTERS ? value
                : value.substring(0, value.offsetByCodePoints(0, MediaWikiClient.MAX_PAGE_CHARACTERS));
    }

    private static String text(JsonObject value, String name) {
        return value.has(name) && value.get(name).isJsonPrimitive() && value.get(name).getAsJsonPrimitive().isString()
                ? value.get(name).getAsString() : "";
    }
}
