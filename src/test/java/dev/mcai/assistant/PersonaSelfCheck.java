package dev.mcai.assistant;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Offline technical checks only; mock answers are not evidence of actual persona quality. */
final class PersonaSelfCheck {
    private static final Duration IDLE = Duration.ofMinutes(30);
    private static final String STYLE = "Use short, warm sentences and village metaphors.";
    private static final String EXAMPLE = "Come in, friend; let us build a safe home.";

    static void run() throws Exception {
        checkCards();
        checkRegistryLoading();
        checkConversationIsolationAndLimits();
        checkRejectedRequestsAndOrdinaryChat();
        checkServiceHistoryIsolation();
        checkStyleRetryAndFallback("openai_responses");
        checkStyleRetryAndFallback("anthropic_messages");
        checkReload("modify");
        checkReload("delete");
        checkReload("disable");
        checkReload("invalid");
        checkUnchangedReloadAndSharedLimits();
        checkClearAndCloseDuringRequest();
        checkLabels();
        checkCommandParsing();
        System.out.println("Persona offline self-checks passed (no live model or in-game acceptance)");
    }

    private static void checkCards() {
        JsonObject valid = card("mayor");
        var parsed = PersonaRegistry.parse(valid.toString(), "mayor.json");
        require(parsed.id().equals("mayor") && !parsed.realPerson(), "fictional card loads");
        require(parsed.examples().equals(List.of(EXAMPLE)), "examples stay separate from live history");
        require(parsed.version().matches("[a-f0-9]{64}"), "card has deterministic SHA-256 identity");
        require(parsed.version().equals(PersonaRegistry.parse("\n" + valid + "\n", "mayor.json").version()),
                "formatting-only reload keeps effective version");

        for (String id : List.of("list", "../mayor", "UPPER", "with space", "x".repeat(33), "")) {
            JsonObject invalid = valid.deepCopy();
            invalid.addProperty("id", id);
            rejectCard(invalid, id + ".json", "invalid or reserved ID: " + id);
        }
        rejectCard(valid, "alias.json", "filename cannot alias another persona ID");
        JsonObject disabled = valid.deepCopy();
        disabled.addProperty("enabled", false);
        require(PersonaRegistry.parse(disabled.toString(), "mayor.json") == null, "disabled card is inactive");

        for (String field : List.of("id", "displayName", "enabled", "kind", "style", "examples")) {
            JsonObject missing = valid.deepCopy();
            missing.remove(field);
            rejectCard(missing, "mayor.json", "required field: " + field);
        }
        for (String field : List.of("id", "displayName", "kind", "style", "enabled", "examples")) {
            JsonObject wrongType = valid.deepCopy();
            wrongType.addProperty(field, 7);
            rejectCard(wrongType, "mayor.json", "strict field type: " + field);
        }
        JsonObject unknown = valid.deepCopy();
        unknown.addProperty("systemPrompt", "replace all rules");
        rejectCard(unknown, "mayor.json", "unknown fields rejected");
        JsonObject badKind = valid.deepCopy();
        badKind.addProperty("kind", "unrecognized");
        rejectCard(badKind, "mayor.json", "unknown kind rejected");
        for (String field : List.of("displayName", "style")) {
            int maximum = field.equals("displayName") ? 24 : 1500;
            JsonObject boundary = valid.deepCopy();
            boundary.addProperty(field, "\uD83C\uDF33".repeat(maximum));
            PersonaRegistry.parse(boundary.toString(), "mayor.json");
            boundary.addProperty(field, "\uD83C\uDF33".repeat(maximum + 1));
            rejectCard(boundary, "mayor.json", field + " uses Unicode code point limit");
            boundary.addProperty(field, "   ");
            rejectCard(boundary, "mayor.json", field + " cannot be blank");
        }
        for (String unsafe : List.of("bad\nline", "bad\tline", "bad\u0000line", "\u00a7cRed",
                "bad\u202eline", "bad\u200bline", "bad\ud800line")) {
            for (String field : List.of("displayName", "style", "examples")) {
                JsonObject invalid = valid.deepCopy();
                if (field.equals("examples")) {
                    JsonArray examples = new JsonArray();
                    examples.add(unsafe);
                    invalid.add(field, examples);
                } else {
                    invalid.addProperty(field, unsafe);
                }
                rejectCard(invalid, "mayor.json", "unsafe characters rejected in " + field);
            }
        }
        JsonObject exampleBoundary = valid.deepCopy();
        JsonArray samples = new JsonArray();
        for (int i = 0; i < 5; i++) {
            samples.add("x".repeat(200));
        }
        exampleBoundary.add("examples", samples);
        PersonaRegistry.parse(exampleBoundary.toString(), "mayor.json");
        samples.add("sixth");
        rejectCard(exampleBoundary, "mayor.json", "maximum five examples");
        samples.remove(5);
        samples.set(0, new com.google.gson.JsonPrimitive("x".repeat(201)));
        rejectCard(exampleBoundary, "mayor.json", "example length bound");
        samples.set(0, new com.google.gson.JsonPrimitive(true));
        rejectCard(exampleBoundary, "mayor.json", "examples must be strings");

        JsonObject real = valid.deepCopy();
        real.addProperty("kind", "real");
        rejectCard(real, "mayor.json", "real card requires administrator consent marker");
        real.addProperty("consentConfirmed", false);
        rejectCard(real, "mayor.json", "false consent marker rejected");
        real.addProperty("consentConfirmed", "true");
        rejectCard(real, "mayor.json", "consent marker is a Boolean");
        real.addProperty("consentConfirmed", true);
        require(PersonaRegistry.parse(real.toString(), "mayor.json").realPerson(), "confirmed real card loads");
    }

    private static void checkRegistryLoading() throws Exception {
        Path root = Files.createTempDirectory("mc-ai-persona-registry-");
        require(PersonaRegistry.load(root.resolve("missing")).cards().isEmpty(), "absent directory is allowed");
        Path cards = Files.createDirectory(root.resolve("cards"));
        Files.writeString(cards.resolve("mayor.json"), card("mayor").toString());
        Files.writeString(cards.resolve("duplicate.json"), card("mayor").toString());
        Files.writeString(cards.resolve("bad.json"), "{\"style\":\"private-card-content\"");
        Files.writeString(cards.resolve("\ud83d\ude00.json"), "{}");
        Files.writeString(cards.resolve("oversize.json"), " ".repeat(PersonaRegistry.MAX_FILE_BYTES + 1));
        var registry = PersonaRegistry.load(cards);
        require(registry.cards().size() == 1 && registry.get("mayor") != null && registry.errors().size() == 4,
                "bad, Unicode-named, duplicate and oversized files do not break valid cards");
        require(!registry.errors().toString().contains("private-card-content"), "load errors exclude card body");
        Files.writeString(cards.resolve("mayor.json"), "{}");
        require(PersonaRegistry.load(cards).get("mayor") == null, "invalid replacement never retains old card");

        Path capacity = Files.createDirectory(root.resolve("capacity"));
        for (int i = 0; i < PersonaRegistry.MAX_PERSONAS; i++) {
            Files.writeString(capacity.resolve("p" + i + ".json"), card("p" + i).toString());
        }
        require(PersonaRegistry.load(capacity).cards().size() == 16, "sixteen bounded cards accepted");
        Files.writeString(capacity.resolve("extra.json"), card("extra").toString());
        require(PersonaRegistry.load(capacity).cards().isEmpty()
                        && !PersonaRegistry.load(capacity).errors().isEmpty(), "over-capacity directory fails closed");
        Path notDirectory = root.resolve("not-directory");
        Files.writeString(notDirectory, "test");
        require(!PersonaRegistry.load(notDirectory).errors().isEmpty(), "non-directory path rejected");
    }

    private static void checkConversationIsolationAndLimits() {
        MutableClock clock = new MutableClock();
        ConversationStore store = new ConversationStore(clock);
        UUID player = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        var mayor = key(player, "mayor", false);
        var privateMayor = key(player, "mayor", true);
        var bard = key(player, "bard", false);
        var privateBard = key(player, "bard", true);
        var otherMayor = key(other, "mayor", false);
        store.record(player, "ordinary", "ordinary answer", 6);
        for (var item : List.of(mayor, privateMayor, bard, privateBard, otherMayor)) {
            clock.advance(Duration.ofSeconds(1));
            store.record(item, item.toString(), "answer", 6);
        }
        require(store.personaSessionCount() == 5, "four persona sessions per player, not four globally");
        require(store.history(player, 6, IDLE).getFirst().question().equals("ordinary"), "ordinary history isolated");
        for (var item : List.of(mayor, privateMayor, bard, privateBard, otherMayor)) {
            require(store.history(item, 6, IDLE).getFirst().question().equals(item.toString()),
                    "player, role and visibility isolate history");
            clock.advance(Duration.ofSeconds(1));
        }
        store.history(mayor, 6, IDLE); // Keep this older-created session recently used.
        clock.advance(Duration.ofSeconds(1));
        store.record(key(player, "miner", false), "new role", "answer", 6);
        require(store.history(privateMayor, 6, IDLE).isEmpty(), "fifth persona evicts least recently used session");
        require(!store.history(mayor, 6, IDLE).isEmpty() && !store.history(otherMayor, 6, IDLE).isEmpty(),
                "eviction preserves recently read and other-player sessions");
        for (int i = 1; i <= 8; i++) {
            store.record(mayor, "turn " + i, "answer", 6);
        }
        require(store.history(mayor, 6, IDLE).size() == 6
                && store.history(mayor, 6, IDLE).getFirst().question().equals("turn 3"), "persona history bounded to six turns");
        store.clearPersona("mayor");
        require(store.history(mayor, 6, IDLE).isEmpty() && store.history(otherMayor, 6, IDLE).isEmpty(),
                "role invalidation clears all players and visibility modes");
        store.clear(player);
        require(store.history(player, 6, IDLE).isEmpty() && store.personaSessionCount() == 0,
                "player clear includes ordinary and every persona");
        store.record(mayor, "expires", "answer", 6);
        clock.advance(IDLE);
        require(store.history(mayor, 6, IDLE).isEmpty(), "persona expires at idle deadline");
        store.record(mayor, "disabled", "answer", 0);
        require(store.personaSessionCount() == 0, "zero history retains no persona session");
        store.record(player, "ordinary", "answer", 6);
        store.record(mayor, "role", "answer", 6);
        store.record(otherMayor, "other", "answer", 6);
        require(store.clearAll() == 2 && store.activePlayers().isEmpty(), "admin clear counts players and clears all modes");
    }

    private static void checkRejectedRequestsAndOrdinaryChat() throws Exception {
        try (Mock mock = new Mock()) {
            Path directory = configuration(mock, "openai_responses");
            writeCard(directory, card("mayor"));
            JsonObject disabled = card("disabled");
            disabled.addProperty("enabled", false);
            writeCard(directory, disabled);
            try (AssistantService service = new AssistantService(directory, Clock.systemUTC())) {
                var callback = new AtomicInteger();
                for (String id : List.of("unknown", "disabled", "../mayor", "list")) {
                    require(!service.submit(player(UUID.randomUUID(), id, false, "hello"), result -> callback.incrementAndGet())
                            .accepted(), "unknown and disabled personas rejected");
                }
                for (var mode : List.of(AssistantService.Retrieval.WIKI, AssistantService.Retrieval.WEB)) {
                    require(!service.submit(new AssistantService.PlayerRequest(UUID.randomUUID(), "Test", "hello", mode,
                            false, "mayor"), result -> callback.incrementAndGet()).accepted(), "persona retrieval combinations rejected");
                }
                require(mock.calls() == 0 && callback.get() == 0 && service.status().contains("daily=0/100"),
                        "rejected persona requests consume no outgoing calls or quota");
                Files.delete(personaFile(directory, "mayor"));
                service.reload();
                var outcome = request(service, player(UUID.randomUUID(), "", false, "ordinary with no roles"));
                require(outcome.success() && outcome.persona() == null && outcome.sources().isEmpty(), "ordinary question works with no cards");
                require(!mock.last().get("instructions").getAsString().contains("Style data JSON"), "ordinary prompt has no persona");
                require(service.status().contains("daily=1/100"), "ordinary request uses existing quota accounting");
            }
        }
    }

    private static void checkServiceHistoryIsolation() throws Exception {
        try (Mock mock = new Mock()) {
            Path directory = configuration(mock, "openai_responses");
            writeCard(directory, card("mayor"));
            writeCard(directory, card("bard"));
            UUID one = UUID.randomUUID();
            UUID two = UUID.randomUUID();
            try (AssistantService service = new AssistantService(directory, Clock.systemUTC())) {
                request(service, player(one, "", false, "ordinary seed"));
                request(service, player(one, "mayor", false, "public mayor seed"));
                require(mock.last().getAsJsonArray("input").size() == 1, "persona starts independent of ordinary history");
                request(service, player(one, "mayor", true, "private mayor seed"));
                require(mock.last().getAsJsonArray("input").size() == 1, "private persona starts independent of public history");
                request(service, player(one, "bard", false, "bard seed"));
                require(mock.last().getAsJsonArray("input").size() == 1, "role switch starts independent history");
                request(service, player(two, "mayor", false, "second player seed"));
                require(mock.last().getAsJsonArray("input").size() == 1, "another player starts independent history");
                request(service, player(one, "mayor", false, "public mayor followup"));
                var input = mock.last().getAsJsonArray("input");
                require(input.size() == 3 && input.get(0).getAsJsonObject().get("content").getAsString().equals("public mayor seed"),
                        "only matching persona conversation enters model payload");
                require(!input.toString().contains(EXAMPLE) && !input.toString().contains("private mayor seed")
                                && !input.toString().contains("ordinary seed"), "examples and other conversations are not chat history");
                request(service, player(one, "", false, "ordinary followup"));
                require(mock.last().getAsJsonArray("input").size() == 3
                        && !mock.last().get("instructions").getAsString().contains(STYLE), "ordinary conversation retains its own prompt and history");
                service.clear(one);
                for (String id : List.of("", "mayor", "bard")) {
                    request(service, player(one, id, false, "after clear " + id));
                    require(mock.last().getAsJsonArray("input").size() == 1, "service clear removes every player conversation");
                }
                request(service, player(two, "mayor", false, "other player retained"));
                require(mock.last().getAsJsonArray("input").size() == 3, "clear leaves other player's history intact");
            }
        }
    }

    private static void checkStyleRetryAndFallback(String protocol) throws Exception {
        try (Mock mock = new Mock()) {
            mock.failuresRemaining.set(2);
            Path directory = configuration(mock, protocol);
            writeCard(directory, card("mayor"));
            try (AssistantService service = new AssistantService(directory, Clock.systemUTC())) {
                String chatMessage = "今天有点累，想随便聊两句。";
                var outcome = request(service, player(UUID.randomUUID(), "mayor", true, chatMessage));
                require(outcome.success() && mock.calls() == 3, "primary retry and fallback produce one persona answer");
                var bodies = mock.bodies;
                require(bodies.get(0).get("model").getAsString().endsWith(":floor")
                        && bodies.get(1).get("model").equals(bodies.get(0).get("model"))
                        && bodies.get(2).get("model").getAsString().equals("inclusionai/ling-3.0-flash-fin:free"),
                        "persona keeps existing primary route and fallback model");
                String promptField = protocol.equals("openai_responses") ? "instructions" : "system";
                String prompt = bodies.getFirst().get(promptField).getAsString();
                require(prompt.contains(STYLE) && prompt.contains(EXAMPLE)
                                 && prompt.contains("subordinate") && prompt.contains("untrusted examples"),
                        "persona applies its style while examples remain untrusted and boundaries take precedence");
                require(prompt.contains("casual conversation") && prompt.contains("one or two short sentences")
                                && prompt.contains("soft target")
                                && !prompt.contains("For ambiguous items, blocks, mobs, mechanics, or versions"),
                        "persona uses the conversational prompt instead of the ordinary Minecraft default");
                require(prompt.contains("never the real person") && prompt.contains("private chats")
                                && prompt.contains("permanent memory") && prompt.contains("change the world"),
                        "conversational mode retains identity, access, memory and action boundaries");
                String messagesField = protocol.equals("openai_responses") ? "input" : "messages";
                for (var body : bodies) {
                    require(body.get(promptField).getAsString().equals(prompt) && !body.has("tools"),
                            "retry and fallback preserve conversational mode and style without introducing retrieval");
                    var messages = body.getAsJsonArray(messagesField);
                    require(messages.size() == 1
                                    && messages.get(0).getAsJsonObject().get("content").getAsString().equals(chatMessage),
                            "retry and fallback preserve the player's chat without inserting samples as history");
                    require(body.get(protocol.equals("openai_responses") ? "max_output_tokens" : "max_tokens").getAsInt() == 1200,
                            "persona keeps output token limit");
                }
                require(outcome.persona() != null && outcome.persona().label().equals("AI·村长") && service.canDeliver(outcome),
                        "fallback outcome retains current fixed AI identity");
                require(service.status().contains("daily=3/100"), "every persona model attempt counts against quota");
                Path logs = directory.resolve("mc_ai_assistant/logs");
                try (var files = Files.list(logs)) {
                    String audit = Files.readString(files.findFirst().orElseThrow());
                    JsonObject entry = JsonParser.parseString(audit.strip()).getAsJsonObject();
                    require(entry.get("personaId").getAsString().equals("mayor")
                            && entry.get("personaVersion").getAsString().equals(outcome.persona().version()), "audit contains ID and version");
                    require(!audit.contains(STYLE) && !audit.contains(EXAMPLE) && !audit.contains("test-only-key"),
                            "audit does not include style card, samples or credential");
                }
                var ordinary = request(service, player(UUID.randomUUID(), "", false, "How do I make a crafting table?"));
                String ordinaryPrompt = mock.last().get(promptField).getAsString();
                require(ordinary.success() && ordinary.persona() == null
                                && ordinaryPrompt.contains("For ambiguous items, blocks, mobs, mechanics, or versions")
                                && !ordinaryPrompt.contains("casual conversation") && !ordinaryPrompt.contains(STYLE),
                        "ordinary mode keeps its original Minecraft prompt after a persona request in both protocols");
            }
        }
    }

    private static void checkReload(String change) throws Exception {
        try (Mock mock = new Mock()) {
            Path directory = configuration(mock, "openai_responses");
            JsonObject original = card("mayor");
            writeCard(directory, original);
            writeCard(directory, card("bard"));
            UUID playerId = UUID.randomUUID();
            UUID other = UUID.randomUUID();
            try (AssistantService service = new AssistantService(directory, Clock.systemUTC())) {
                var stale = request(service, player(playerId, "mayor", false, "old history"));
                var unaffected = request(service, player(other, "bard", false, "bard history"));
                Gate gate = mock.stall("pending old persona");
                var pending = new CompletableFuture<AssistantService.Outcome>();
                var completions = new AtomicInteger();
                require(service.submit(player(playerId, "mayor", false, gate.question), result -> {
                    completions.incrementAndGet();
                    pending.complete(result);
                }).accepted(), "in-flight persona request accepted before " + change);
                require(gate.entered.await(5, TimeUnit.SECONDS), "pending request reached mock");
                JsonObject replacement = original.deepCopy();
                switch (change) {
                    case "modify" -> { replacement.addProperty("style", "Use a different, formal voice."); writeCard(directory, replacement); }
                    case "delete" -> Files.delete(personaFile(directory, "mayor"));
                    case "disable" -> { replacement.addProperty("enabled", false); writeCard(directory, replacement); }
                    case "invalid" -> Files.writeString(personaFile(directory, "mayor"), "{}");
                    default -> throw new AssertionError("unknown reload scenario");
                }
                service.reload();
                require(!pending.get(5, TimeUnit.SECONDS).success() && service.status().contains("active=0/"),
                        change + " cancels old request and releases concurrency");
                require(!service.canDeliver(stale) && service.canDeliver(unaffected),
                        change + " invalidates queued old result but preserves another role");
                gate.release.countDown();
                require(gate.finished.await(5, TimeUnit.SECONDS), "cancelled HTTP handler finishes");
                int calls = mock.calls();
                if (!change.equals("modify")) {
                    require(!service.submit(player(playerId, "mayor", false, "unavailable"), ignored -> {}).accepted()
                            && mock.calls() == calls, "removed role rejects new requests before transport");
                }
                writeCard(directory, original);
                service.reload();
                require(!service.canDeliver(stale), "reintroducing identical card never resurrects a stale outcome");
                request(service, player(playerId, "mayor", false, "new persona history"));
                require(mock.last().getAsJsonArray("input").size() == 1, "role change clears old history");
                request(service, player(other, "bard", false, "bard followup"));
                require(mock.last().getAsJsonArray("input").size() == 3 && completions.get() == 1,
                        "unaffected role retains history and cancelled result completes exactly once");
            }
        }
    }

    private static void checkUnchangedReloadAndSharedLimits() throws Exception {
        try (Mock mock = new Mock()) {
            Path directory = configuration(mock, "openai_responses");
            JsonObject original = card("mayor");
            writeCard(directory, original);
            writeCard(directory, card("bard"));
            MutableClock clock = new MutableClock();
            AssistantConfig config = AssistantConfig.load(directory.resolve("mc_ai_assistant.json"));
            config.playerCooldownSeconds = 8;
            config.fullConversationLog = false;
            config.save(directory.resolve("mc_ai_assistant.json"));
            try (AssistantService service = new AssistantService(directory, clock)) {
                UUID playerId = UUID.randomUUID();
                Gate gate = mock.stall("unchanged reload");
                var pending = new CompletableFuture<AssistantService.Outcome>();
                require(service.submit(player(playerId, "mayor", false, gate.question), pending::complete).accepted(), "initial persona accepted");
                require(gate.entered.await(5, TimeUnit.SECONDS), "unchanged role request reaches model");
                require(!service.submit(player(playerId, "bard", true, "parallel switch"), ignored -> {}).accepted(),
                        "switching role cannot bypass single-player concurrency");
                Files.writeString(personaFile(directory, "mayor"), "\n" + original + "\n");
                service.reload();
                require(!pending.isDone(), "equivalent card reload keeps active request alive");
                gate.release.countDown();
                var outcome = pending.get(5, TimeUnit.SECONDS);
                require(outcome.success() && service.canDeliver(outcome), "equivalent reload preserves delivery");
                require(!service.submit(player(playerId, "bard", false, "cooldown switch"), ignored -> {}).accepted()
                        && service.status().contains("daily=1/100"), "switching role cannot reset cooldown or consume rejected quota");
                clock.advance(Duration.ofSeconds(8));
                request(service, player(playerId, "bard", false, "after cooldown"));
                require(service.status().contains("daily=2/100"), "all personas share daily quota");
                require(Files.notExists(directory.resolve("mc_ai_assistant/logs")), "disabled full log writes no persona audit");
            }
        }
    }

    private static void checkClearAndCloseDuringRequest() throws Exception {
        try (Mock mock = new Mock()) {
            Path directory = configuration(mock, "openai_responses");
            writeCard(directory, card("mayor"));
            UUID playerId = UUID.randomUUID();
            try (AssistantService service = new AssistantService(directory, Clock.systemUTC())) {
                request(service, player(playerId, "mayor", true, "prior context"));
                Gate gate = mock.stall("clear during request");
                var pending = new CompletableFuture<AssistantService.Outcome>();
                require(service.submit(player(playerId, "mayor", true, gate.question), pending::complete).accepted(), "clear scenario accepted");
                require(gate.entered.await(5, TimeUnit.SECONDS), "clear scenario in flight");
                service.clear(playerId);
                gate.release.countDown();
                require(pending.get(5, TimeUnit.SECONDS).success(), "clear permits current reply to finish");
                request(service, player(playerId, "mayor", true, "after in-flight clear"));
                require(mock.last().getAsJsonArray("input").size() == 1, "finishing old request does not restore cleared context");
                Gate closing = mock.stall("close during persona request");
                var callbacks = new AtomicInteger();
                require(service.submit(player(playerId, "mayor", true, closing.question), ignored -> callbacks.incrementAndGet()).accepted(),
                        "shutdown scenario accepted");
                require(closing.entered.await(5, TimeUnit.SECONDS), "shutdown scenario in flight");
                service.close();
                closing.release.countDown();
                require(closing.finished.await(5, TimeUnit.SECONDS), "shutdown transport released");
                require(service.isClosed() && callbacks.get() == 0 && service.status().contains("personaSessions=0"),
                        "shutdown clears persona state and suppresses pending callbacks");
                require(!service.canDeliver(pending.get()), "closed service cannot deliver completed persona reply");
            }
        }
    }

    private static void checkLabels() {
        var fictional = PersonaRegistry.parse(card("mayor").toString(), "mayor.json");
        var tag = new AssistantService.PersonaTag(fictional.id(), fictional.version(), fictional.label(), 0);
        require(AiCommands.personaTitle(tag, "Player", false).getString().contains("[AI·村长]"), "fictional title has fixed AI marker");
        require(AiCommands.personaTitle(tag, "Player", true).getString().contains("私人回答"), "persona private title retains visibility marker");
        JsonObject real = card("friend");
        real.addProperty("displayName", "朋友");
        real.addProperty("kind", "real");
        real.addProperty("consentConfirmed", true);
        var realCard = PersonaRegistry.parse(real.toString(), "friend.json");
        require(realCard.label().equals("AI·模仿朋友口吻"), "real style label cannot claim to be the real person");
        var text = AiCommands.answerChunk("[Admin] https://example.com/raw-model-link");
        require(text.getString().startsWith("▍ ") && text.toFlatList().stream().allMatch(item -> item.getStyle().getClickEvent() == null),
                "answer is visually marked and arbitrary model URLs do not become clickable sources");
        require(AiCommands.answerChunk("\u00a7cFake\u202e\u200b\u0000\tName\u00a7").getString().equals("▍ FakeName"),
                "model text cannot inject color, bidi, invisible or control characters into the fixed chat identity");
    }

    private static void checkCommandParsing() throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        try (Mock mock = new Mock(); AssistantService service = new AssistantService(configuration(mock, "openai_responses"), Clock.systemUTC())) {
            var factory = AiCommands.class.getDeclaredMethod("personaCommands", AssistantService.class, boolean.class);
            factory.setAccessible(true);
            @SuppressWarnings("unchecked")
            var publicNode = (com.mojang.brigadier.builder.LiteralArgumentBuilder<net.minecraft.commands.CommandSourceStack>) factory.invoke(null, service, false);
            @SuppressWarnings("unchecked")
            var privateNode = (com.mojang.brigadier.builder.LiteralArgumentBuilder<net.minecraft.commands.CommandSourceStack>) factory.invoke(null, service, true);
            var dispatcher = new com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack>();
            dispatcher.register(net.minecraft.commands.Commands.literal("ai").then(publicNode)
                    .then(net.minecraft.commands.Commands.literal("private").then(privateNode)));
            for (String command : List.of("ai persona", "ai persona list", "ai persona mayor 你好 世界", "ai private persona mayor 你好 世界")) {
                var parsed = dispatcher.parse(command, null);
                require(parsed.getExceptions().isEmpty() && !parsed.getReader().canRead()
                        && parsed.getContext().getCommand() != null, "production Persona command parses: " + command);
            }
            require(mock.calls() == 0, "command parsing does not send a model request");
        }
    }

    private static JsonObject card(String id) {
        JsonObject card = new JsonObject();
        card.addProperty("id", id);
        card.addProperty("displayName", "村长");
        card.addProperty("enabled", true);
        card.addProperty("kind", "fictional");
        card.addProperty("style", STYLE);
        JsonArray examples = new JsonArray();
        examples.add(EXAMPLE);
        card.add("examples", examples);
        return card;
    }

    private static void rejectCard(JsonObject card, String filename, String description) {
        try {
            PersonaRegistry.parse(card.toString(), filename);
        } catch (RuntimeException expected) {
            return;
        }
        throw new AssertionError(description);
    }

    private static ConversationStore.Key key(UUID playerId, String persona, boolean privateReply) {
        return new ConversationStore.Key(playerId, persona, privateReply);
    }

    private static AssistantService.PlayerRequest player(UUID playerId, String persona, boolean privateReply, String question) {
        return new AssistantService.PlayerRequest(playerId, "PersonaTest", question, AssistantService.Retrieval.NONE, privateReply, persona);
    }

    private static AssistantService.Outcome request(AssistantService service, AssistantService.PlayerRequest request) throws Exception {
        var result = new CompletableFuture<AssistantService.Outcome>();
        require(service.submit(request, result::complete).accepted(), "expected request to be accepted: " + request.question());
        var outcome = result.get(8, TimeUnit.SECONDS);
        require(outcome.success(), "expected mock answer success: " + outcome.text());
        return outcome;
    }

    private static Path configuration(Mock mock, String protocol) throws Exception {
        Path directory = Files.createTempDirectory("mc-ai-persona-service-");
        Files.writeString(directory.resolve("model.secret"), "test-only-key");
        AssistantConfig config = new AssistantConfig();
        config.baseUrl = mock.baseUrl();
        config.protocol = protocol;
        config.secretFile = "model.secret";
        config.playerCooldownSeconds = 0;
        config.requestTimeoutSeconds = 5;
        config.dailyRequestLimit = 100;
        config.fullConversationLog = true;
        config.timezone = "UTC";
        config.save(directory.resolve("mc_ai_assistant.json"));
        return directory;
    }

    private static Path personaFile(Path directory, String id) {
        return directory.resolve("mc_ai_assistant/personas").resolve(id + ".json");
    }

    private static void writeCard(Path directory, JsonObject card) throws IOException {
        Path path = personaFile(directory, card.get("id").getAsString());
        Files.createDirectories(path.getParent());
        Files.writeString(path, card.toString());
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("Persona self-check failed: " + message);
        }
    }

    private static final class Gate {
        final String question;
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);

        Gate(String question) {
            this.question = question;
        }
    }

    private static final class Mock implements AutoCloseable {
        final List<JsonObject> bodies = new CopyOnWriteArrayList<>();
        final AtomicInteger failuresRemaining = new AtomicInteger();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final java.util.concurrent.ExecutorService executor = Executors.newCachedThreadPool();
        final HttpServer server;
        volatile Gate gate;

        Mock() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/api/v1/", exchange -> {
                Gate current = null;
                try {
                    require("Bearer test-only-key".equals(exchange.getRequestHeaders().getFirst("Authorization")), "mock model authorization");
                    boolean anthropic = exchange.getRequestURI().getPath().endsWith("/messages");
                    require(anthropic || exchange.getRequestURI().getPath().endsWith("/responses"), "only known model endpoints called");
                    JsonObject body = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                    bodies.add(body);
                    JsonArray messages = body.getAsJsonArray(anthropic ? "messages" : "input");
                    String question = messages.get(messages.size() - 1).getAsJsonObject().get("content").getAsString();
                    current = gate != null && gate.question.equals(question) ? gate : null;
                    if (current != null) {
                        current.entered.countDown();
                        require(current.release.await(8, TimeUnit.SECONDS), "mock gate must be released");
                    }
                    if (failuresRemaining.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                        respond(exchange, 500, "{\"error\":{\"message\":\"temporary\"}}");
                    } else {
                        JsonObject reply = new JsonObject();
                        reply.addProperty("model", body.get("model").getAsString());
                        if (anthropic) {
                            JsonObject part = new JsonObject();
                            part.addProperty("type", "text");
                            part.addProperty("text", "Mock answer to " + question);
                            JsonArray content = new JsonArray();
                            content.add(part);
                            reply.add("content", content);
                        } else {
                            reply.addProperty("output_text", "Mock answer to " + question);
                        }
                        respond(exchange, 200, reply.toString());
                    }
                } catch (IOException | InterruptedException cancelled) {
                    if (current == null) {
                        failure.compareAndSet(null, cancelled);
                    }
                } catch (Throwable exception) {
                    failure.compareAndSet(null, exception);
                } finally {
                    exchange.close();
                    if (current != null) {
                        current.finished.countDown();
                    }
                }
            });
            server.start();
        }

        String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1"; }
        int calls() { return bodies.size(); }
        JsonObject last() { return bodies.getLast(); }
        Gate stall(String question) { gate = new Gate(question); return gate; }

        private static void respond(HttpExchange exchange, int status, String response) throws IOException {
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }

        @Override
        public void close() {
            if (gate != null) {
                gate.release.countDown();
            }
            server.stop(0);
            executor.shutdownNow();
            if (failure.get() != null) {
                throw new AssertionError("Persona HTTP mock failed", failure.get());
            }
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-09-08T00:00:00Z");
        void advance(Duration duration) { instant = instant.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant, zone); }
        @Override public Instant instant() { return instant; }
    }
}
