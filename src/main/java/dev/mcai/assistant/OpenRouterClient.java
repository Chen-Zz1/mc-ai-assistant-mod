package dev.mcai.assistant;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class OpenRouterClient implements AutoCloseable {
    enum FailureKind {
        NETWORK,
        TIMEOUT,
        RATE_LIMIT,
        SERVER,
        AUTH,
        ACCESS_BLOCKED,
        INVALID_REQUEST,
        CONTENT_REFUSED,
        INVALID_RESPONSE,
        DAILY_LIMIT,
        QUOTA_UNAVAILABLE,
        NO_KNOWLEDGE,
        REWRITE_FAILED
    }

    static final class ApiException extends Exception {
        private final FailureKind kind;

        ApiException(FailureKind kind, String message) {
            super(message);
            this.kind = kind;
        }

        ApiException(FailureKind kind, String message, Throwable cause) {
            super(message, cause);
            this.kind = kind;
        }

        FailureKind kind() {
            return kind;
        }

        boolean retryable() {
            return kind == FailureKind.NETWORK || kind == FailureKind.TIMEOUT
                    || kind == FailureKind.SERVER;
        }
    }

    record Request(String model, UUID playerId, String systemPrompt, List<ConversationStore.Exchange> history,
                   String question, int maxOutputTokens, boolean disableReasoning) {
        Request {
            Objects.requireNonNull(model);
            Objects.requireNonNull(playerId);
            Objects.requireNonNull(systemPrompt);
            history = List.copyOf(history);
            Objects.requireNonNull(question);
        }
    }

    record Reply(String text, String model, int totalTokens) {
    }

    private static final int MAX_RESPONSE_BYTES = 1_048_576;

    private final ExecutorService executor = Executors.newFixedThreadPool(2,
            Thread.ofPlatform().daemon().name("mc-ai-http-", 0).factory());
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .executor(executor)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    CompletableFuture<Reply> send(AssistantConfig config, Request request, String apiKey) {
        return send(config, request, apiKey, Duration.ofSeconds(config.requestTimeoutSeconds));
    }

    CompletableFuture<Reply> send(AssistantConfig config, Request request, String apiKey, Duration timeout) {
        String endpoint = config.protocol.equals("anthropic_messages") ? "messages" : "responses";
        URI target = URI.create(config.baseUrl + "/" + endpoint);
        String body = config.protocol.equals("anthropic_messages")
                ? buildAnthropicBody(request).toString()
                : buildResponsesBody(request).toString();

        HttpRequest.Builder builder = HttpRequest.newBuilder(target)
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("X-OpenRouter-Title", "Minecraft AI Assistant")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (config.protocol.equals("anthropic_messages")) {
            builder.header("anthropic-version", "2023-06-01");
        }

        CompletableFuture<Reply> result = new CompletableFuture<>();
        var transport = httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.limiting(
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), MAX_RESPONSE_BYTES));
        result.orTimeout(timeout.toNanos(), TimeUnit.NANOSECONDS);
        result.whenComplete((reply, failure) -> {
            if (result.isCancelled() || failure instanceof TimeoutException) {
                transport.cancel(true);
            }
        });
        transport.whenComplete((response, throwable) -> {
                    if (throwable != null) {
                        result.completeExceptionally(mapTransportFailure(throwable));
                        return;
                    }
                    try {
                        String responseBody = response.body();
                        if (response.statusCode() < 200 || response.statusCode() >= 300) {
                            throw mapHttpFailure(response.statusCode(), responseBody);
                        }
                        result.complete(parseReply(config.protocol, responseBody));
                    } catch (ApiException exception) {
                        result.completeExceptionally(exception);
                    } catch (RuntimeException exception) {
                        result.completeExceptionally(new ApiException(FailureKind.INVALID_RESPONSE,
                                "Invalid response JSON", exception));
                    }
                });
        return result;
    }

    static JsonObject buildResponsesBody(Request request) {
        JsonObject root = commonBody(request);
        root.addProperty("instructions", request.systemPrompt());
        root.add("input", messages(request.history(), request.question()));
        root.addProperty("max_output_tokens", request.maxOutputTokens());
        root.addProperty("store", false);
        root.addProperty("user", request.playerId().toString());
        if (request.disableReasoning()) {
            JsonObject reasoning = new JsonObject();
            reasoning.addProperty("effort", "none");
            root.add("reasoning", reasoning);
        }
        return root;
    }

    static JsonObject buildAnthropicBody(Request request) {
        JsonObject root = commonBody(request);
        root.addProperty("system", request.systemPrompt());
        root.add("messages", messages(request.history(), request.question()));
        root.addProperty("max_tokens", request.maxOutputTokens());
        return root;
    }

    static Reply parseReply(String protocol, String body) throws ApiException {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        if (root.has("error") && !root.get("error").isJsonNull()) {
            JsonObject error = root.get("error").isJsonObject()
                    ? root.getAsJsonObject("error") : new JsonObject();
            String code = string(error, "code", "").toLowerCase();
            String message = string(error, "message", "").toLowerCase();
            if (code.equals("429") || message.contains("rate limit")) {
                throw new ApiException(FailureKind.RATE_LIMIT, "OpenRouter rate limit reached");
            }
            if (code.contains("server") || message.contains("internal server")) {
                throw new ApiException(FailureKind.SERVER, "OpenRouter server error");
            }
            throw new ApiException(FailureKind.INVALID_RESPONSE, "OpenRouter returned an error object");
        }
        if (protocol.equals("anthropic_messages")) {
            if (string(root, "stop_reason", "").equals("max_tokens")) {
                throw new ApiException(FailureKind.INVALID_RESPONSE, "Incomplete response: max_tokens");
            }
        } else {
            String reason = root.has("incomplete_details") && root.get("incomplete_details").isJsonObject()
                    ? string(root.getAsJsonObject("incomplete_details"), "reason", "") : "";
            String status = string(root, "status", "");
            if (status.equals("incomplete") || !reason.isBlank()) {
                throw new ApiException(reason.equals("content_filter") ? FailureKind.CONTENT_REFUSED
                        : FailureKind.INVALID_RESPONSE, "Incomplete response: "
                        + (reason.isBlank() ? status : reason));
            }
            if (!status.isBlank() && !status.equals("completed")) {
                throw new ApiException(FailureKind.INVALID_RESPONSE, "Response status: " + status);
            }
        }
        String text = protocol.equals("anthropic_messages")
                ? parseAnthropicText(root)
                : parseResponsesText(root);
        if (text.isBlank()) {
            FailureKind kind = body.toLowerCase().contains("refusal")
                    ? FailureKind.CONTENT_REFUSED : FailureKind.INVALID_RESPONSE;
            throw new ApiException(kind, "Response did not contain answer text");
        }
        String model = string(root, "model", "unknown");
        int totalTokens = 0;
        if (root.has("usage") && root.get("usage").isJsonObject()) {
            JsonObject usage = root.getAsJsonObject("usage");
            totalTokens = integer(usage, "total_tokens",
                    integer(usage, "input_tokens", 0) + integer(usage, "output_tokens", 0));
        }
        return new Reply(text.strip(), model, totalTokens);
    }

    private static JsonObject commonBody(Request request) {
        JsonObject root = new JsonObject();
        root.addProperty("model", request.model());
        root.addProperty("stream", false);
        return root;
    }

    private static JsonArray messages(List<ConversationStore.Exchange> history, String question) {
        JsonArray messages = new JsonArray();
        for (ConversationStore.Exchange exchange : history) {
            messages.add(message("user", exchange.question()));
            messages.add(message("assistant", exchange.answer()));
        }
        messages.add(message("user", question));
        return messages;
    }

    private static JsonObject message(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }

    private static String parseResponsesText(JsonObject root) {
        String direct = string(root, "output_text", "");
        StringBuilder text = new StringBuilder();
        Set<String> citations = new LinkedHashSet<>();
        JsonArray output = array(root, "output");
        for (JsonElement itemElement : output) {
            if (!itemElement.isJsonObject()) {
                continue;
            }
            JsonObject item = itemElement.getAsJsonObject();
            if (!string(item, "type", "").equals("message")) {
                continue;
            }
            JsonArray content = array(item, "content");
            appendTextBlocks(text, content);
            appendCitations(citations, content);
        }
        if (text.isEmpty() && !direct.isBlank()) {
            text.append(direct);
        }
        for (String citation : citations) {
            if (text.indexOf(citation) < 0) {
                text.append("\n- ").append(citation);
            }
        }
        return text.toString();
    }

    private static String parseAnthropicText(JsonObject root) {
        StringBuilder text = new StringBuilder();
        appendTextBlocks(text, array(root, "content"));
        return text.toString();
    }

    private static void appendTextBlocks(StringBuilder result, JsonArray content) {
        for (JsonElement blockElement : content) {
            if (!blockElement.isJsonObject()) {
                continue;
            }
            JsonObject block = blockElement.getAsJsonObject();
            String type = string(block, "type", "");
            if ((type.equals("output_text") || type.equals("text")) && block.has("text")) {
                if (!result.isEmpty()) {
                    result.append('\n');
                }
                result.append(block.get("text").getAsString());
            }
        }
    }

    private static void appendCitations(Set<String> result, JsonArray content) {
        for (JsonElement blockElement : content) {
            if (!blockElement.isJsonObject()) {
                continue;
            }
            for (JsonElement annotationElement : array(blockElement.getAsJsonObject(), "annotations")) {
                if (annotationElement.isJsonObject()) {
                    JsonObject annotation = annotationElement.getAsJsonObject();
                    if (string(annotation, "type", "").equals("url_citation")) {
                        String url = string(annotation, "url", "");
                        if (url.isBlank() && annotation.has("url_citation")
                                && annotation.get("url_citation").isJsonObject()) {
                            url = string(annotation.getAsJsonObject("url_citation"), "url", "");
                        }
                        if (!url.isBlank()) {
                            result.add(url);
                        }
                    }
                }
            }
        }
    }

    private static JsonArray array(JsonObject object, String name) {
        return object.has(name) && object.get(name).isJsonArray() ? object.getAsJsonArray(name) : new JsonArray();
    }

    private static String string(JsonObject object, String name, String fallback) {
        return object.has(name) && object.get(name).isJsonPrimitive()
                ? object.get(name).getAsString() : fallback;
    }

    private static int integer(JsonObject object, String name, int fallback) {
        try {
            return object.has(name) ? object.get(name).getAsInt() : fallback;
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private static ApiException mapHttpFailure(int statusCode, String body) {
        String lowerBody = body.toLowerCase();
        if (statusCode == 401 || statusCode == 403) {
            return new ApiException(FailureKind.AUTH, "OpenRouter authentication failed");
        }
        if (statusCode == 408) {
            return new ApiException(FailureKind.TIMEOUT, "OpenRouter request timed out");
        }
        if (statusCode == 429) {
            return new ApiException(FailureKind.RATE_LIMIT, "OpenRouter rate limit reached");
        }
        if (statusCode >= 500) {
            return new ApiException(FailureKind.SERVER, "OpenRouter server error " + statusCode);
        }
        if (lowerBody.contains("moderation") || lowerBody.contains("refusal")
                || lowerBody.contains("content policy")) {
            return new ApiException(FailureKind.CONTENT_REFUSED, "OpenRouter refused the content");
        }
        return new ApiException(FailureKind.INVALID_REQUEST, "OpenRouter rejected the request with " + statusCode);
    }

    static ApiException mapTransportFailure(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        if (current instanceof ApiException apiException) {
            return apiException;
        }
        if (current instanceof HttpTimeoutException || current instanceof TimeoutException) {
            return new ApiException(FailureKind.TIMEOUT, "OpenRouter request timed out", current);
        }
        if (current instanceof IOException) {
            return new ApiException(FailureKind.NETWORK, "Unable to reach OpenRouter", current);
        }
        return new ApiException(FailureKind.INVALID_RESPONSE, "Unexpected OpenRouter client failure", current);
    }

    @Override
    public void close() {
        httpClient.shutdownNow();
        executor.shutdownNow();
    }
}
