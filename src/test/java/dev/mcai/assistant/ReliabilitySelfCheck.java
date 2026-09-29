package dev.mcai.assistant;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.network.chat.ClickEvent;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class ReliabilitySelfCheck {
    private ReliabilitySelfCheck() {
    }

    static void run() throws Exception {
        checkCompletionStates();
        checkQuotaFailureRollback();
        checkSourceBoundary();
        checkContextAndIncompleteFallback();
        checkSearchRetries();
        checkNetworkRetry();
        checkSearchDeadline();
        checkQuotaBetweenStages();
        checkPruning();
        checkShutdown();
    }

    private static void checkCompletionStates() throws Exception {
        for (String body : List.of(
                "{\"status\":\"incomplete\",\"output_text\":\"partial\"}",
                "{\"incomplete_details\":{\"reason\":\"max_output_tokens\"},\"output_text\":\"partial\"}")) {
            try {
                OpenRouterClient.parseReply("openai_responses", body);
                throw new AssertionError("Incomplete Responses text must fail");
            } catch (OpenRouterClient.ApiException expected) {
                require(expected.kind() == OpenRouterClient.FailureKind.INVALID_RESPONSE,
                        "incomplete Responses classification");
                require(expected.getMessage().contains(body.contains("max_output_tokens")
                        ? "max_output_tokens" : "incomplete"), "incomplete reason retained");
            }
        }
        try {
            OpenRouterClient.parseReply("anthropic_messages",
                    "{\"stop_reason\":\"max_tokens\",\"content\":[{\"type\":\"text\",\"text\":\"partial\"}]}");
            throw new AssertionError("Incomplete Anthropic text must fail");
        } catch (OpenRouterClient.ApiException expected) {
            require(expected.kind() == OpenRouterClient.FailureKind.INVALID_RESPONSE
                    && expected.getMessage().contains("max_tokens"), "Anthropic truncation classification");
        }
        require(OpenRouterClient.parseReply("openai_responses",
                "{\"status\":\"completed\",\"output_text\":\"done\"}").text().equals("done"),
                "completed Responses accepted");
        require(OpenRouterClient.parseReply("anthropic_messages",
                "{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":\"done\"}]}")
                .text().equals("done"), "completed Anthropic accepted");
    }

    private static void checkQuotaFailureRollback() throws Exception {
        Path root = Files.createTempDirectory("mc-ai-quota-failure-");
        Path parent = Files.createDirectory(root.resolve("state"));
        Path stateFile = parent.resolve("quota.json");
        DailyQuota quota = new DailyQuota(stateFile, 3, ZoneId.of("UTC"), Clock.systemUTC());
        Files.delete(parent);
        Files.writeString(parent, "block directory creation");
        expectQuotaWriteFailure(quota);
        Files.delete(parent);
        Files.createDirectory(parent);
        Files.createDirectory(stateFile);
        Files.writeString(stateFile.resolve("block-move"), "keep directory non-empty");
        expectQuotaWriteFailure(quota);
        require(Files.notExists(parent.resolve("quota.json.tmp")), "failed move cleans temporary file");
        Files.delete(stateFile.resolve("block-move"));
        Files.delete(stateFile);
        require(quota.tryAcquire() && quota.snapshot().used() == 1, "quota resumes after storage recovery");
        require(new DailyQuota(stateFile, 3, ZoneId.of("UTC"), Clock.systemUTC()).snapshot().used() == 1,
                "persisted quota matches committed memory");
    }

    private static void expectQuotaWriteFailure(DailyQuota quota) throws Exception {
        try {
            quota.tryAcquire();
            throw new AssertionError("Quota persistence failure expected");
        } catch (IOException expected) {
            require(quota.snapshot().used() == 0, "failed persistence does not consume quota");
        }
    }

    private static void checkSourceBoundary() throws Exception {
        var sources = SourceUrls.validated(List.of("https://example.com/a", "https://example.com/a",
                "javascript:alert(1)", "/relative", "https:///missing", "https://bad.example/%zz",
                "https://user:password@example.com", "http://example.org/b"));
        require(sources.equals(List.of(URI.create("https://example.com/a"), URI.create("http://example.org/b"))),
                "source URL validation and stable deduplication");
        require(AiCommands.sourceLinks(sources).toFlatList().stream()
                .filter(part -> part.getStyle().getClickEvent() instanceof ClickEvent.OpenUrl).count() == 2,
                "validated sources have click events");
        require(AiCommands.answerChunk("model says https://evil.example").toFlatList().stream()
                .noneMatch(part -> part.getStyle().getClickEvent() != null), "model URL remains plain text");
        var results = FirecrawlClient.parseResults("""
                {"success":true,"data":{"web":[
                {"url":"javascript:alert(1)","markdown":"bad"},
                {"url":"https://bad.example/%zz","markdown":"bad"},
                {"url":"https://empty.example","markdown":""},
                {"url":"https://example.com/a","markdown":"useful"}]}}
                """, 10);
        require(results.size() == 1 && results.getFirst().url().equals("https://example.com/a"),
                "invalid or empty Firecrawl sources do not enter grounding");
    }

    private static void checkContextAndIncompleteFallback() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (Mock mock = new Mock(exchange -> {
            JsonObject body = readJson(exchange);
            require(body.get("instructions").getAsString().contains("default to Minecraft Java Edition"),
                    "Minecraft disambiguation instruction reaches ordinary requests");
            var input = body.getAsJsonArray("input");
            require(input.get(input.size() - 1).getAsJsonObject().get("content").getAsString()
                    .equals("钻石的主要用途"), "original question preserved");
            if (calls.incrementAndGet() == 1) {
                respond(exchange, 200, "{\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"max_output_tokens\"},\"output_text\":\"partial\"}");
            } else {
                respond(exchange, 200, "{\"status\":\"completed\",\"model\":\"fallback\",\"output_text\":\"complete https://evil.example\"}");
            }
        })) {
            Path config = configuration(mock, 10, 5);
            try (AssistantService service = new AssistantService(config, Clock.systemUTC())) {
                var outcome = request(service, false, "钻石的主要用途");
                require(outcome.success() && outcome.text().startsWith("complete") && outcome.sources().isEmpty(),
                        "only complete fallback is delivered, without model-provided source buttons");
                require(calls.get() == 2 && service.status().contains("daily=2/10"), "single fallback attempt");
                require(audit(config).contains("\"status\":\"success\""), "complete answer audited as success");
            }
        }
    }

    private static void checkSearchRetries() throws Exception {
        for (int status : new int[]{503, 408, 401, 403, 429, 400}) {
            AtomicInteger searches = new AtomicInteger();
            AtomicInteger answers = new AtomicInteger();
            boolean retryable = status == 503 || status == 408;
            try (Mock mock = new Mock(exchange -> {
                JsonObject body = readJson(exchange);
                if (exchange.getRequestURI().getPath().equals("/v2/search")) {
                    int call = searches.incrementAndGet();
                    respond(exchange, call == 1 ? status : 200, call == 1 ? "{}" : searchBody());
                } else if (body.get("max_output_tokens").getAsInt() == 96) {
                    respond(exchange, 200, "{\"output_text\":\"Minecraft diamond uses\"}");
                } else {
                    answers.incrementAndGet();
                    respond(exchange, 200, "{\"output_text\":\"grounded answer\"}");
                }
            })) {
                Path config = configuration(mock, 10, 5);
                try (AssistantService service = new AssistantService(config, Clock.systemUTC())) {
                    var outcome = request(service, true, "钻石有什么用");
                    require(outcome.success() == retryable, "search retry policy for HTTP " + status);
                    require(searches.get() == (retryable ? 2 : 1) && answers.get() == (retryable ? 1 : 0),
                            "only transient Firecrawl failures retry once");
                }
            }
        }
        AtomicInteger searches = new AtomicInteger();
        try (Mock mock = new Mock(exchange -> {
            readJson(exchange);
            if (exchange.getRequestURI().getPath().equals("/v2/search")) {
                searches.incrementAndGet();
                respond(exchange, 503, "{}");
            } else {
                respond(exchange, 200, "{\"output_text\":\"Minecraft diamond uses\"}");
            }
        })) {
            Path config = configuration(mock, 10, 5);
            try (AssistantService service = new AssistantService(config, Clock.systemUTC())) {
                require(!request(service, true, "diamonds").success() && searches.get() == 2,
                        "search retries stop after one retry");
                require(audit(config).contains("retry_exhausted"), "retry exhaustion is auditable");
            }
        }
    }

    private static void checkNetworkRetry() throws Exception {
        int unusedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            unusedPort = socket.getLocalPort();
        }
        try (Mock mock = new Mock(exchange -> {
            readJson(exchange);
            respond(exchange, 200, "{\"output_text\":\"Minecraft diamond uses\"}");
        })) {
            Path directory = configuration(mock, 10, 5);
            Path file = directory.resolve("mc_ai_assistant.json");
            AssistantConfig config = AssistantConfig.load(file);
            config.firecrawlBaseUrl = "http://127.0.0.1:" + unusedPort + "/v2";
            config.save(file);
            try (AssistantService service = new AssistantService(directory, Clock.systemUTC())) {
                var outcome = request(service, true, "diamonds");
                require(!outcome.success() && outcome.failureKind() == OpenRouterClient.FailureKind.NETWORK,
                        "network failure classification");
                require(service.status().contains("daily=3/10"), "network failure has exactly two search attempts");
            }
        }
    }

    private static void checkSearchDeadline() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger completions = new AtomicInteger();
        try (Mock mock = new Mock(exchange -> {
            JsonObject body = readJson(exchange);
            calls.incrementAndGet();
            if (exchange.getRequestURI().getPath().equals("/v2/search")) {
                Thread.sleep(200);
                respond(exchange, 200, searchBody());
            } else if (body.get("max_output_tokens").getAsInt() == 96) {
                Thread.sleep(200);
                respond(exchange, 200, "{\"output_text\":\"Minecraft diamond uses\"}");
            } else {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write("{\"output_text\":\"".getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                Thread.sleep(1300);
                exchange.getResponseBody().write("late answer\"}".getBytes(StandardCharsets.UTF_8));
            }
        })) {
            Path directory = configuration(mock, 10, 1);
            try (AssistantService service = new AssistantService(directory, Clock.systemUTC())) {
                CompletableFuture<AssistantService.Outcome> completed = new CompletableFuture<>();
                long started = System.nanoTime();
                require(service.submit(player(true, "diamonds"), outcome -> {
                    completions.incrementAndGet();
                    completed.complete(outcome);
                }).accepted(), "deadline request accepted");
                var outcome = completed.get(3, TimeUnit.SECONDS);
                long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                require(!outcome.success() && outcome.failureKind() == OpenRouterClient.FailureKind.TIMEOUT,
                        "whole search deadline includes response body reads");
                require(elapsed < 1600 && calls.get() == 3, "stages share one total timeout budget");
                Thread.sleep(150);
                require(completions.get() == 1 && service.status().contains("active=0/2"),
                        "deadline releases once and suppresses late continuation");
                require(audit(directory).contains("DEADLINE"), "deadline is distinct in audit");
            }
        }
    }

    private static void checkQuotaBetweenStages() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (Mock mock = new Mock(exchange -> {
            readJson(exchange);
            calls.incrementAndGet();
            respond(exchange, 200, "{\"output_text\":\"Minecraft diamond uses\"}");
        })) {
            try (AssistantService service = new AssistantService(configuration(mock, 1, 5), Clock.systemUTC())) {
                var outcome = request(service, true, "diamonds");
                require(outcome.failureKind() == OpenRouterClient.FailureKind.DAILY_LIMIT && calls.get() == 1,
                        "quota stops search before an unpaid outbound attempt");
            }
        }
    }

    private static void checkPruning() throws Exception {
        try (Mock mock = new Mock(exchange -> {
            readJson(exchange);
            respond(exchange, 200, "{\"output_text\":\"done\"}");
        })) {
            Path directory = configuration(mock, 10, 5);
            Path file = directory.resolve("mc_ai_assistant.json");
            AssistantConfig config = AssistantConfig.load(file);
            config.playerCooldownSeconds = 5;
            config.historyIdleMinutes = 1;
            config.save(file);
            MutableClock clock = new MutableClock();
            try (AssistantService service = new AssistantService(directory, clock)) {
                request(service, false, "one");
                request(service, false, "two");
                require(service.status().contains("cooldowns=2, sessions=2"), "temporary player state tracked");
                clock.now = clock.now.plusSeconds(61);
                require(service.status().contains("cooldowns=0, sessions=0"), "idle players pruned without revisiting them");
            }
        }
    }

    private static void checkShutdown() throws Exception {
        for (int target : new int[]{0, 1, 2, 3}) {
            int stallAt = Math.max(1, target);
            AtomicInteger calls = new AtomicInteger();
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            try (Mock mock = new Mock(exchange -> {
                JsonObject body = readJson(exchange);
                if (calls.incrementAndGet() == stallAt) {
                    entered.countDown();
                    release.await(3, TimeUnit.SECONDS);
                }
                if (exchange.getRequestURI().getPath().equals("/v2/search")) {
                    respond(exchange, 200, searchBody());
                } else {
                    respond(exchange, 200, "{\"output_text\":\"Minecraft diamond uses\"}");
                }
            })) {
                AssistantService service = new AssistantService(configuration(mock, 10, 5), Clock.systemUTC());
                CompletableFuture<AssistantService.Outcome> completed = new CompletableFuture<>();
                require(service.submit(player(target != 0, "diamonds"), completed::complete).accepted(),
                        "shutdown request accepted");
                require(entered.await(3, TimeUnit.SECONDS), "shutdown reaches stage " + target);
                long started = System.nanoTime();
                service.close();
                require(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1000,
                        "shutdown cancellation is bounded");
                release.countDown();
                Thread.sleep(100);
                require(service.isClosed() && service.status().contains("active=0/2") && !completed.isDone(),
                        "shutdown suppresses completion and releases all requests");
                require(calls.get() == stallAt, "shutdown cannot advance to the next stage");
                service.setEnabled(true);
                require(!service.submit(player(false, "hello"), ignored -> {}).accepted(), "closed service cannot reopen");
                service.close();
            } finally {
                release.countDown();
            }
        }
    }

    private static AssistantService.Outcome request(AssistantService service, boolean search, String question)
            throws Exception {
        CompletableFuture<AssistantService.Outcome> result = new CompletableFuture<>();
        require(service.submit(player(search, question), result::complete).accepted(), "request accepted");
        return result.get(10, TimeUnit.SECONDS);
    }

    private static AssistantService.PlayerRequest player(boolean search, String question) {
        return new AssistantService.PlayerRequest(UUID.randomUUID(), "TestPlayer", question,
                search ? AssistantService.Retrieval.WEB : AssistantService.Retrieval.NONE, true);
    }

    private static Path configuration(Mock mock, int quota, int timeout) throws Exception {
        Path directory = Files.createTempDirectory("mc-ai-reliability-");
        Files.writeString(directory.resolve("model.secret"), "test-only-key");
        Files.writeString(directory.resolve("search.secret"), "test-only-search-key");
        AssistantConfig config = new AssistantConfig();
        config.baseUrl = mock.baseUrl() + "/api/v1";
        config.firecrawlBaseUrl = mock.baseUrl() + "/v2";
        config.secretFile = "model.secret";
        config.firecrawlSecretFile = "search.secret";
        config.playerCooldownSeconds = 0;
        config.dailyRequestLimit = quota;
        config.requestTimeoutSeconds = timeout;
        config.fullConversationLog = true;
        config.timezone = "UTC";
        config.save(directory.resolve("mc_ai_assistant.json"));
        return directory;
    }

    private static String audit(Path directory) throws IOException {
        try (var files = Files.walk(directory.resolve("mc_ai_assistant/logs"))) {
            Path file = files.filter(Files::isRegularFile).findFirst().orElseThrow();
            return Files.readString(file);
        }
    }

    private static String searchBody() {
        return "{\"success\":true,\"data\":{\"web\":[{\"title\":\"Diamond\",\"url\":\"https://example.com/diamond\",\"markdown\":\"source facts\"}]}}";
    }

    private static JsonObject readJson(HttpExchange exchange) throws IOException {
        return JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                .getAsJsonObject();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static void require(boolean value, String description) {
        if (!value) {
            throw new AssertionError(description);
        }
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws Exception;
    }

    private static final class Mock implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor = Executors.newCachedThreadPool(
                Thread.ofPlatform().daemon().name("mc-ai-test-", 0).factory());
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        Mock(Handler handler) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                try {
                    handler.handle(exchange);
                } catch (IOException | InterruptedException cancelled) {
                    // Clients deliberately cancel body reads and requests in these checks.
                } catch (Throwable exception) {
                    failure.compareAndSet(null, exception);
                } finally {
                    exchange.close();
                }
            });
            server.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
            if (failure.get() != null) {
                throw new AssertionError("Mock handler failed", failure.get());
            }
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-08-31T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
