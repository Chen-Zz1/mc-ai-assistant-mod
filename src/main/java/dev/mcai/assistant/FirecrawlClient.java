package dev.mcai.assistant;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class FirecrawlClient implements AutoCloseable {
    record SearchResult(String title, String url, String content) {
    }

    private static final int MAX_RESPONSE_BYTES = 2_097_152;
    private static final int MAX_CONTENT_CHARACTERS = 2_500;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    CompletableFuture<List<SearchResult>> search(AssistantConfig config, String query, String apiKey) {
        return search(config, query, apiKey, Duration.ofSeconds(config.requestTimeoutSeconds));
    }

    CompletableFuture<List<SearchResult>> search(AssistantConfig config, String query, String apiKey,
                                                Duration timeout) {
        return search(config, query, apiKey, timeout, false, body -> parseResults(body, config.searchMaxResults));
    }

    CompletableFuture<List<MediaWikiClient.Page>> searchWiki(AssistantConfig config, WikiQuery query, String apiKey,
                                                           Duration timeout, long fetchedAt) {
        String terms = String.join(" ", query.retrievalTerms());
        return search(config, terms, apiKey, timeout, true, body -> WikiWebFallback.parse(body, fetchedAt));
    }

    CompletableFuture<List<MediaWikiClient.Page>> scrapeWiki(AssistantConfig config, URI article, String apiKey,
                                                           Duration timeout, long fetchedAt) {
        if (article == null || WikiWebFallback.articleUri(article.toString()) == null) {
            throw new IllegalArgumentException("Wiki scrape requires an English Wiki article URL");
        }
        JsonObject body = new JsonObject();
        body.addProperty("url", article.toString());
        JsonArray formats = new JsonArray(); formats.add("markdown"); body.add("formats", formats);
        body.addProperty("onlyMainContent", true);
        return send(config, "scrape", body, apiKey, timeout, response -> WikiWebFallback.parseScrape(response, article, fetchedAt));
    }

    @FunctionalInterface
    private interface ResponseParser<T> {
        T parse(String body) throws OpenRouterClient.ApiException;
    }

    private <T> CompletableFuture<T> search(AssistantConfig config, String query, String apiKey,
                                            Duration timeout, boolean wiki, ResponseParser<T> parser) {
        JsonObject body = new JsonObject();
        body.addProperty("query", query);
        body.addProperty("limit", wiki ? MediaWikiClient.MAX_PAGES_PER_QUERY : config.searchMaxResults);
        if (wiki) {
            JsonArray domains = new JsonArray();
            domains.add("minecraft.wiki");
            body.add("includeDomains", domains);
        }
        JsonArray sources = new JsonArray();
        sources.add("web");
        body.add("sources", sources);
        body.addProperty("ignoreInvalidURLs", true);
        JsonObject scrapeOptions = new JsonObject();
        JsonArray formats = new JsonArray();
        formats.add("markdown");
        scrapeOptions.add("formats", formats);
        if (wiki) scrapeOptions.addProperty("onlyMainContent", true);
        body.add("scrapeOptions", scrapeOptions);
        return send(config, "search", body, apiKey, timeout, parser);
    }

    private <T> CompletableFuture<T> send(AssistantConfig config, String endpoint, JsonObject body, String apiKey,
                                         Duration timeout, ResponseParser<T> parser) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.firecrawlBaseUrl + "/" + endpoint))
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        CompletableFuture<T> result = new CompletableFuture<>();
        var transport = httpClient.sendAsync(request, HttpResponse.BodyHandlers.limiting(
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
                        String responseBody = response.body();
                        if (response.statusCode() < 200 || response.statusCode() >= 300) {
                            throw mapHttpFailure(response.statusCode());
                        }
                        result.complete(parser.parse(responseBody));
                    } catch (OpenRouterClient.ApiException exception) {
                        result.completeExceptionally(exception);
                    } catch (RuntimeException exception) {
                        result.completeExceptionally(new OpenRouterClient.ApiException(
                                OpenRouterClient.FailureKind.INVALID_RESPONSE,
                                "Invalid Firecrawl response JSON", exception));
                    }
                });
        return result;
    }

    static List<SearchResult> parseResults(String body, int limit) throws OpenRouterClient.ApiException {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        if (!root.has("success") || !root.get("success").getAsBoolean()) {
            throw new OpenRouterClient.ApiException(OpenRouterClient.FailureKind.SERVER,
                    "Firecrawl search was unsuccessful");
        }
        JsonObject data = root.has("data") && root.get("data").isJsonObject()
                ? root.getAsJsonObject("data") : new JsonObject();
        JsonArray web = data.has("web") && data.get("web").isJsonArray()
                ? data.getAsJsonArray("web") : new JsonArray();
        List<SearchResult> results = new ArrayList<>();
        Set<String> seenUrls = new HashSet<>();
        for (JsonElement element : web) {
            if (results.size() >= limit) {
                break;
            }
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject item = element.getAsJsonObject();
            String url = text(item, "url");
            if (SourceUrls.parse(url) == null) {
                continue;
            }
            String content = text(item, "markdown");
            if (content.isBlank()) {
                content = text(item, "description");
            }
            if (content.isBlank() || !seenUrls.add(url)) {
                continue;
            }
            results.add(new SearchResult(text(item, "title"), url,
                    truncate(content.strip(), MAX_CONTENT_CHARACTERS)));
        }
        return List.copyOf(results);
    }

    static String formatResults(List<SearchResult> results) {
        StringBuilder formatted = new StringBuilder();
        for (int index = 0; index < results.size(); index++) {
            SearchResult result = results.get(index);
            formatted.append('[').append(index + 1).append("] ")
                    .append(result.title().isBlank() ? result.url() : result.title()).append('\n')
                    .append("URL: ").append(result.url()).append('\n')
                    .append(result.content()).append("\n\n");
        }
        return formatted.toString().strip();
    }

    private static String text(JsonObject object, String name) {
        return object.has(name) && object.get(name).isJsonPrimitive()
                ? object.get(name).getAsString() : "";
    }

    private static String truncate(String value, int maxCodePoints) {
        int count = value.codePointCount(0, value.length());
        return count <= maxCodePoints ? value
                : value.substring(0, value.offsetByCodePoints(0, maxCodePoints)) + "…";
    }

    private static OpenRouterClient.ApiException mapHttpFailure(int statusCode) {
        OpenRouterClient.FailureKind kind = switch (statusCode) {
            case 401, 403 -> OpenRouterClient.FailureKind.AUTH;
            case 408 -> OpenRouterClient.FailureKind.TIMEOUT;
            case 429 -> OpenRouterClient.FailureKind.RATE_LIMIT;
            default -> statusCode >= 500 ? OpenRouterClient.FailureKind.SERVER
                    : OpenRouterClient.FailureKind.INVALID_REQUEST;
        };
        return new OpenRouterClient.ApiException(kind, "Firecrawl HTTP " + statusCode);
    }

    @Override
    public void close() {
        httpClient.shutdownNow();
    }
}
