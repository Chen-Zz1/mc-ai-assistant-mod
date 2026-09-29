package dev.mcai.assistant;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

final class MediaWikiClient implements AutoCloseable {
    static final int MAX_PAGES_PER_QUERY = 3;
    static final int MAX_PAGE_CHARACTERS = 32_000;
    private static final int MAX_RESPONSE_BYTES = 2_097_152;

    record Hit(long pageId, String title, String resolvedAlias) {
        Hit(long pageId, String title) {
            this(pageId, title, "");
        }
    }

    record Page(long pageId, String title, String url, long revisionId, long fetchedAt, String text,
                List<String> aliases, String sourceKind) {
        Page {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
            sourceKind = sourceKind == null ? "mediawiki" : sourceKind;
        }

        Page(long pageId, String title, String url, long revisionId, long fetchedAt, String text, List<String> aliases) {
            this(pageId, title, url, revisionId, fetchedAt, text, aliases, "mediawiki");
        }

        Page(long pageId, String title, String url, long revisionId, long fetchedAt, String text) {
            this(pageId, title, url, revisionId, fetchedAt, text, List.of());
        }

        Page withAlias(String alias) {
            if (alias == null || alias.isBlank() || matchesTopic(alias)) {
                return this;
            }
            var updated = new ArrayList<>(aliases);
            if (updated.size() == 8) {
                updated.removeFirst();
            }
            updated.add(alias);
            return new Page(pageId, title, url, revisionId, fetchedAt, text, updated, sourceKind);
        }

        boolean matchesTopic(String topic) {
            String normalized = WikiQuery.normalizeTitle(topic);
            return WikiQuery.normalizeTitle(title).equals(normalized)
                    || aliases.stream().anyMatch(alias -> WikiQuery.normalizeTitle(alias).equals(normalized));
        }

        URI permalink() {
            if (sourceKind.equals("firecrawl")) return URI.create(url);
            return URI.create(url + (url.contains("?") ? "&" : "?") + "oldid=" + revisionId);
        }

        String cacheKey() {
            return sourceKind.equals("firecrawl") ? "url:" + url : "id:" + pageId;
        }
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    CompletableFuture<List<Hit>> search(String apiUrl, String topic, Duration timeout) {
        // Resolve an exact title/redirect alongside full-text search, without an extra HTTP attempt.
        String exact = topic.contains("|") ? "" : "&titles=" + encode(topic) + "&redirects=1&prop=info";
        return mapped(get(apiUrl, "action=query&list=search&srnamespace=0&srlimit=" + MAX_PAGES_PER_QUERY
                + "&srprop=&srsearch=" + encode(topic) + exact, timeout), root -> parseSearch(root, topic));
    }

    CompletableFuture<Page> fetch(String apiUrl, long pageId, long fetchedAt, Duration timeout) {
        return mapped(get(apiUrl, "action=query&prop=extracts%7Cinfo%7Crevisions&pageids=" + pageId
                + "&explaintext=1&exsectionformat=wiki&rvprop=ids&inprop=url", timeout),
                root -> parsePage(root, apiUrl, fetchedAt));
    }

    private static <T> CompletableFuture<T> mapped(CompletableFuture<JsonObject> request,
                                                  Function<JsonObject, T> parse) {
        var result = request.thenApply(parse);
        result.whenComplete((reply, failure) -> {
            if (result.isCancelled()) {
                request.cancel(true);
            }
        });
        return result;
    }

    private CompletableFuture<JsonObject> get(String apiUrl, String parameters, Duration timeout) {
        URI target = URI.create(apiUrl + "?" + parameters + "&format=json&formatversion=2&maxlag=5");
        HttpRequest request = HttpRequest.newBuilder(target).timeout(timeout)
                .header("User-Agent", "MinecraftAiAssistant/0.2 (bounded user-triggered Wiki retrieval)")
                .header("Accept", "application/json").GET().build();
        CompletableFuture<JsonObject> result = new CompletableFuture<>();
        var transport = http.sendAsync(request, HttpResponse.BodyHandlers.limiting(
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), MAX_RESPONSE_BYTES));
        result.orTimeout(timeout.toNanos(), TimeUnit.NANOSECONDS);
        result.whenComplete((reply, failure) -> {
            if (result.isCancelled() || failure instanceof TimeoutException) {
                transport.cancel(true);
            }
        });
        transport.whenComplete((response, throwable) -> {
            if (throwable != null) {
                result.completeExceptionally(OpenRouterClient.mapTransportFailure(throwable));
                return;
            }
            try {
                int status = response.statusCode();
                if (status == 403 || response.headers().firstValue("cf-mitigated").orElse("").equals("challenge")) {
                    throw new OpenRouterClient.ApiException(OpenRouterClient.FailureKind.ACCESS_BLOCKED,
                            "MediaWiki access blocked");
                }
                if (status < 200 || status >= 300) {
                    var kind = switch (status) {
                        case 401 -> OpenRouterClient.FailureKind.AUTH;
                        case 408 -> OpenRouterClient.FailureKind.TIMEOUT;
                        case 429 -> OpenRouterClient.FailureKind.RATE_LIMIT;
                        default -> status >= 500 ? OpenRouterClient.FailureKind.SERVER
                                : OpenRouterClient.FailureKind.INVALID_REQUEST;
                    };
                    throw new OpenRouterClient.ApiException(kind, "MediaWiki HTTP " + status);
                }
                JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
                if (root.has("error")) {
                    String code = text(root.getAsJsonObject("error"), "code");
                    var kind = code.equals("maxlag") ? OpenRouterClient.FailureKind.SERVER
                            : code.equals("ratelimited") ? OpenRouterClient.FailureKind.RATE_LIMIT
                            : OpenRouterClient.FailureKind.INVALID_RESPONSE;
                    throw new OpenRouterClient.ApiException(kind, "MediaWiki API error");
                }
                if (!root.has("query") || !root.get("query").isJsonObject()) {
                    throw new OpenRouterClient.ApiException(OpenRouterClient.FailureKind.INVALID_RESPONSE,
                            "MediaWiki response has no query data");
                }
                result.complete(root);
            } catch (OpenRouterClient.ApiException exception) {
                result.completeExceptionally(exception);
            } catch (RuntimeException exception) {
                result.completeExceptionally(new OpenRouterClient.ApiException(
                        OpenRouterClient.FailureKind.INVALID_RESPONSE, "Invalid MediaWiki JSON", exception));
            }
        });
        return result;
    }

    static List<Hit> parseSearch(JsonObject root) {
        return parseSearch(root, "");
    }

    static List<Hit> parseSearch(JsonObject root, String topic) {
        List<Hit> hits = new ArrayList<>();
        JsonObject query = root.getAsJsonObject("query");
        // Accept only a chain that starts at the requested title and ends at a returned main-space page.
        // A search hit alone is never evidence that two titles name the same entity.
        String resolved = topic;
        if (!topic.isBlank() && !topic.contains("|")) {
            for (String field : List.of("normalized", "redirects")) {
                JsonArray mappings = query.getAsJsonArray(field);
                if (mappings != null) {
                    for (int step = 0; step < Math.min(16, mappings.size()); step++) {
                        String next = resolved;
                        for (var mapping : mappings) {
                            if (mapping.isJsonObject()) {
                                var object = mapping.getAsJsonObject();
                                if (WikiQuery.normalizeTitle(text(object, "from"))
                                        .equals(WikiQuery.normalizeTitle(resolved))) {
                                    // Section redirects are not proof that the whole page is this topic.
                                    if (!text(object, "tofragment").isBlank()) {
                                        resolved = "";
                                        next = "";
                                    } else {
                                        next = text(object, "to");
                                    }
                                    break;
                                }
                            }
                        }
                        if (next.equals(resolved)) {
                            break;
                        }
                        resolved = next;
                    }
                }
            }
            JsonArray exactPages = query.getAsJsonArray("pages");
            if (exactPages != null && !resolved.isBlank()) {
                for (var element : exactPages) {
                    if (!element.isJsonObject()) {
                        continue;
                    }
                    var page = element.getAsJsonObject();
                    String title = text(page, "title");
                    if (!page.has("missing") && !page.has("invalid") && number(page, "ns") == 0
                            && number(page, "pageid") > 0 && !title.isBlank()
                            && WikiQuery.normalizeTitle(title).equals(WikiQuery.normalizeTitle(resolved))) {
                        hits.add(new Hit(number(page, "pageid"), title,
                                WikiQuery.normalizeTitle(title).equals(WikiQuery.normalizeTitle(topic)) ? "" : topic));
                        break;
                    }
                }
            }
        }
        JsonArray items = root.getAsJsonObject("query").getAsJsonArray("search");
        if (items == null) {
            throw new IllegalArgumentException("Missing MediaWiki search results");
        }
        for (var element : items) {
            if (hits.size() >= MAX_PAGES_PER_QUERY) {
                break;
            }
            if (!element.isJsonObject()) {
                continue;
            }
            var item = element.getAsJsonObject();
            long id = number(item, "pageid");
            String title = text(item, "title");
            if (number(item, "ns") == 0 && id > 0 && !title.isBlank()
                    && hits.stream().noneMatch(hit -> hit.pageId() == id)) {
                hits.add(new Hit(id, title));
            }
        }
        return List.copyOf(hits);
    }

    static Page parsePage(JsonObject root, String apiUrl, long fetchedAt) {
        var pages = root.getAsJsonObject("query").getAsJsonArray("pages");
        if (pages == null) {
            throw new IllegalArgumentException("Missing MediaWiki pages");
        }
        for (var element : pages) {
            if (!element.isJsonObject()) {
                continue;
            }
            var item = element.getAsJsonObject();
            if (item.has("missing") || number(item, "ns") != 0) {
                continue;
            }
            var revisions = item.getAsJsonArray("revisions");
            if (revisions == null || revisions.isEmpty() || !revisions.get(0).isJsonObject()) {
                continue;
            }
            String extract = text(item, "extract").strip();
            if (extract.codePointCount(0, extract.length()) > MAX_PAGE_CHARACTERS) {
                extract = extract.substring(0, extract.offsetByCodePoints(0, MAX_PAGE_CHARACTERS));
            }
            Page page = new Page(number(item, "pageid"), text(item, "title"), text(item, "fullurl"),
                    number(revisions.get(0).getAsJsonObject(), "revid"), fetchedAt, extract);
            if (validPage(page, apiUrl)) {
                return page;
            }
        }
        return null;
    }

    static boolean validPage(Page page, String apiUrl) {
        if (page == null || page.title() == null
                || page.title().isBlank() || page.title().length() > 500 || page.text() == null || page.text().isBlank()
                || page.text().codePointCount(0, page.text().length()) > MAX_PAGE_CHARACTERS
                || page.aliases().size() > 8 || page.aliases().stream().anyMatch(alias -> alias == null
                    || alias.isBlank() || alias.codePointCount(0, alias.length()) > 150
                    || alias.codePoints().anyMatch(Character::isISOControl))) {
            return false;
        }
        if (page.sourceKind().equals("firecrawl")) {
            return page.pageId() == 0 && page.revisionId() == 0
                    && WikiWebFallback.articleUri(page.url()) != null;
        }
        if (!page.sourceKind().equals("mediawiki") || page.pageId() <= 0 || page.revisionId() <= 0) return false;
        URI url = SourceUrls.parse(page.url());
        URI api = URI.create(apiUrl);
        return url != null && url.getFragment() == null && url.getHost().equalsIgnoreCase(api.getHost())
                && url.getScheme().equalsIgnoreCase(api.getScheme()) && url.getPort() == api.getPort();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String text(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : "";
    }

    private static long number(JsonObject object, String key) {
        try {
            return object.has(key) ? object.get(key).getAsLong() : -1;
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    @Override
    public void close() {
        http.shutdownNow();
    }
}
