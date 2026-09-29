package dev.mcai.assistant;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class ChatDefaultsWikiSelfCheck {
    static void run() throws Exception {
        publicPrivacyDefaults();
        preferencesPersistAndFailSafely();
        routingAndHistory();
        sourceValidation();
        fallbackAndCache();
        limitsAndFailures();
        cancellation();
        System.out.println("Chat defaults and Wiki fallback self-checks passed");
    }

    private static void publicPrivacyDefaults() throws Exception {
        try (Mock mock = new Mock()) {
            Path dir = config(mock, 100, 5, false);
            UUID player = UUID.randomUUID();
            try (var service = new AssistantService(dir, Clock.systemUTC())) {
                String notice = service.takePrivacyNotice(player);
                require(notice.contains("日志已关闭") && notice.contains("UUID") && notice.contains("127.0.0.1"),
                        "notice reflects configured destination, identifiers and logging state");
                require(!notice.contains("test-model-only") && !notice.contains("test-firecrawl-only"), "notice excludes credentials");
                require(service.takePrivacyNotice(player).isEmpty(), "unchanged notice is sent once per player per runtime");
                require(!service.takePrivacyNotice(UUID.randomUUID()).isEmpty(), "notice is not shared between players");
                require(request(service, new AssistantService.PlayerRequest(player, "Test", "PRIVATE_DEFAULT_TEST",
                        AssistantService.Retrieval.NONE, true)).success(), "default logging request succeeds");
                require(!Files.exists(dir.resolve("mc_ai_assistant/logs")), "default request creates no conversation audit file");
                var updated = AssistantConfig.load(dir.resolve("mc_ai_assistant.json"));
                updated.fullConversationLog = true;
                updated.save(dir.resolve("mc_ai_assistant.json"));
                service.reload();
                require(service.takePrivacyNotice(player).contains("日志已开启"), "changed policy prompts again after reload");
                require(request(service, new AssistantService.PlayerRequest(player, "Test", "EXPLICIT_AUDIT_TEST",
                        AssistantService.Retrieval.NONE, true)).success(), "explicit logging request succeeds");
                try (var logs = Files.list(dir.resolve("mc_ai_assistant/logs"))) {
                    String recorded = Files.readString(logs.findFirst().orElseThrow());
                    require(recorded.contains("EXPLICIT_AUDIT_TEST") && !recorded.contains("PRIVATE_DEFAULT_TEST"),
                            "logging opt-in persists only subsequent messages");
                }
            }
        }
    }

    private static void preferencesPersistAndFailSafely() throws Exception {
        Path file = Files.createTempDirectory("mc-ai-preferences-").resolve("preferences.json");
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        var store = new PlayerPreferences(file);
        require(store.get(a).equals(PlayerPreferences.Settings.DEFAULT), "new player defaults remain ordinary/public");
        store.set(a, new PlayerPreferences.Settings("mayor", true));
        store = new PlayerPreferences(file);
        require(store.get(a).privateReply() && store.get(a).personaId().equals("mayor"), "both preferences survive restart");
        require(store.get(b).equals(PlayerPreferences.Settings.DEFAULT), "other player has separate defaults");
        Path backup = file.resolveSibling("saved.json");
        Files.move(file, backup);
        Files.createDirectory(file);
        Files.writeString(file.resolve("sentinel"), "simulate failed replace");
        try {
            store.set(a, PlayerPreferences.Settings.DEFAULT);
            throw new AssertionError("save should fail");
        } catch (IOException expected) {
            require(store.get(a).privateReply(), "failed save must preserve private visibility in memory");
        }
        Files.delete(file.resolve("sentinel"));
        Files.delete(file);
        Files.move(backup, file);
        require(new PlayerPreferences(file).get(a).privateReply(), "failed save preserves disk state");
        store.set(a, PlayerPreferences.Settings.DEFAULT);
        require(new PlayerPreferences(file).get(a).equals(PlayerPreferences.Settings.DEFAULT), "reset is persistent");
        Files.writeString(file, "{broken");
        try {
            new PlayerPreferences(file);
            throw new AssertionError("corrupt visibility file must not silently become public");
        } catch (IOException expected) { }
    }

    private static void routingAndHistory() throws Exception {
        try (Mock mock = new Mock()) {
            Path dir = config(mock, 100, 5, true);
            Path role = dir.resolve("mc_ai_assistant/personas/mayor.json");
            Files.createDirectories(role.getParent());
            Files.copy(Path.of("docs/examples/mayor.json"), role);
            UUID player = UUID.randomUUID();
            try (var service = new AssistantService(dir, Clock.systemUTC())) {
                require(service.setDefaultPersona(player, "mayor").accepted(), "available role can be selected");
                require(service.setDefaultVisibility(player, true).accepted(), "private default can be selected");
                require(mock.models.size() == 0 && service.status().contains("daily=0/100"), "settings have no model/quota side effects");
                var inherited = service.resolveRequest(player, "Test", "hello", AssistantService.Retrieval.NONE, null, null);
                require(inherited.personaId().equals("mayor") && inherited.privateReply(), "plain command inherits both defaults");
                var onePublic = service.resolveRequest(player, "Test", "hello", AssistantService.Retrieval.NONE, false, null);
                require(!onePublic.privateReply() && onePublic.personaId().equals("mayor"), "one-off visibility overrides only visibility");
                var plain = service.resolveRequest(player, "Test", "hello", AssistantService.Retrieval.NONE, null, "");
                require(plain.personaId().isEmpty() && plain.privateReply(), "plain override retains private default");
                for (var mode : List.of(AssistantService.Retrieval.WIKI, AssistantService.Retrieval.WEB)) {
                    var lookup = service.resolveRequest(player, "Test", "hello", mode, null, null);
                    require(lookup.personaId().isEmpty() && lookup.privateReply(), "retrieval bypasses role but honors privacy");
                }
                require(!service.setDefaultPersona(player, "missing").accepted()
                        && service.preferences(player).personaId().equals("mayor"), "invalid role does not replace saved selection");
                require(service.setDefaultPersona(player, "").accepted() && service.preferences(player).privateReply(),
                        "clear role preserves private default");
                request(service, service.resolveRequest(player, "Test", "PRIVATE_ONLY_829", AssistantService.Retrieval.NONE, null, null));
                request(service, service.resolveRequest(player, "Test", "PUBLIC_ONLY_192", AssistantService.Retrieval.NONE, false, null));
                require(!mock.models.getLast().toString().contains("PRIVATE_ONLY_829"), "public ordinary prompt cannot contain private history");
                request(service, service.resolveRequest(player, "Test", "private followup", AssistantService.Retrieval.NONE, null, null));
                String privatePrompt = mock.models.getLast().toString();
                require(privatePrompt.contains("PRIVATE_ONLY_829") && !privatePrompt.contains("PUBLIC_ONLY_192"), "private continuity remains separate");
                service.clear(player);
                require(service.preferences(player).privateReply(), "clearing conversation does not reset preferences");
                var dispatcher = new com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack>();
                dispatcher.register(AiCommands.commandTree(service));
                for (String command : List.of("ai settings", "ai settings role mayor", "ai settings ordinary", "ai settings private",
                        "ai settings public", "ai settings reset", "ai hello world", "ai plain hello world", "ai public hello",
                        "ai private hello", "ai public wiki diamonds", "ai private search diamonds", "ai persona mayor hi",
                        "ai public persona mayor hi", "ai private plain hello")) {
                    var parsed = dispatcher.parse(command, null);
                    require(parsed.getExceptions().isEmpty() && !parsed.getReader().canRead()
                            && parsed.getContext().getCommand() != null, "production command parses: " + command);
                }
                service.setDefaultPersona(player, "mayor");
            }
            Files.delete(role);
            try (var restored = new AssistantService(dir, Clock.systemUTC())) {
                var req = restored.resolveRequest(player, "Test", "hello", AssistantService.Retrieval.NONE, null, null);
                require(req.privateReply() && req.personaId().equals("mayor"), "service restart preserves unavailable preferred role");
                require(!restored.submit(req, ignored -> { }).accepted(), "removed role rejects instead of silently changing style");
                require(restored.resetPreferences(player).accepted(), "reset available after role removal");
            }
        }
    }

    private static void sourceValidation() throws Exception {
        for (String invalid : List.of("https://evil.example/w/Diamond", "https://minecraft.wiki.evil.example/w/Diamond",
                "http://minecraft.wiki/w/Diamond", "https://user@minecraft.wiki/w/Diamond", "https://minecraft.wiki:8443/w/Diamond",
                "https://minecraft.wiki/w/Talk%3ADiamond", "https://minecraft.wiki/w/Diamond?redirect=evil", "https://minecraft.wiki/api.php")) {
            require(WikiWebFallback.articleUri(invalid) == null, "reject unsafe/non-article URL");
        }
        JsonObject response = fallbackBody();
        JsonArray web = response.getAsJsonObject("data").getAsJsonArray("web");
        var original = web.get(0).getAsJsonObject();
        var offsite = original.deepCopy(); offsite.addProperty("url", "https://evil.example/w/Diamond"); web.add(offsite);
        var snippet = original.deepCopy(); snippet.addProperty("url", "https://minecraft.wiki/w/Map");
        snippet.remove("markdown"); snippet.addProperty("description", "snippet only"); web.add(snippet);
        var blocked = original.deepCopy(); blocked.addProperty("markdown", "# Please wait\nVerify you are human"); web.add(blocked);
        var redirected = original.deepCopy(); var metadata = new JsonObject(); metadata.addProperty("url", "https://evil.example/w/Diamond");
        redirected.add("metadata", metadata); web.add(redirected);
        var pages = WikiWebFallback.parse(response.toString(), System.currentTimeMillis());
        require(pages.size() == 1 && pages.getFirst().sourceKind().equals("firecrawl"), "accept only validated full article body");
        require(pages.getFirst().revisionId() == 0 && !pages.getFirst().permalink().toString().contains("oldid"), "fallback must not fabricate revisions");
        require(!pages.getFirst().text().contains("NAVIGATION") && pages.getFirst().text().contains("== Usage =="), "drop navigation, preserve sections");
        require(!WikiWebFallback.prose("# Diamond\n```json\nFAKE_STRUCTURED\n```\n| FAKE_TABLE |\nA diamond is a mineral.", "Diamond")
                .contains("FAKE_"), "do not treat flattened widgets or tables as prose evidence");
        JsonObject redirect = scrapeBody("https://minecraft.wiki/w/Witch_Hut", "https://minecraft.wiki/w/Swamp_Hut",
                "# Swamp Hut\n## Generation\nSwamp huts generate in swamps.");
        var requested = java.net.URI.create("https://minecraft.wiki/w/Witch_Hut");
        var redirectedPages = WikiWebFallback.parseScrape(redirect.toString(), requested, System.currentTimeMillis());
        require(redirectedPages.size() == 1 && redirectedPages.getFirst().matchesTopic("Witch Hut"), "verified final URL yields whole-page alias");
        var cache = new WikiCache(Files.createTempDirectory("mc-ai-redirect-").resolve("cache.json"), "https://minecraft.wiki/api.php", Clock.systemUTC());
        cache.putAll(redirectedPages);
        var query = new WikiQuery("Witch Hut", "find locate", "java", "26.2", false);
        require(cache.hasTopic(query) && cache.rank(query).chunks().getFirst().heading().equals("Generation"),
                "verified alias recovers generation section and exact-topic cache");
        redirect.getAsJsonObject("data").getAsJsonObject("metadata").addProperty("url", "https://evil.example/w/Swamp_Hut");
        require(WikiWebFallback.parseScrape(redirect.toString(), requested, 1).isEmpty(), "cross-site redirect cannot establish alias");
    }

    private static void fallbackAndCache() throws Exception {
        try (Mock mock = new Mock()) {
            Path dir = config(mock, 100, 5, true);
            try (var service = new AssistantService(dir, Clock.systemUTC())) {
                var first = request(service, wiki());
                require(first.success() && first.sources().equals(List.of(java.net.URI.create("https://minecraft.wiki/w/Diamond"))), "blocked Wiki uses restricted fallback");
                require(mock.wiki.get() == 1 && mock.fallback.get() == 1 && service.status().contains("daily=4/100"), "rewrite/API/fallback/answer all charged");
                require(mock.models.getLast().toString().contains("Diamonds are used for crafting")
                        && mock.models.getLast().toString().contains("unavailable (current page)"), "answer grounded in fallback body with honest provenance");
            }
            try (var restored = new AssistantService(dir, Clock.systemUTC())) {
                require(request(restored, wiki()).success(), "cached fallback survives service restart");
                require(mock.wiki.get() == 1 && mock.fallback.get() == 1 && restored.status().contains("daily=6/100"), "fresh exact-topic cache avoids both remote retrieval routes");
            }
        }
    }

    private static void limitsAndFailures() throws Exception {
        for (int quota : List.of(2, 3)) {
            try (Mock mock = new Mock(); var service = new AssistantService(config(mock, quota, 5, true), Clock.systemUTC())) {
                require(request(service, wiki()).failureKind() == OpenRouterClient.FailureKind.DAILY_LIMIT, "fallback respects remaining budget");
                require(mock.fallback.get() == (quota == 3 ? 1 : 0) && mock.models.size() == 1, "no answer beyond quota");
            }
        }
        for (int status : List.of(401, 429)) {
            try (Mock mock = new Mock()) {
                mock.wikiStatus = status;
                try (var service = new AssistantService(config(mock, 100, 5, true), Clock.systemUTC())) {
                    require(!request(service, wiki()).success() && mock.fallback.get() == 0, "authentication/rate limit must not trigger fallback");
                }
            }
        }
        try (Mock mock = new Mock(); var service = new AssistantService(config(mock, 100, 5, false), Clock.systemUTC())) {
            require(request(service, wiki()).failureKind() == OpenRouterClient.FailureKind.ACCESS_BLOCKED
                    && mock.fallback.get() == 0, "fallback is opt-in");
        }
        try (Mock mock = new Mock()) {
            mock.empty = true;
            try (var service = new AssistantService(config(mock, 100, 5, true), Clock.systemUTC())) {
                require(request(service, wiki()).failureKind() == OpenRouterClient.FailureKind.NO_KNOWLEDGE
                        && mock.models.size() == 1, "no answer when fallback has no article body");
            }
        }
        try (Mock mock = new Mock()) {
            mock.scrapeEmpty = true;
            try (var service = new AssistantService(config(mock, 100, 5, true), Clock.systemUTC())) {
                require(request(service, wiki()).success() && mock.fallback.get() == 2
                        && service.status().contains("daily=5/100"), "empty direct title fetch can use one domain-limited search");
            }
        }
        try (Mock mock = new Mock()) {
            mock.failOnce = true;
            try (var service = new AssistantService(config(mock, 100, 5, true), Clock.systemUTC())) {
                require(request(service, wiki()).success() && mock.fallback.get() == 2
                        && service.status().contains("daily=5/100"), "one bounded fallback retry is charged");
            }
        }
        try (Mock mock = new Mock()) {
            mock.stall = true;
            try (var service = new AssistantService(config(mock, 100, 1, true), Clock.systemUTC())) {
                long started = System.nanoTime();
                require(request(service, wiki()).failureKind() == OpenRouterClient.FailureKind.TIMEOUT
                        && TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2500
                        && mock.models.size() == 1, "fallback uses original total deadline and does not answer after expiry");
            }
        }
    }

    private static void cancellation() throws Exception {
        try (Mock mock = new Mock()) {
            mock.stall = true;
            var service = new AssistantService(config(mock, 100, 5, true), Clock.systemUTC());
            AtomicInteger delivered = new AtomicInteger();
            require(service.submit(wiki(), ignored -> delivered.incrementAndGet()).accepted(), "cancel test accepted");
            require(mock.entered.await(3, TimeUnit.SECONDS), "fallback started");
            service.close();
            mock.release.countDown();
            require(mock.completed.await(3, TimeUnit.SECONDS), "mock fallback returned after cancellation");
            require(delivered.get() == 0 && mock.models.size() == 1, "stopped service cannot continue fallback to model");
        }
    }

    private static AssistantService.PlayerRequest wiki() {
        return new AssistantService.PlayerRequest(UUID.randomUUID(), "Test", "钻石有什么用", AssistantService.Retrieval.WIKI, true);
    }

    private static AssistantService.Outcome request(AssistantService service, AssistantService.PlayerRequest request) throws Exception {
        var result = new CompletableFuture<AssistantService.Outcome>();
        require(service.submit(request, result::complete).accepted(), "request accepted");
        return result.get(8, TimeUnit.SECONDS);
    }

    private static Path config(Mock mock, int quota, int timeout, boolean fallback) throws Exception {
        Path dir = Files.createTempDirectory("mc-ai-defaults-wiki-");
        Files.writeString(dir.resolve("model.secret"), "test-model-only");
        Files.writeString(dir.resolve("firecrawl.secret"), "test-firecrawl-only");
        var config = new AssistantConfig();
        config.secretFile = "model.secret";
        config.firecrawlSecretFile = "firecrawl.secret";
        config.baseUrl = mock.base() + "/api/v1";
        config.firecrawlBaseUrl = mock.base() + "/v2";
        config.wikiApiUrl = mock.base() + "/api.php";
        config.wikiFirecrawlFallback = fallback;
        config.dailyRequestLimit = quota;
        config.requestTimeoutSeconds = timeout;
        config.playerCooldownSeconds = 0;
        config.save(dir.resolve("mc_ai_assistant.json"));
        return dir;
    }

    private static JsonObject fallbackBody() {
        JsonObject item = new JsonObject();
        item.addProperty("url", "https://minecraft.wiki/w/Diamond");
        item.addProperty("markdown", "NAVIGATION\n# Diamond  Share article feedback\nDiamonds are a mineral.\n## Contents\nNAVIGATION\n## Usage\nDiamonds are used for crafting tools and armor.");
        JsonArray web = new JsonArray(); web.add(item);
        JsonObject data = new JsonObject(); data.add("web", web);
        JsonObject root = new JsonObject(); root.addProperty("success", true); root.add("data", data);
        return root;
    }

    private static JsonObject scrapeBody(String requested, String target, String markdown) {
        JsonObject metadata = new JsonObject();
        metadata.addProperty("sourceURL", requested); metadata.addProperty("url", target); metadata.addProperty("statusCode", 200);
        JsonObject data = new JsonObject(); data.addProperty("markdown", markdown); data.add("metadata", metadata);
        JsonObject root = new JsonObject(); root.addProperty("success", true); root.add("data", data);
        return root;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Mock implements AutoCloseable {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final java.util.concurrent.ExecutorService executor = Executors.newCachedThreadPool();
        final List<JsonObject> models = new CopyOnWriteArrayList<>();
        final AtomicInteger wiki = new AtomicInteger(), fallback = new AtomicInteger();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), completed = new CountDownLatch(1);
        int wikiStatus = 403;
        boolean empty, scrapeEmpty, failOnce, stall;

        Mock() throws IOException {
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                try {
                    int status = 200;
                    String body;
                    String path = exchange.getRequestURI().getPath();
                    if (path.equals("/api.php")) {
                        require(exchange.getRequestHeaders().getFirst("Authorization") == null, "no credentials sent to Wiki");
                        wiki.incrementAndGet(); status = wikiStatus; body = "blocked";
                    } else {
                        var input = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                        if (path.equals("/v2/search") || path.equals("/v2/scrape")) {
                            int count = fallback.incrementAndGet();
                            require("Bearer test-firecrawl-only".equals(exchange.getRequestHeaders().getFirst("Authorization")), "dedicated fallback key");
                            if (path.equals("/v2/search")) {
                                require(input.getAsJsonArray("includeDomains").toString().equals("[\"minecraft.wiki\"]")
                                        && input.get("limit").getAsInt() == 3, "fallback scope and bounded result count");
                            } else require(input.get("url").getAsString().equals("https://minecraft.wiki/w/Diamond"), "fallback direct title URL restricted");
                            entered.countDown();
                            if (stall) release.await(4, TimeUnit.SECONDS);
                            body = path.equals("/v2/scrape") ? scrapeBody("https://minecraft.wiki/w/Diamond",
                                    "https://minecraft.wiki/w/Diamond", empty || scrapeEmpty ? "" : "# Diamond\n## Usage\nDiamonds are used for crafting tools and armor.").toString()
                                    : empty ? "{\"success\":true,\"data\":{\"web\":[]}}" : fallbackBody().toString();
                            if (failOnce && count == 1) status = 500;
                        } else {
                            require(path.equals("/api/v1/responses"), "only expected endpoints");
                            require("Bearer test-model-only".equals(exchange.getRequestHeaders().getFirst("Authorization")), "dedicated model key");
                            models.add(input);
                            var reply = new JsonObject();
                            reply.addProperty("output_text", input.get("max_output_tokens").getAsInt() == 128
                                    ? "{\"topic\":\"Diamond\",\"keywords\":\"diamond usage crafting\"}" : "回答。[1]");
                            body = reply.toString();
                        }
                    }
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(status, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch (IOException | InterruptedException cancelled) {
                    // Cancellation tests close the transport before the response is written.
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    if (exchange.getRequestURI().getPath().startsWith("/v2/")) completed.countDown();
                    exchange.close();
                }
            });
            server.start();
        }

        String base() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

        public void close() {
            release.countDown(); server.stop(0); executor.shutdownNow();
            if (failure.get() != null) throw new AssertionError("mock failed", failure.get());
        }
    }
}
