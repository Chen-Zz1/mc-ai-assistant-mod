package dev.mcai.assistant;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class WikiSelfCheck {
    private static final String API = "https://minecraft.wiki/api.php";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-31T00:00:00Z"), ZoneOffset.UTC);
    private static final String EXTRACT = "Diamond is a rare item.\n== Obtaining ==\n"
            + "=== Mining ===\nDiamond ore can be mined.\n== Usage ==\n"
            + "Diamonds are used for crafting tools and armor.\n=== Trading ===\n"
            + "Diamonds can be traded with villagers.\n== History ==\nHistorical diamond facts.";

    static void run() throws Exception {
        checkQueryAndCache();
        checkParsing();
        checkAliasesAndDiagnostics();
        checkConfiguration();
        checkPipeline();
        checkFailureAndQuota();
        checkRewriteCorrection();
        checkRewriteFailureBoundaries();
        checkRewriteCancellation();
        checkCancellation();
        System.out.println("Wiki offline self-checks passed");
    }

    // Deliberately separate from the default offline suite: public Wiki only, no model or secrets.
    public static void main(String[] args) throws Exception {
        try (MediaWikiClient client = new MediaWikiClient()) {
            var hits = client.search(API, "Diamond", Duration.ofSeconds(20)).get(25, TimeUnit.SECONDS);
            var hit = hits.stream().filter(item -> item.title().equalsIgnoreCase("Diamond")).findFirst().orElseThrow();
            var page = client.fetch(API, hit.pageId(), System.currentTimeMillis(), Duration.ofSeconds(20))
                    .get(25, TimeUnit.SECONDS);
            require(page != null, "live page has usable prose");
            Path file = Files.createTempDirectory("mc-ai-wiki-live-").resolve("cache.json");
            WikiCache cache = new WikiCache(file, API, Clock.systemUTC());
            cache.putAll(List.of(page));
            var chunks = new WikiCache(file, API, Clock.systemUTC()).search(query("Diamond", "diamond usage crafting"));
            require(!chunks.isEmpty(), "live extract can be persisted and retrieved");
            System.out.println("Wiki live smoke passed: title=" + page.title() + ", revision=" + page.revisionId()
                    + ", chunks=" + chunks.size() + ", source=" + page.permalink());
        }
    }

    private static void checkQueryAndCache() throws Exception {
        WikiQuery query = WikiQuery.parse("```json\n{\"topic\":\"Diamond\",\"keywords\":\"diamond usage crafting\"}\n```",
                "Java 26.2 钻石有什么用？");
        require(query.topic().equals("Diamond") && query.edition().equals("java") && query.version().equals("26.2"),
                "rewrite keeps canonical topic separate from intent and explicit version");
        String valid = "{\"topic\":\"Diamond\",\"keywords\":\"diamond crafting\"}";
        require(WikiQuery.parse(valid, "基岩版 1.21 钻石").edition().equals("bedrock"), "edition from original");
        require(WikiQuery.parse(valid, "比较 Java 1.21 与 Bedrock 26.2 版本").version().isEmpty(), "multi-version query");
        for (String invalid : List.of("invalid", "{}", "[]", "null",
                "{\"topic\":true,\"keywords\":\"diamond\"}",
                "{\"topic\":\"Diamond\",\"keywords\":12}",
                "{\"topic\":\"Diamond\",\"keywords\":null}",
                "{\"topic\":\"\",\"keywords\":\"diamond\"}",
                "{\"topic\":\"女巫小屋\",\"keywords\":\"女巫小屋 寻找 沼泽\"}",
                "{\"topic\":\"Diamond\",\"keywords\":\"diamond 合成\"}",
                "{\"topic\":\"Diamond\\u202e\",\"keywords\":\"diamond\"}",
                "{\"topic\":\"Diamond\\nore\",\"keywords\":\"diamond\"}",
                "{\"topic\":\"" + "a".repeat(151) + "\",\"keywords\":\"diamond\"}")) {
            try {
                WikiQuery.parse(invalid, "钻石有什么用？");
                throw new AssertionError("invalid rewrite reached retrieval: " + invalid);
            } catch (WikiQuery.InvalidRewrite expected) {
                // No literal-query fallback may bypass the English Wiki lookup contract.
            }
        }
        require(WikiQuery.terms("uses recipe mining").equals(WikiQuery.terms("usage crafting mined")), "intent synonyms");
        Path file = Files.createTempDirectory("mc-ai-wiki-cache-").resolve("cache.json");
        WikiCache cache = new WikiCache(file, API, CLOCK);
        cache.putAll(List.of(page(1, "Diamond", EXTRACT)));
        require(cache.hasTopic(query), "exact article coverage");
        var chunks = cache.search(query);
        require(chunks.getFirst().heading().equals("Usage"), "intent section outranks mining");
        require(chunks.stream().noneMatch(chunk -> chunk.heading().contains("History")), "history excluded by default");
        require(WikiCache.chunks(page(1, "Diamond", EXTRACT)).stream()
                .anyMatch(chunk -> chunk.heading().equals("Obtaining > Mining")), "nested heading path retained");
        var grounding = WikiCache.grounding(chunks);
        require(grounding.sources().size() == 1 && grounding.text().contains("Revision: 101")
                && grounding.sources().getFirst().toString().endsWith("?oldid=101"), "deduplicated revision citations");
        require(!cache.hasTopic(query("Villager", "diamond trading")), "incidental terms are not cache coverage");
        String stored = Files.readString(file);
        require(stored.contains("CC BY-NC-SA") && stored.contains(WikiCache.LICENSE_URL)
                && !stored.contains("钻石有什么用"), "cache stores attributed knowledge, not questions");
        require(new WikiCache(file, API, CLOCK).search(query).equals(chunks), "cache survives restart");
        require(new WikiCache(file, "https://elsewhere.example/api.php", CLOCK).size() == 0, "origin mismatch ignored");
        require(new WikiCache(file, API, Clock.offset(CLOCK, WikiCache.TTL)).size() == 0, "TTL boundary expires");
        require(!WikiCache.compatible(query, "Java Edition 1.21", "")
                && !WikiCache.compatible(query, "Diamond", "Bedrock Edition"), "explicit incompatible scope filtered");
        require(WikiCache.compatible(query, "Diamond", "Usage"), "generic current-page prose remains eligible");
        var unicode = WikiCache.chunks(page(2, "Diamond", "😀".repeat(2100)));
        require(unicode.size() == 3 && unicode.stream().allMatch(chunk ->
                chunk.body().codePointCount(0, chunk.body().length()) <= 1000
                        && !Character.isHighSurrogate(chunk.body().charAt(chunk.body().length() - 1))), "Unicode-safe chunks");
        List<MediaWikiClient.Page> many = new ArrayList<>();
        for (int index = 1; index <= 129; index++) {
            many.add(page(index, "Diamond " + index, "Diamond usage"));
        }
        cache.putAll(many);
        require(cache.size() == 128 && cache.page(1) == null && cache.page(129) != null, "bounded deterministic eviction");
        Files.writeString(file, "broken JSON");
        require(new WikiCache(file, API, CLOCK).size() == 0, "corrupt disposable cache ignored");
        Path blocked = Files.createDirectory(file.resolveSibling("blocked"));
        Files.writeString(blocked.resolve("keep"), "keep");
        WikiCache memoryOnly = new WikiCache(blocked, API, CLOCK);
        try {
            memoryOnly.putAll(List.of(page(1, "Diamond", EXTRACT)));
            throw new AssertionError("nonempty directory cannot be replaced by cache");
        } catch (IOException expected) {
            require(memoryOnly.hasTopic(query), "disk failure retains fresh memory evidence");
        }
        Path repeatedFile = file.resolveSibling("repeated.json");
        WikiCache repeated = new WikiCache(repeatedFile, API, CLOCK);
        repeated.putAll(List.of(page(1, "Diamond", "== Usage ==\ndiamond usage"),
                page(2, "Diamond", "== Usage ==\n" + "diamond usage ".repeat(20))));
        var scores = repeated.search(query("Diamond", "diamond usage"));
        require(scores.size() == 2 && scores.get(0).score() == scores.get(1).score(), "term repetition does not inflate score");
    }

    private static void checkParsing() {
        JsonObject root = JsonParser.parseString("{\"query\":{\"search\":["
                + "{\"pageid\":1,\"ns\":0,\"title\":\"Diamond\"},{\"pageid\":1,\"ns\":0,\"title\":\"Duplicate\"},"
                + "{\"pageid\":2,\"ns\":1,\"title\":\"Talk\"},{\"pageid\":3,\"ns\":0,\"title\":\"Ore\"},"
                + "{\"pageid\":4,\"ns\":0,\"title\":\"Block\"},{\"pageid\":5,\"ns\":0,\"title\":\"Excess\"}]}}")
                .getAsJsonObject();
        var hits = MediaWikiClient.parseSearch(root);
        require(hits.size() == 3 && hits.get(1).pageId() == 3, "namespace, duplicate and result cap");
        var parsed = MediaWikiClient.parsePage(pageBody("https://minecraft.wiki", "😀".repeat(32_001)), API, CLOCK.millis());
        require(parsed != null && parsed.text().codePointCount(0, parsed.text().length()) == 32_000, "bounded page extract");
        require(MediaWikiClient.parsePage(pageBody("https://evil.example", EXTRACT), API, CLOCK.millis()) == null,
                "foreign source rejected");
        require(MediaWikiClient.parsePage(pageBody("https://minecraft.wiki", ""), API, CLOCK.millis()) == null,
                "empty extract rejected");
        JsonObject missing = pageBody("https://minecraft.wiki", EXTRACT);
        missing.getAsJsonObject("query").getAsJsonArray("pages").get(0).getAsJsonObject().addProperty("missing", true);
        require(MediaWikiClient.parsePage(missing, API, CLOCK.millis()) == null, "missing page rejected");
    }

    private static void checkAliasesAndDiagnostics() throws Exception {
        JsonObject root = JsonParser.parseString("""
                {"query":{"redirects":[{"from":"Witch Hut","to":"Swamp Hut"}],
                "pages":[{"pageid":94079,"ns":0,"title":"Swamp Hut"}],
                "search":[{"pageid":94079,"ns":0,"title":"Swamp Hut"},
                {"pageid":2,"ns":0,"title":"Other"}]}}
                """).getAsJsonObject();
        var hits = MediaWikiClient.parseSearch(root, "Witch Hut");
        require(hits.size() == 2 && hits.getFirst().resolvedAlias().equals("Witch Hut"),
                "API-confirmed redirect is prioritized and deduplicated within search budget");
        require(MediaWikiClient.parseSearch(root, "Unrelated").stream()
                .allMatch(hit -> hit.resolvedAlias().isBlank()), "unrelated mapping cannot forge an alias");
        var redirect = root.getAsJsonObject("query").getAsJsonArray("redirects").get(0).getAsJsonObject();
        redirect.addProperty("tofragment", "Section");
        require(MediaWikiClient.parseSearch(root, "Witch Hut").getFirst().resolvedAlias().isBlank(),
                "section redirect cannot give whole-page entity coverage");
        redirect.remove("tofragment");
        require(MediaWikiClient.parseSearch(root, "Witch Hut|Other").getFirst().resolvedAlias().isBlank(),
                "multi-title input cannot acquire exact coverage");

        Path directory = Files.createTempDirectory("mc-ai-wiki-alias-");
        WikiCache cache = new WikiCache(directory.resolve("cache.json"), API, CLOCK);
        var swamp = new MediaWikiClient.Page(94079, "Swamp Hut", "https://minecraft.wiki/w/Swamp_Hut",
                3754145, CLOCK.millis(), "== Generation ==\nSwamp huts generate rarely in swamp biomes.");
        var query = query("Witch Hut", "witch hut generation swamp locating");
        cache.putAll(List.of(swamp));
        require(cache.search(query).isEmpty() && cache.rank(query).failureReason().equals("TOPIC_MISMATCH"),
                "baseline alias mismatch is reproducible without claiming historical rewrite content");
        cache.putAll(List.of(swamp.withAlias(hits.getFirst().resolvedAlias())));
        require(cache.hasTopic(query) && cache.search(query).getFirst().heading().equals("Generation"),
                "verified page alias makes relevant generation section eligible");
        require(new WikiCache(directory.resolve("cache.json"), API, CLOCK).hasTopic(query),
                "verified aliases persist with bounded fresh page cache");
        require(!new WikiCache(directory.resolve("cache.json"), API, Clock.offset(CLOCK, WikiCache.TTL)).hasTopic(query),
                "alias cannot outlive its source page TTL");
        require(!MediaWikiClient.validPage(swamp.withAlias("x".repeat(151)), API), "alias size validated on cache load");
        var realistic = new MediaWikiClient.Page(94079, "Swamp Hut", "https://minecraft.wiki/w/Swamp_Hut",
                3754145, CLOCK.millis(), "Swamp huts are also known as witch huts.\n== Generation ==\n"
                + "Swamp huts generate rarely in swamp biomes.\n== Mobs ==\nWitches spawn in the hut.\n"
                + "== Issues ==\nIssues relating to Witch hut are tracked elsewhere.\n"
                + "== External links ==\nWitch hut references.").withAlias("Witch Hut");
        cache.putAll(List.of(realistic));
        var actualRewrite = cache.search(query("Witch Hut", "witch hut find locate spawn"));
        require(actualRewrite.getFirst().heading().equals("Generation")
                && actualRewrite.stream().noneMatch(chunk -> chunk.heading().equals("Issues")
                    || chunk.heading().equals("External links")),
                "observed live locate rewrite prioritizes generation and excludes housekeeping sections");
        var ranking = cache.rank(query);
        WikiDiagnostics diagnostics = new WikiDiagnostics();
        diagnostics.query(query);
        diagnostics.hits(hits);
        diagnostics.stage("rank");
        diagnostics.ranking(ranking, true);
        diagnostics.finish("success", 2);
        String summary = diagnostics.summary();
        require(!summary.contains("Witch") && !summary.contains("Swamp") && !summary.contains("Generation")
                && summary.contains("outboundAttempts"), "ordinary diagnostics have counts and timings, not private content");
        require(diagnostics.snapshot(true).toString().contains("3754145"), "protected details retain revision evidence");
        Path disabled = directory.resolve("disabled");
        AuditLog off = new AuditLog(disabled, ZoneOffset.UTC, 30, false, CLOCK);
        off.append(UUID.randomUUID(), "test", "private", "answer", true, true, "model", "success", 1,
                "wiki", diagnostics.snapshot(true), null, null);
        require(Files.notExists(disabled), "disabled full audit also disables detailed Wiki diagnostics");
        Path enabled = directory.resolve("enabled");
        AuditLog on = new AuditLog(enabled, ZoneOffset.UTC, 30, true, CLOCK);
        Files.createDirectories(enabled);
        Path expired = enabled.resolve("conversations-2026-01-01.jsonl");
        Files.writeString(expired, "old private diagnostic");
        Files.setLastModifiedTime(expired, java.nio.file.attribute.FileTime.from(CLOCK.instant().minus(Duration.ofDays(31))));
        on.append(UUID.randomUUID(), "test", "private", "answer", true, true, "model", "success", 1,
                "wiki", diagnostics.snapshot(true), null, null);
        require(Files.notExists(expired), "diagnostic details share audit retention cleanup");
        String stored = Files.readString(enabled.resolve("conversations-2026-08-31.jsonl"));
        require(stored.contains(diagnostics.requestId) && stored.contains("Witch Hut"), "audit correlates protected details");
    }

    private static void checkPipeline() throws Exception {
        try (Mock mock = new Mock()) {
            Path directory = configuration(mock, 50, 5);
            try (AssistantService service = new AssistantService(directory, CLOCK)) {
                var outcome = request(service);
                require(outcome.success() && outcome.text().contains("CC BY-NC-SA 3.0")
                        && outcome.sources().size() == 1 && outcome.sources().getFirst().toString().endsWith("oldid=101"),
                        "Wiki answer has server-controlled attribution and sources");
                require(mock.models.get() == 2 && mock.searches.get() == 1 && mock.fetches.get() == 1,
                        "cold pipeline rewrite -> search -> page -> answer");
                require(service.status().contains("daily=4/50"), "every actual outbound attempt counted");
                require(request(service).success() && mock.models.get() == 4 && mock.searches.get() == 1,
                        "warm cache uses only two model calls");
                require(service.status().contains("daily=6/50"), "cache hit avoids Wiki quota charge");
            }
            try (AssistantService service = new AssistantService(directory, CLOCK)) {
                require(request(service).success() && mock.searches.get() == 1, "persisted cache reused after restart");
                mock.topic = "Villager";
                require(!request(service).success() && mock.searches.get() == 2, "new topic searches despite cached incidental text");
                mock.topic = "Diamond";
            }
            try (AssistantService service = new AssistantService(directory, Clock.offset(CLOCK, WikiCache.TTL))) {
                require(request(service).success() && mock.fetches.get() == 2, "expired page refreshed");
            }
            try (var logs = Files.list(directory.resolve("mc_ai_assistant/logs"))) {
                require(Files.readString(logs.findFirst().orElseThrow()).contains("\"retrieval\":\"wiki\""), "audit labels Wiki backend");
            }
        }
        try (Mock mock = new Mock()) {
            mock.hitCount = 3;
            try (AssistantService service = new AssistantService(configuration(mock, 10, 5), CLOCK)) {
                require(request(service).success() && mock.fetches.get() == 3 && mock.models.get() == 2
                        && service.status().contains("daily=6/10"), "three pages fetched separately within cold quota ceiling");
            }
        }
    }

    private static void checkConfiguration() throws IOException {
        Path file = Files.createTempDirectory("mc-ai-wiki-config-").resolve("config.json");
        for (String url : new String[]{"https://evil.example/api.php", "https://minecraft.wiki/w/api.php",
                "https://user@minecraft.wiki/api.php", "https://minecraft.wiki/api.php?x=y",
                "http://minecraft.wiki/api.php"}) {
            AssistantConfig config = new AssistantConfig();
            config.wikiApiUrl = url;
            try {
                config.save(file);
                throw new AssertionError("invalid Wiki endpoint accepted");
            } catch (IllegalArgumentException expected) {
                // Only official HTTPS endpoint or explicit loopback mocks are supported.
            }
        }
    }

    private static void checkFailureAndQuota() throws Exception {
        for (int status : new int[]{200, 429, 503}) {
            try (Mock mock = new Mock()) {
                mock.wikiStatus = status;
                mock.empty = status == 200;
                try (AssistantService service = new AssistantService(configuration(mock, 20, 5), CLOCK)) {
                    var result = request(service);
                    require(!result.success() && mock.models.get() == 1 && mock.searches.get() == 1 && mock.fetches.get() == 0,
                            "Wiki failure or no results cannot run answer or ordinary fallback");
                    if (status == 200) {
                        require(result.failureKind() == OpenRouterClient.FailureKind.NO_KNOWLEDGE, "explicit no-evidence result");
                    }
                }
            }
        }
        try (Mock mock = new Mock(); AssistantService service = new AssistantService(configuration(mock, 2, 5), CLOCK)) {
            require(request(service).failureKind() == OpenRouterClient.FailureKind.DAILY_LIMIT
                    && mock.models.get() == 1 && mock.searches.get() == 1 && mock.fetches.get() == 0, "quota stops unpaid page fetch");
        }
    }

    private static void checkRewriteCorrection() throws Exception {
        String bad = "{\"topic\":\"钻石\",\"keywords\":\"钻石 合成\"}";
        for (String first : List.of(bad, "not JSON", "")) {
            try (Mock mock = new Mock()) {
                mock.rewriteReplies = List.of(new RewriteReply(first, 200));
                try (AssistantService service = new AssistantService(configuration(mock, 10, 5), CLOCK)) {
                    require(request(service).success(), "invalid rewrite repaired before searching");
                    require(mock.rewrites.get() == 2 && mock.models.get() == 3 && mock.searches.get() == 1
                            && mock.fetches.get() == 1 && service.status().contains("daily=5/10"),
                            "exactly one correction is charged and no invalid topic reaches Wiki");
                    var initial = mock.rewriteBodies.get(0);
                    var repaired = mock.rewriteBodies.get(1);
                    require(initial.get("input").equals(repaired.get("input"))
                            && !initial.get("instructions").equals(repaired.get("instructions")),
                            "correction retains the original question and receives a distinct instruction");
                }
            }
        }
        try (Mock mock = new Mock(); AssistantService service = new AssistantService(configuration(mock, 20, 5), CLOCK)) {
            var who = player();
            CompletableFuture<AssistantService.Outcome> first = new CompletableFuture<>();
            require(service.submit(who, first::complete).accepted() && first.get(5, TimeUnit.SECONDS).success(),
                    "prior Wiki answer establishes history");
            mock.rewrites.set(0);
            mock.rewriteBodies.clear();
            mock.rewriteReplies = List.of(new RewriteReply(bad, 200));
            CompletableFuture<AssistantService.Outcome> followup = new CompletableFuture<>();
            require(service.submit(new AssistantService.PlayerRequest(who.playerId(), who.playerName(), "它如何合成？",
                    AssistantService.Retrieval.WIKI, true), followup::complete).accepted()
                    && followup.get(5, TimeUnit.SECONDS).success(), "pronoun question survives correction");
            var input = mock.rewriteBodies.get(1).get("input");
            require(input.equals(mock.rewriteBodies.get(0).get("input")) && input.toString().contains("钻石有什么用")
                    && input.toString().contains("它如何合成"), "both attempts use identical recent history");
        }
    }

    private static void checkRewriteFailureBoundaries() throws Exception {
        String bad = "{\"topic\":\"钻石\",\"keywords\":\"钻石 合成\"}";
        try (Mock mock = new Mock()) {
            mock.rewriteReplies = List.of(new RewriteReply(bad, 200), new RewriteReply(bad, 200));
            Path directory = configuration(mock, 10, 5);
            try (AssistantService service = new AssistantService(directory, CLOCK)) {
                var result = request(service);
                require(result.failureKind() == OpenRouterClient.FailureKind.REWRITE_FAILED
                        && result.text().contains("检索词改写失败") && !result.text().contains("未找到足够"),
                        "exhausted correction has a dedicated user-facing failure");
                require(mock.rewrites.get() == 2 && mock.searches.get() == 0 && mock.fetches.get() == 0
                        && service.status().contains("daily=2/10"), "invalid repair cannot search or answer");
                try (var logs = Files.list(directory.resolve("mc_ai_assistant/logs"))) {
                    JsonObject audit = JsonParser.parseString(Files.readString(logs.findFirst().orElseThrow())).getAsJsonObject();
                    var diagnostic = audit.getAsJsonObject("wikiDiagnostics");
                    require(diagnostic.get("reason").getAsString().equals("failure:WIKI_REWRITE_FAILED")
                            && diagnostic.getAsJsonObject("counts").get("invalidRewrite_NON_ENGLISH").getAsInt() == 2
                            && diagnostic.get("topic").getAsString().isEmpty(), "bounded reason counts without invalid model text");
                }
            }
        }
        try (Mock mock = new Mock()) {
            mock.rewriteReplies = List.of(new RewriteReply(bad, 200));
            try (AssistantService service = new AssistantService(configuration(mock, 1, 5), CLOCK)) {
                require(request(service).failureKind() == OpenRouterClient.FailureKind.DAILY_LIMIT
                        && mock.rewrites.get() == 1 && mock.searches.get() == 0, "quota denies unpaid correction");
            }
        }
        for (int status : new int[]{401, 429, 400, 503, 422}) {
            for (boolean duringCorrection : new boolean[]{false, true}) {
                try (Mock mock = new Mock()) {
                    var failureReply = new RewriteReply(status == 422 ? "content policy refusal" : "", status);
                    mock.rewriteReplies = duringCorrection ? List.of(new RewriteReply(bad, 200), failureReply)
                            : List.of(failureReply);
                    try (AssistantService service = new AssistantService(configuration(mock, 10, 5), CLOCK)) {
                        var expected = switch (status) {
                            case 401 -> OpenRouterClient.FailureKind.AUTH;
                            case 429 -> OpenRouterClient.FailureKind.RATE_LIMIT;
                            case 400 -> OpenRouterClient.FailureKind.INVALID_REQUEST;
                            case 422 -> OpenRouterClient.FailureKind.CONTENT_REFUSED;
                            default -> OpenRouterClient.FailureKind.SERVER;
                        };
                        require(request(service).failureKind() == expected && mock.rewrites.get() == (duringCorrection ? 2 : 1)
                                && mock.searches.get() == 0, "HTTP failure retains category without repair or search fallback");
                    }
                }
            }
        }
    }

    private static void checkRewriteCancellation() throws Exception {
        for (boolean close : new boolean[]{false, true}) {
            try (Mock mock = new Mock()) {
                mock.rewriteReplies = List.of(new RewriteReply("{}", 200));
                mock.stallCorrection = true;
                mock.firstRewriteDelayMillis = close ? 0 : 600;
                try (AssistantService service = new AssistantService(configuration(mock, 10, close ? 5 : 1), CLOCK)) {
                    long started = System.nanoTime();
                    AtomicInteger completions = new AtomicInteger();
                    CompletableFuture<AssistantService.Outcome> result = new CompletableFuture<>();
                    require(service.submit(player(), outcome -> {
                        completions.incrementAndGet();
                        result.complete(outcome);
                    }).accepted(), "correction cancellation request accepted");
                    require(mock.correctionEntered.await(3, TimeUnit.SECONDS), "correction reached provider");
                    if (close) {
                        service.close();
                    } else {
                        require(result.get(3, TimeUnit.SECONDS).failureKind() == OpenRouterClient.FailureKind.TIMEOUT,
                                "correction shares the original deadline");
                        require((System.nanoTime() - started) / 1_000_000 < 1500,
                                "correction must not reset timeout after a slow initial rewrite");
                    }
                    mock.release.countDown();
                    Thread.sleep(100);
                    require(mock.rewrites.get() == 2 && mock.searches.get() == 0 && mock.fetches.get() == 0
                            && completions.get() == (close ? 0 : 1), "late correction cannot resume retrieval after cancellation");
                }
            }
        }
    }

    private static void checkCancellation() throws Exception {
        for (boolean close : new boolean[]{false, true}) {
            try (Mock mock = new Mock()) {
                mock.stall = true;
                try (AssistantService service = new AssistantService(configuration(mock, 20, close ? 5 : 1), CLOCK)) {
                    AtomicInteger completions = new AtomicInteger();
                    CompletableFuture<AssistantService.Outcome> result = new CompletableFuture<>();
                    require(service.submit(player(), outcome -> {
                        completions.incrementAndGet();
                        result.complete(outcome);
                    }).accepted(), "cancellation request accepted");
                    require(mock.entered.await(3, TimeUnit.SECONDS), "Wiki stage reached");
                    if (close) {
                        service.close();
                    } else {
                        require(result.get(3, TimeUnit.SECONDS).failureKind() == OpenRouterClient.FailureKind.TIMEOUT,
                                "Wiki shares total deadline");
                    }
                    mock.release.countDown();
                    Thread.sleep(100);
                    require(service.status().contains("active=0/2") && mock.fetches.get() == 0 && mock.models.get() == 1
                            && completions.get() == (close ? 0 : 1), "late Wiki callbacks cannot continue after cancellation");
                }
            }
        }
    }

    private static WikiQuery query(String topic, String keywords) {
        return new WikiQuery(topic, keywords, "java", "26.2", false);
    }

    private static MediaWikiClient.Page page(long id, String title, String extract) {
        return new MediaWikiClient.Page(id, title, "https://minecraft.wiki/w/Diamond", id + 100, CLOCK.millis(), extract);
    }

    private static JsonObject pageBody(String baseUrl, String extract) {
        JsonObject page = new JsonObject();
        page.addProperty("pageid", 1);
        page.addProperty("ns", 0);
        page.addProperty("title", "Diamond");
        page.addProperty("fullurl", baseUrl + "/w/Diamond");
        page.addProperty("extract", extract);
        page.add("revisions", JsonParser.parseString("[{\"revid\":101}]"));
        JsonArray pages = new JsonArray();
        pages.add(page);
        JsonObject query = new JsonObject();
        query.add("pages", pages);
        JsonObject root = new JsonObject();
        root.add("query", query);
        return root;
    }

    private static AssistantService.PlayerRequest player() {
        return new AssistantService.PlayerRequest(UUID.randomUUID(), "WikiTest", "钻石有什么用？",
                AssistantService.Retrieval.WIKI, true);
    }

    private static AssistantService.Outcome request(AssistantService service) throws Exception {
        var future = new CompletableFuture<AssistantService.Outcome>();
        require(service.submit(player(), future::complete).accepted(), "Wiki accepted without Firecrawl key");
        return future.get(8, TimeUnit.SECONDS);
    }

    private static Path configuration(Mock mock, int quota, int timeout) throws Exception {
        Path directory = Files.createTempDirectory("mc-ai-wiki-pipeline-");
        Files.writeString(directory.resolve("model.secret"), "test-only-key");
        AssistantConfig config = new AssistantConfig();
        config.baseUrl = mock.baseUrl() + "/api/v1";
        config.wikiApiUrl = mock.baseUrl() + "/api.php";
        config.secretFile = "model.secret";
        config.playerCooldownSeconds = 0;
        config.dailyRequestLimit = quota;
        config.requestTimeoutSeconds = timeout;
        config.fullConversationLog = true;
        config.timezone = "UTC";
        config.save(directory.resolve("mc_ai_assistant.json"));
        return directory;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private record RewriteReply(String text, int status) { }

    private static final class Mock implements AutoCloseable {
        final AtomicInteger models = new AtomicInteger();
        final AtomicInteger searches = new AtomicInteger();
        final AtomicInteger fetches = new AtomicInteger();
        final AtomicInteger rewrites = new AtomicInteger();
        final List<JsonObject> rewriteBodies = new java.util.concurrent.CopyOnWriteArrayList<>();
        final CountDownLatch correctionEntered = new CountDownLatch(1);
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final java.util.concurrent.ExecutorService executor = Executors.newCachedThreadPool();
        final HttpServer server;
        volatile String topic = "Diamond";
        volatile int wikiStatus = 200;
        volatile int hitCount = 1;
        volatile boolean empty;
        volatile boolean stall;
        volatile boolean stallCorrection;
        volatile int firstRewriteDelayMillis;
        volatile List<RewriteReply> rewriteReplies = List.of();

        Mock() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                try {
                    handle(exchange);
                } catch (IOException | InterruptedException cancelled) {
                    // Deadline/close tests deliberately cancel transport.
                } catch (Throwable exception) {
                    failure.set(exception);
                } finally {
                    exchange.close();
                }
            });
            server.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void handle(HttpExchange exchange) throws Exception {
            String response;
            int status = 200;
            if (exchange.getRequestURI().getPath().equals("/api.php")) {
                require(exchange.getRequestHeaders().getFirst("Authorization") == null, "no model credential sent to Wiki");
                String query = URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
                require(query.contains("formatversion=2") && query.contains("maxlag=5"), "versioned polite API request");
                if (query.contains("list=search")) {
                    searches.incrementAndGet();
                    require(query.contains("srsearch=" + topic + "&"), "search canonical topic, not intent sentence");
                    entered.countDown();
                    if (stall) {
                        release.await(4, TimeUnit.SECONDS);
                    }
                    JsonArray hits = new JsonArray();
                    if (!empty && topic.equals("Diamond")) {
                        for (int id = 1; id <= hitCount; id++) {
                            JsonObject hit = new JsonObject();
                            hit.addProperty("ns", 0);
                            hit.addProperty("pageid", id);
                            hit.addProperty("title", id == 1 ? "Diamond" : "Diamond " + id);
                            hits.add(hit);
                        }
                    }
                    response = "{\"query\":{\"search\":" + hits + "}}";
                } else {
                    fetches.incrementAndGet();
                    var idMatch = java.util.regex.Pattern.compile("pageids=(\\d+)&").matcher(query);
                    require(idMatch.find() && query.contains("explaintext=1")
                            && query.contains("exsectionformat=wiki"), "single full page extract with section headings");
                    int id = Integer.parseInt(idMatch.group(1));
                    JsonObject body = pageBody(baseUrl(), EXTRACT);
                    var page = body.getAsJsonObject("query").getAsJsonArray("pages").get(0).getAsJsonObject();
                    page.addProperty("pageid", id);
                    page.addProperty("title", id == 1 ? "Diamond" : "Diamond " + id);
                    response = body.toString();
                }
                status = wikiStatus;
            } else {
                models.incrementAndGet();
                require(exchange.getRequestURI().getPath().equals("/api/v1/responses"), "only model or Wiki endpoints");
                JsonObject body = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                        .getAsJsonObject();
                require(!body.has("tools"), "Wiki does not use hosted web tools or embeddings");
                String text;
                if (body.get("max_output_tokens").getAsInt() == 128) {
                    text = "{\"topic\":\"" + topic + "\",\"keywords\":\"diamond usage crafting\"}";
                    int rewriteIndex = rewrites.getAndIncrement();
                    rewriteBodies.add(body);
                    if (rewriteIndex == 0 && firstRewriteDelayMillis > 0) {
                        Thread.sleep(firstRewriteDelayMillis);
                    }
                    if (rewriteIndex < rewriteReplies.size()) {
                        text = rewriteReplies.get(rewriteIndex).text();
                        status = rewriteReplies.get(rewriteIndex).status();
                    }
                    if (rewriteIndex == 1 && stallCorrection) {
                        correctionEntered.countDown();
                        release.await(4, TimeUnit.SECONDS);
                    }
                } else {
                    String payload = body.toString();
                    require(payload.contains("Diamonds are used for crafting") && payload.contains("NOT a verified snapshot")
                            && payload.contains("crafting grids") && payload.contains("钻石有什么用"), "grounded answer preserves question and limitations");
                    text = "钻石可以用来合成工具与盔甲。[1]";
                }
                JsonObject reply = new JsonObject();
                reply.addProperty("output_text", text);
                response = new Gson().toJson(reply);
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }

        @Override
        public void close() {
            release.countDown();
            server.stop(0);
            executor.shutdownNow();
            if (failure.get() != null) {
                throw new AssertionError("Wiki mock failed", failure.get());
            }
        }
    }
}
