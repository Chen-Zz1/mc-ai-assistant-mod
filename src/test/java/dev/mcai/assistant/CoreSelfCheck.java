package dev.mcai.assistant;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class CoreSelfCheck {
    private CoreSelfCheck() {
    }

    public static void main(String[] args) throws Exception {
        checkConfigAndPayloads();
        checkSearchRewritePayload();
        checkSearchTimingFormatter();
        checkFirecrawlParsingAndTruncation();
        checkSearchPipeline();
        checkSearchPipelineRewriteFallback();
        checkConversationExpiry();
        checkQuotaPersistenceAndRollover();
        checkAnthropicTransport();
        checkRetryAndFallback();
        checkInvalidResponseFallsBack();
        checkNonRetryableFailure();
        checkChatSplitting();
        checkAnswerFormatting();
        checkClickableSources();
        ReliabilitySelfCheck.run();
        WikiSelfCheck.run();
        PersonaSelfCheck.run();
        PersonaDialogueSelfCheck.run();
        ChatDefaultsWikiSelfCheck.run();
        System.out.println("Core self-checks passed");
    }

    private static void checkConfigAndPayloads() throws Exception {
        Path directory = Files.createTempDirectory("mc-ai-config-");
        Path configFile = directory.resolve("mc_ai_assistant.json");
        AssistantConfig config = AssistantConfig.load(configFile);
        require(!config.fullConversationLog, "public defaults must not persist conversation text");
        require(!AssistantConfig.load(configFile).fullConversationLog, "generated config preserves private logging default");
        require(config.primaryModel.equals("deepseek/deepseek-v4-flash-0731:floor"), "primary model default");
        require(config.fallbackModel.equals("inclusionai/ling-3.0-flash-fin:free"), "fallback model default");
        require(config.outputMaxTokens == 1200, "output token default");
        require(config.requestTimeoutSeconds == 60, "request timeout default");
        require(config.searchEngine.equals("firecrawl"), "search engine default");
        require(!Files.readString(configFile).contains("OPENROUTER_API_KEY"), "config must not contain an API key");

        AssistantConfig invalidSearch = new AssistantConfig();
        invalidSearch.searchEngine = "unsupported";
        boolean invalidSearchRejected = false;
        try {
            invalidSearch.save(directory.resolve("invalid-search.json"));
        } catch (IllegalArgumentException expected) {
            invalidSearchRejected = true;
        }
        require(invalidSearchRejected, "invalid search engine rejected");

        OpenRouterClient.Request plain = request(config.primaryModel);
        JsonObject responses = OpenRouterClient.buildResponsesBody(plain);
        require(responses.get("model").getAsString().endsWith(":floor"), "Responses model route");
        require(!responses.has("tools"), "plain request must not search");
        require(responses.getAsJsonArray("input").size() == 3, "history and current question");

        JsonObject anthropic = OpenRouterClient.buildAnthropicBody(plain);
        require(anthropic.has("system") && anthropic.has("messages"), "Anthropic payload");
        require(!anthropic.has("tools"), "Anthropic compatibility path must not claim search");

        OpenRouterClient.Reply responsesReply = OpenRouterClient.parseReply("openai_responses", """
                {"model":"test/responses","output":[{"type":"reasoning","content":[{"type":"output_text","text":"private reasoning"}]},{"type":"message","content":[{"type":"output_text","text":"hello"}]}],"usage":{"total_tokens":7}}
                """);
        require(responsesReply.text().equals("hello") && responsesReply.totalTokens() == 7,
                "Responses parsing");
        OpenRouterClient.Reply citedReply = OpenRouterClient.parseReply("openai_responses", """
                {"model":"test/responses","output":[{"type":"message","content":[{"type":"output_text","text":"answer","annotations":[{"type":"url_citation","url":"https://example.com/a"},{"type":"url_citation","url_citation":{"url":"https://example.com/a"}},{"type":"url_citation","url_citation":{"url":"https://example.com/b"}}]}]}]}
                """);
        require(citedReply.text().equals("answer\n- https://example.com/a\n- https://example.com/b"),
                "Responses citations are preserved and deduplicated");
        OpenRouterClient.Reply messagesReply = OpenRouterClient.parseReply("anthropic_messages", """
                {"model":"test/messages","content":[{"type":"text","text":"hi"}],"usage":{"input_tokens":2,"output_tokens":3}}
                """);
        require(messagesReply.text().equals("hi") && messagesReply.totalTokens() == 5,
                "Anthropic parsing");
        try {
            OpenRouterClient.parseReply("openai_responses",
                    "{\"error\":{\"code\":429,\"message\":\"Rate limit exceeded\"}}");
            throw new AssertionError("rate limit error expected");
        } catch (OpenRouterClient.ApiException expected) {
            require(expected.kind() == OpenRouterClient.FailureKind.RATE_LIMIT && !expected.retryable(),
                    "rate limits must not trigger an immediate retry");
        }
    }

    private static void checkSearchRewritePayload() {
        OpenRouterClient.Request rewrite = request("test/rewrite", 96, true);
        JsonObject body = OpenRouterClient.buildResponsesBody(rewrite);
        require(body.get("max_output_tokens").getAsInt() <= 128,
                "search rewrite output cap stays small");
        require(!body.has("tools") && !body.has("max_tool_calls"),
                "search rewrite must not use a search tool");
        require(body.getAsJsonObject("reasoning").get("effort").getAsString().equals("none"),
                "search rewrite disables reasoning");
    }

    private static void checkSearchTimingFormatter() {
        UUID player = UUID.randomUUID();
        String line = AssistantService.formatSearchPipelineTiming("firecrawl", player, 12, 34, 56, 102, true);
        require(line.equals("Search pipeline timing for player " + player
                        + ": rewrite=12ms, firecrawl=34ms, answer=56ms, total=102ms, success=true"),
                "search pipeline timing formatter");
    }

    private static void checkFirecrawlParsingAndTruncation() throws Exception {
        JsonObject root = new JsonObject();
        root.addProperty("success", true);
        JsonObject data = new JsonObject();
        JsonArray web = new JsonArray();
        JsonObject first = new JsonObject();
        first.addProperty("title", "First result");
        first.addProperty("url", "https://example.com/first");
        first.addProperty("markdown", "长".repeat(2_501));
        web.add(first);
        JsonObject second = new JsonObject();
        second.addProperty("title", "Second result");
        second.addProperty("url", "https://example.com/second");
        second.addProperty("description", "description fallback");
        web.add(second);
        JsonObject third = new JsonObject();
        third.addProperty("title", "Third result");
        third.addProperty("url", "https://example.com/third");
        third.addProperty("markdown", "must be capped by result limit");
        web.add(third);
        data.add("web", web);
        root.add("data", data);

        List<FirecrawlClient.SearchResult> results = FirecrawlClient.parseResults(root.toString(), 2);
        require(results.size() == 2, "Firecrawl result count is capped");
        require(results.get(0).content().codePointCount(0, results.get(0).content().length()) == 2_501,
                "Firecrawl body is truncated to the configured bound plus marker");
        require(results.get(0).content().endsWith("…"), "Firecrawl truncation marker");
        require(results.get(1).content().equals("description fallback"),
                "Firecrawl description fallback is parsed");
    }

    private static void checkSearchPipeline() throws Exception {
        String rewrittenQuery = "Minecraft Java Edition 26.2 diamond uses";
        String originalQuestion = "钻石有什么用";
        List<String> stages = new CopyOnWriteArrayList<>();
        try (MockServer mock = new MockServer(exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/responses")) {
                JsonObject body = readJson(exchange);
                if (stages.isEmpty()) {
                    stages.add("rewrite");
                    require(!body.has("tools"), "rewrite request has no search tools");
                    require(body.get("max_output_tokens").getAsInt() <= 128,
                            "rewrite request has a small output cap");
                    respond(exchange, 200, "{\"model\":\"test/rewrite\",\"output_text\":\""
                            + rewrittenQuery + "\"}");
                } else {
                    stages.add("answer");
                    require(!body.has("tools"), "answer request has no search tools");
                    require(body.getAsJsonObject("reasoning").get("effort").getAsString().equals("none"),
                            "search answer disables reasoning");
                    require(body.toString().contains("https://example.com/minecraft"),
                            "answer request includes the search URL");
                    require(body.toString().contains(originalQuestion),
                            "answer request includes the original question");
                    respond(exchange, 200, "{\"model\":\"test/answer\",\"output_text\":\"search answer\"}");
                }
                return;
            }
            require(path.endsWith("/search"), "search pipeline Firecrawl endpoint");
            JsonObject body = readJson(exchange, "Bearer firecrawl-test-key");
            stages.add("firecrawl");
            require(body.get("query").getAsString().equals(rewrittenQuery),
                    "Firecrawl receives the rewritten query");
            require(body.get("limit").getAsInt() == 2, "Firecrawl receives the result limit");
            respond(exchange, 200, firecrawlResponse("https://example.com/minecraft"));
        })) {
            Path directory = serviceConfig(mock.baseUrl(), 10, mock.firecrawlBaseUrl());
            try (AssistantService service = new AssistantService(directory, Clock.systemUTC())) {
                CompletableFuture<AssistantService.Outcome> completed = new CompletableFuture<>();
                AssistantService.PlayerRequest request = new AssistantService.PlayerRequest(
                        UUID.randomUUID(), "TestPlayer", originalQuestion, AssistantService.Retrieval.WEB, true);
                require(service.submit(request, completed::complete).accepted(), "search pipeline accepted");
                AssistantService.Outcome outcome = completed.get(10, TimeUnit.SECONDS);
                require(outcome.success() && outcome.text().equals("search answer")
                                && outcome.sources().equals(List.of(URI.create("https://example.com/minecraft"))),
                        "search pipeline answer succeeds");
                require(stages.equals(List.of("rewrite", "firecrawl", "answer")),
                        "search pipeline order is rewrite, Firecrawl, answer");
            }
        }
    }

    private static void checkSearchPipelineRewriteFallback() throws Exception {
        String originalQuestion = "它什么时候发布";
        String expectedFallbackQuery = "Minecraft Java Edition 26.2 " + originalQuestion;
        List<String> stages = new CopyOnWriteArrayList<>();
        AtomicInteger responseCalls = new AtomicInteger();
        try (MockServer mock = new MockServer(exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/responses")) {
                JsonObject body = readJson(exchange);
                if (responseCalls.incrementAndGet() == 1) {
                    stages.add("rewrite");
                    respond(exchange, 400, "{\"error\":{\"message\":\"rewrite rejected\"}}");
                } else {
                    stages.add("answer");
                    require(body.toString().contains("https://example.com/fallback"),
                            "fallback answer includes search results");
                    respond(exchange, 200, "{\"model\":\"test/answer\",\"output_text\":\"fallback answer\"}");
                }
                return;
            }
            require(path.endsWith("/search"), "fallback search Firecrawl endpoint");
            JsonObject body = readJson(exchange, "Bearer firecrawl-test-key");
            stages.add("firecrawl");
            require(body.get("query").getAsString().equals(expectedFallbackQuery),
                    "rewrite failure falls back to the original question");
            respond(exchange, 200, firecrawlResponse("https://example.com/fallback"));
        })) {
            Path directory = serviceConfig(mock.baseUrl(), 10, mock.firecrawlBaseUrl());
            try (AssistantService service = new AssistantService(directory, Clock.systemUTC())) {
                CompletableFuture<AssistantService.Outcome> completed = new CompletableFuture<>();
                AssistantService.PlayerRequest request = new AssistantService.PlayerRequest(
                        UUID.randomUUID(), "TestPlayer", originalQuestion, AssistantService.Retrieval.WEB, true);
                require(service.submit(request, completed::complete).accepted(),
                        "rewrite fallback request accepted");
                AssistantService.Outcome outcome = completed.get(10, TimeUnit.SECONDS);
                require(outcome.success() && outcome.text().equals("fallback answer")
                                && outcome.sources().equals(List.of(URI.create("https://example.com/fallback"))),
                        "rewrite fallback still answers");
                require(responseCalls.get() == 2, "rewrite failure does not trigger another rewrite request");
                require(stages.equals(List.of("rewrite", "firecrawl", "answer")),
                        "rewrite fallback preserves search pipeline order");
            }
        }
    }

    private static String firecrawlResponse(String url) {
        return "{\"success\":true,\"data\":{\"web\":[{\"title\":\"Minecraft source\","
                + "\"url\":\"" + url + "\",\"markdown\":\"source content\"}]}}";
    }

    private static void checkConversationExpiry() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-30T00:00:00Z"), ZoneId.of("UTC"));
        ConversationStore store = new ConversationStore(clock);
        UUID player = UUID.randomUUID();
        store.record(player, "q1", "a1", 2);
        store.record(player, "q2", "a2", 2);
        store.record(player, "q3", "a3", 2);
        List<ConversationStore.Exchange> history = store.history(player, 2, Duration.ofMinutes(30));
        require(history.size() == 2 && history.getFirst().question().equals("q2"), "bounded history");
        clock.advance(Duration.ofMinutes(31));
        require(store.history(player, 2, Duration.ofMinutes(30)).isEmpty(), "idle expiry");
    }

    private static void checkQuotaPersistenceAndRollover() throws Exception {
        Path stateFile = Files.createTempDirectory("mc-ai-quota-").resolve("quota.json");
        MutableClock clock = new MutableClock(Instant.parse("2026-08-30T12:00:00Z"), ZoneId.of("UTC"));
        DailyQuota quota = new DailyQuota(stateFile, 2, ZoneId.of("UTC"), clock);
        require(quota.tryAcquire(), "quota request one");
        require(quota.tryAcquire(), "quota request two");
        require(!quota.tryAcquire(), "quota request three denied");
        DailyQuota restored = new DailyQuota(stateFile, 2, ZoneId.of("UTC"), clock);
        require(restored.snapshot().used() == 2, "quota survives restart");
        clock.advance(Duration.ofDays(1));
        require(restored.tryAcquire() && restored.snapshot().used() == 1, "quota day rollover");
    }

    private static void checkRetryAndFallback() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        List<String> models = new CopyOnWriteArrayList<>();
        try (MockServer mock = new MockServer(exchange -> {
            JsonObject body = readJson(exchange);
            models.add(body.get("model").getAsString());
            int call = calls.incrementAndGet();
            if (call == 1) {
                require(body.getAsJsonObject("reasoning").get("effort").getAsString().equals("none"),
                        "ordinary answer disables reasoning");
            }
            if (call <= 2) {
                respond(exchange, 500, "{\"error\":{\"message\":\"temporary\"}}");
            } else {
                respond(exchange, 200, "{\"model\":\"inclusionai/ling-3.0-flash-fin:free\",\"output_text\":\"fallback ok\"}");
            }
        })) {
            Path directory = serviceConfig(mock.baseUrl(), 10);
            try (AssistantService service = new AssistantService(directory, Clock.systemUTC())) {
                CompletableFuture<AssistantService.Outcome> completed = new CompletableFuture<>();
                AssistantService.Submission submission = service.submit(playerRequest(), completed::complete);
                require(submission.accepted(), "retry request accepted");
                AssistantService.Outcome outcome = completed.get(10, TimeUnit.SECONDS);
                require(outcome.success() && outcome.text().equals("fallback ok"), "fallback succeeds");
                require(calls.get() == 3, "initial, retry, fallback call count");
                require(models.get(0).endsWith(":floor") && models.get(1).endsWith(":floor"),
                        "primary used for initial and retry");
                require(models.get(2).equals("inclusionai/ling-3.0-flash-fin:free"), "fallback model used");
                require(service.status().contains("daily=3/10"), "all outbound attempts count toward quota");
            }
        }
    }

    private static void checkInvalidResponseFallsBack() throws Exception {
        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger fallbackCalls = new AtomicInteger();
        try (MockServer mock = new MockServer(exchange -> {
            JsonObject body = readJson(exchange);
            String model = body.get("model").getAsString();
            require(body.getAsJsonObject("reasoning").get("effort").getAsString().equals("none"),
                    "invalid response requests disable reasoning");
            if (model.endsWith(":floor")) {
                primaryCalls.incrementAndGet();
                respond(exchange, 200, "{\"model\":\"test/empty\",\"output_text\":\"\"}");
            } else {
                require(model.equals("inclusionai/ling-3.0-flash-fin:free"), "invalid response fallback model");
                fallbackCalls.incrementAndGet();
                respond(exchange, 200, "{\"model\":\"test/fallback\",\"output_text\":\"fallback after empty\"}");
            }
        })) {
            Path directory = serviceConfig(mock.baseUrl(), 10);
            try (AssistantService service = new AssistantService(directory, Clock.systemUTC())) {
                CompletableFuture<AssistantService.Outcome> completed = new CompletableFuture<>();
                require(service.submit(playerRequest(), completed::complete).accepted(),
                        "invalid response request accepted");
                AssistantService.Outcome outcome = completed.get(10, TimeUnit.SECONDS);
                require(outcome.success() && outcome.text().equals("fallback after empty"),
                        "invalid response falls back successfully");
                require(primaryCalls.get() == 1 && fallbackCalls.get() == 1,
                        "invalid response uses one primary and one fallback request");
            }
        }
    }

    private static void checkAnthropicTransport() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (MockServer mock = new MockServer(exchange -> {
            require(exchange.getRequestURI().getPath().endsWith("/messages"), "Anthropic endpoint");
            require("2023-06-01".equals(exchange.getRequestHeaders().getFirst("anthropic-version")),
                    "Anthropic version header");
            JsonObject body = readJson(exchange);
            require(body.has("system") && body.has("max_tokens"), "Anthropic wire body");
            calls.incrementAndGet();
            respond(exchange, 200, "{\"model\":\"test/messages\",\"content\":[{\"type\":\"text\",\"text\":\"messages ok\"}]}");
        })) {
            AssistantConfig config = new AssistantConfig();
            config.protocol = "anthropic_messages";
            config.baseUrl = mock.baseUrl();
            config.requestTimeoutSeconds = 5;
            try (OpenRouterClient client = new OpenRouterClient()) {
                OpenRouterClient.Reply reply = client.send(config, request(config.primaryModel),
                        "test-only-key").get(10, TimeUnit.SECONDS);
                require(reply.text().equals("messages ok") && calls.get() == 1, "Anthropic transport");
            }
        }
    }

    private static void checkNonRetryableFailure() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (MockServer mock = new MockServer(exchange -> {
            calls.incrementAndGet();
            readJson(exchange);
            respond(exchange, 401, "{\"error\":{\"message\":\"unauthorized\"}}");
        })) {
            Path directory = serviceConfig(mock.baseUrl(), 10);
            try (AssistantService service = new AssistantService(directory, Clock.systemUTC())) {
                CompletableFuture<AssistantService.Outcome> completed = new CompletableFuture<>();
                require(service.submit(playerRequest(), completed::complete).accepted(), "auth request accepted");
                AssistantService.Outcome outcome = completed.get(10, TimeUnit.SECONDS);
                require(!outcome.success() && outcome.failureKind() == OpenRouterClient.FailureKind.AUTH,
                        "auth failure classification");
                require(calls.get() == 1, "auth failure must not retry or fallback");
            }
        }
    }

    private static void checkChatSplitting() {
        String answer = "测".repeat(450);
        List<String> chunks = AiCommands.splitForChat(answer);
        require(chunks.size() == 3, "chat answer chunk count");
        require(chunks.stream().allMatch(chunk -> chunk.codePointCount(0, chunk.length()) <= 220),
                "chat chunk size");
    }

    private static void checkAnswerFormatting() {
        Component title = AiCommands.answerTitle("TestPlayer", false);
        require(title.getString().contains("AI 回答") && title.getString().contains("TestPlayer"),
                "answer title contains player name");
        require(AiCommands.answerTitle("TestPlayer", true).getString().contains("AI 私人回答"),
                "private answer title marker");

        Component question = AiCommands.questionLine("钻石有什么用");
        require(question.getString().equals("问题：钻石有什么用"), "question line contains original question");
        Component chunk = AiCommands.answerChunk("回答正文");
        require(chunk.getString().equals("▍ 回答正文")
                        && chunk.toFlatList().get(0).getStyle().getColor() != null,
                "answer chunk has the unified marker");
    }

    private static void checkClickableSources() {
        List<URI> sources = SourceUrls.validated(
                List.of("https://example.com/a", "https://example.com/a", "https://example.com/b"));
        require(sources.equals(List.of(URI.create("https://example.com/a"), URI.create("https://example.com/b"))),
                "source URLs are unique and ordered");

        Component sourceLine = AiCommands.sourceLinks(sources);
        List<Component> links = sourceLine.toFlatList().stream()
                .filter(component -> component.getStyle().getClickEvent() instanceof ClickEvent.OpenUrl)
                .toList();
        require(links.size() == 2, "source labels have click events");
        require(((ClickEvent.OpenUrl) links.get(0).getStyle().getClickEvent()).uri().toString()
                        .equals("https://example.com/a"),
                "first source click event URL");
    }

    private static OpenRouterClient.Request request(String model) {
        return request(model, 1200, false);
    }

    private static OpenRouterClient.Request request(String model, int maxOutputTokens,
                                                    boolean disableReasoning) {
        return new OpenRouterClient.Request(model, UUID.randomUUID(), "system",
                List.of(new ConversationStore.Exchange("old question", "old answer")),
                "new question", maxOutputTokens, disableReasoning);
    }

    private static AssistantService.PlayerRequest playerRequest() {
        return new AssistantService.PlayerRequest(UUID.randomUUID(), "TestPlayer", "hello",
                AssistantService.Retrieval.NONE, true);
    }

    private static Path serviceConfig(String baseUrl, int dailyLimit) throws Exception {
        return serviceConfig(baseUrl, dailyLimit, null);
    }

    private static Path serviceConfig(String baseUrl, int dailyLimit, String firecrawlBaseUrl) throws Exception {
        Path directory = Files.createTempDirectory("mc-ai-service-");
        Files.writeString(directory.resolve("test.secret"), "test-only-key", StandardCharsets.UTF_8);
        AssistantConfig config = new AssistantConfig();
        config.baseUrl = baseUrl;
        config.secretFile = "test.secret";
        if (firecrawlBaseUrl != null) {
            Files.writeString(directory.resolve("firecrawl.secret"), "firecrawl-test-key", StandardCharsets.UTF_8);
            config.firecrawlBaseUrl = firecrawlBaseUrl;
            config.firecrawlSecretFile = "firecrawl.secret";
            config.searchMaxResults = 2;
        }
        config.playerCooldownSeconds = 0;
        config.dailyRequestLimit = dailyLimit;
        config.fullConversationLog = false;
        config.requestTimeoutSeconds = 5;
        config.save(directory.resolve("mc_ai_assistant.json"));
        return directory;
    }

    private static JsonObject readJson(HttpExchange exchange) throws IOException {
        return readJson(exchange, "Bearer test-only-key");
    }

    private static JsonObject readJson(HttpExchange exchange, String expectedAuthorization) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        require(expectedAuthorization.equals(exchange.getRequestHeaders().getFirst("Authorization")),
                "authorization header");
        return JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void require(boolean condition, String description) {
        if (!condition) {
            throw new AssertionError("Self-check failed: " + description);
        }
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private static final class MockServer implements AutoCloseable {
        private final HttpServer server;

        MockServer(Handler handler) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/v1/responses", exchange -> handler.handle(exchange));
            server.createContext("/api/v1/messages", exchange -> handler.handle(exchange));
            server.createContext("/v2/search", exchange -> handler.handle(exchange));
            server.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1";
        }

        String firecrawlBaseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/v2";
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zone;

        MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId newZone) {
            return new MutableClock(instant, newZone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
