package dev.mcai.assistant;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Synthetic fixtures and loopback transport only; not evidence of a real person's conversational similarity. */
final class PersonaDialogueSelfCheck {
    private static final String LEGACY_STYLE = "Legacy-only style marker.";
    private static final String LEGACY_EXAMPLE = "Legacy-only example marker.";
    private static final String PRIMARY = "synthetic-dialogue-primary";
    private static final String FALLBACK = "synthetic-dialogue-fallback";
    private static final String MOCK_CREDENTIAL = "synthetic-loopback-token";

    static void run() throws Exception {
        for (String name : List.of("MC_AI_ASSISTANT_SECRET_FILE", "MC_AI_ASSISTANT_FIRECRAWL_SECRET_FILE")) {
            String override = System.getenv(name);
            require(override == null || override.isBlank(), "offline fixtures require no credential-file environment override");
        }
        checkParsingAndVersionIdentity();
        checkNestedValidation();
        checkSizeBoundaries();
        for (String protocol : List.of("openai_responses", "anthropic_messages")) {
            checkPayloadAndHistory(protocol);
            checkRetryAndFallback(protocol);
        }
        checkEquivalentReload();
        for (String change : List.of("text", "v2_to_v1", "v1_to_v2", "invalid")) {
            checkChangedReload(change);
        }
        System.out.println("Persona dialogue offline self-checks passed (synthetic fixtures; no live API or client acceptance)");
    }

    private static void checkParsingAndVersionIdentity() throws Exception {
        JsonObject original = dialogueCard("dialogue");
        var parsed = parse(original);
        require(parsed.schemaVersion() == 2 && parsed.usesDialogueExamples(),
                "V2 selects dialogue mode");
        require(parsed.style().isEmpty() && parsed.examples().isEmpty(),
                "V2 has no legacy style or flat examples");
        var messages = parsed.dialogueExamples().getFirst().dialogue();
        require(messages.size() == 4
                        && messages.get(0).speaker().equals("other") && messages.get(1).speaker().equals("other")
                        && messages.get(2).speaker().equals("target") && messages.get(3).speaker().equals("target"),
                "consecutive messages by the same speaker retain their boundaries and order");
        for (int i = 0; i < messages.size(); i++) {
            String raw = messageAt(original, 0, i).get("text").getAsString();
            require(messages.get(i).text().equals(raw), "spaces, CR/LF and tabs remain verbatim");
            require(parsed.prompt().contains(new JsonPrimitive(raw).toString()),
                    "prompt JSON preserves the full escaped message");
        }
        requireImmutable(() -> parsed.dialogueExamples().clear(), "outer example list is immutable");
        requireImmutable(() -> messages.clear(), "nested message list is immutable");
        require(parsed.version().matches("[a-f0-9]{64}"), "V2 has a SHA-256 identity");

        JsonObject reordered = reverseFields(original).getAsJsonObject();
        String formatted = "\n" + new GsonBuilder().setPrettyPrinting().create().toJson(reordered) + "\n";
        var equivalent = PersonaRegistry.parse(formatted, "dialogue.json");
        require(equivalent.version().equals(parsed.version()) && equivalent.prompt().equals(parsed.prompt()),
                "nested field order and JSON indentation do not change effective version or prompt");

        assertNewVersion(original, card -> messageAt(card, 0, 0).addProperty("text",
                messageAt(card, 0, 0).get("text").getAsString() + " "), "message whitespace is significant");
        assertNewVersion(original, card -> messageAt(card, 0, 0).addProperty("speaker", "target"),
                "speaker changes are significant even when the dialogue remains valid");
        assertNewVersion(original, card -> {
            JsonArray dialogue = exampleAt(card, 0).getAsJsonArray("dialogue");
            JsonElement first = dialogue.get(0);
            dialogue.set(0, dialogue.get(1));
            dialogue.set(1, first);
        }, "message ordering is significant");
        JsonObject twoExamples = original.deepCopy();
        JsonObject second = exampleAt(twoExamples, 0).deepCopy();
        second.addProperty("id", "EX02");
        twoExamples.getAsJsonArray("examples").add(second);
        assertNewVersion(twoExamples, card -> {
            JsonArray examples = card.getAsJsonArray("examples");
            JsonElement first = examples.get(0);
            examples.set(0, examples.get(1));
            examples.set(1, first);
        }, "example ordering is significant");

        JsonObject legacy = legacyCard("legacy");
        var old = parse(legacy);
        // This literal is the pre-V2 canonical identity, including its historical field order.
        String legacyCanonical = "{\"id\":\"legacy\",\"displayName\":\"Legacy\",\"kind\":\"fictional\","
                + "\"style\":\"Legacy-only style marker.\",\"examples\":[\"Legacy-only example marker.\"]}";
        require(old.version().equals(sha256(legacyCanonical)), "V1 keeps its pre-V2 version identity");
        legacy.addProperty("schemaVersion", 1);
        legacy.addProperty("style", "  " + LEGACY_STYLE + "  ");
        legacy.getAsJsonArray("examples").set(0, new JsonPrimitive("  " + LEGACY_EXAMPLE + "  "));
        var explicitV1 = parse(reverseFields(legacy).getAsJsonObject());
        require(explicitV1.schemaVersion() == 1 && !explicitV1.usesDialogueExamples()
                        && explicitV1.dialogueExamples().isEmpty()
                        && explicitV1.version().equals(old.version()) && explicitV1.prompt().equals(old.prompt()),
                "explicit schema 1 preserves legacy trimming, accessors, hash and prompt");
        JsonObject switched = dialogueCard("legacy");
        switched.addProperty("displayName", "Legacy");
        require(!parse(switched).version().equals(old.version()), "switching schema changes effective mode identity");
    }

    private static void checkNestedValidation() {
        JsonObject valid = dialogueCard("dialogue");
        for (String field : List.of("schemaVersion", "id", "displayName", "enabled", "kind", "examples")) {
            rejectMutation(valid, card -> card.remove(field), "missing V2 field " + field);
        }
        for (JsonElement version : List.of(new JsonPrimitive("2"), new JsonPrimitive(true),
                new JsonPrimitive(0), new JsonPrimitive(3), JsonParser.parseString("2.0"),
                JsonParser.parseString("2e0"), JsonNull.INSTANCE)) {
            rejectMutation(valid, card -> card.add("schemaVersion", version), "strict schema version token");
        }
        rejectMutation(valid, card -> card.addProperty("style", LEGACY_STYLE), "V2 forbids legacy style");
        rejectMutation(valid, card -> card.addProperty("mode", "dialogue"), "schema is the only mode selector");
        rejectMutation(valid, card -> card.addProperty("examples", "not an array"), "example array type");
        rejectMutation(valid, card -> card.add("examples", new JsonArray()), "empty examples rejected");
        rejectMutation(valid, card -> card.getAsJsonArray("examples").set(0, new JsonPrimitive("flat")),
                "V2 cannot silently accept a V1 flat sample");
        for (String field : List.of("id", "dialogue")) {
            rejectMutation(valid, card -> exampleAt(card, 0).remove(field), "missing example field " + field);
        }
        rejectMutation(valid, card -> exampleAt(card, 0).addProperty("id", 1), "example ID type");
        rejectMutation(valid, card -> exampleAt(card, 0).addProperty("id", " "), "blank example ID");
        rejectMutation(valid, card -> exampleAt(card, 0).addProperty("context", "extra"), "unknown example field");
        rejectMutation(valid, card -> exampleAt(card, 0).addProperty("dialogue", false), "dialogue type");
        rejectMutation(valid, card -> card.getAsJsonArray("examples").add(exampleAt(card, 0).deepCopy()),
                "duplicate example IDs rejected");
        for (String field : List.of("speaker", "text")) {
            rejectMutation(valid, card -> messageAt(card, 0, 0).remove(field), "missing message field " + field);
            rejectMutation(valid, card -> messageAt(card, 0, 0).add(field, JsonNull.INSTANCE),
                    "null message field " + field);
        }
        rejectMutation(valid, card -> messageAt(card, 0, 0).addProperty("speaker", 1), "speaker type");
        rejectMutation(valid, card -> messageAt(card, 0, 0).addProperty("speaker", "assistant"), "speaker enum");
        rejectMutation(valid, card -> messageAt(card, 0, 0).addProperty("speaker", " other "),
                "speaker labels must not be normalized silently");
        rejectMutation(valid, card -> messageAt(card, 0, 0).addProperty("text", false), "text type");
        rejectMutation(valid, card -> messageAt(card, 0, 0).addProperty("time", "synthetic"), "unknown message field");
        rejectMutation(valid, card -> exampleAt(card, 0).getAsJsonArray("dialogue").set(0, new JsonPrimitive("text")),
                "message must be an object");
        rejectMutation(valid, card -> messageAt(card, 0, 3).addProperty("speaker", "other"),
                "example must end with target");
        rejectMutation(valid, card -> exampleAt(card, 0).getAsJsonArray("dialogue").forEach(
                message -> message.getAsJsonObject().addProperty("speaker", "target")),
                "target-only examples cannot supply a conversational exchange");
        rejectMutation(valid, card -> {
            JsonArray one = new JsonArray();
            one.add(message("target", "single synthetic reply"));
            exampleAt(card, 0).add("dialogue", one);
        }, "a single message is not a dialogue");
        for (String unsafe : List.of(" \t\r\n ", "bad\u0000text", "bad\u000btext", "\u00a7cRed",
                "bad\u202etext", "bad\u200btext", "bad\ud800text")) {
            rejectMutation(valid, card -> messageAt(card, 0, 0).addProperty("text", unsafe),
                    "blank or unsafe message text rejected");
        }
        JsonObject syntheticRealFlag = valid.deepCopy();
        syntheticRealFlag.addProperty("kind", "real");
        rejectMutation(syntheticRealFlag, card -> card.remove("consentConfirmed"), "V2 consent marker required");
        rejectMutation(syntheticRealFlag, card -> card.addProperty("consentConfirmed", false), "false V2 consent marker");
        rejectMutation(syntheticRealFlag, card -> card.addProperty("consentConfirmed", "true"), "consent Boolean type");
        require(parse(syntheticRealFlag).realPerson(), "synthetic real-kind fixture accepts explicit consent");
        JsonObject disabled = valid.deepCopy();
        disabled.addProperty("enabled", false);
        require(parse(disabled) == null, "valid disabled V2 card is inactive");
    }

    private static void checkSizeBoundaries() throws Exception {
        parse(gridCard(16, 2, "x"));
        reject(gridCard(17, 2, "x"), "more than 16 examples");
        parse(gridCard(1, 32, "x"));
        reject(gridCard(1, 33, "x"), "more than 32 messages in one example");
        JsonObject totalMessages = gridCard(8, 32, "x");
        parse(totalMessages);
        exampleAt(totalMessages, 7).getAsJsonArray("dialogue").remove(31);
        JsonObject extra = exampleAt(gridCard(1, 2, "x"), 0).deepCopy();
        extra.addProperty("id", "EX09");
        totalMessages.getAsJsonArray("examples").add(extra);
        reject(totalMessages, "257 messages exceed the total without exceeding any individual limit");

        JsonObject points = gridCard(1, 2, "x");
        messageAt(points, 0, 1).addProperty("text", "\ud83c\udf33".repeat(2000));
        parse(points);
        messageAt(points, 0, 1).addProperty("text", "\ud83c\udf33".repeat(2001));
        reject(points, "per-message limit uses Unicode code points");
        JsonObject totalText = gridCard(4, 2, "x".repeat(2000));
        parse(totalText);
        exampleAt(totalText, 0).getAsJsonArray("dialogue").add(message("target", "x"));
        reject(totalText, "16001 code points exceed the total without exceeding message limits");

        Path cards = Files.createTempDirectory("mc-ai-dialogue-byte-boundaries-");
        Path file = cards.resolve("dialogue.json");
        Files.writeString(file, padded(dialogueCard("dialogue"), 64 * 1024), StandardCharsets.UTF_8);
        require(PersonaRegistry.load(cards).get("dialogue") != null, "V2 accepts exactly 64 KiB");
        Files.writeString(file, padded(dialogueCard("dialogue"), 64 * 1024 + 1), StandardCharsets.UTF_8);
        require(PersonaRegistry.load(cards).get("dialogue") == null && !PersonaRegistry.load(cards).errors().isEmpty(),
                "V2 rejects an oversized file instead of retaining an earlier valid card");
        Files.writeString(file, padded(legacyCard("dialogue"), 16 * 1024), StandardCharsets.UTF_8);
        require(PersonaRegistry.load(cards).get("dialogue") != null, "legacy files still accept exactly 16 KiB");
        Files.writeString(file, padded(legacyCard("dialogue"), 16 * 1024 + 1), StandardCharsets.UTF_8);
        require(PersonaRegistry.load(cards).get("dialogue") == null, "V1 cannot use the larger V2 file allowance");
        Path validFile = cards.resolve("valid.json");
        Files.writeString(validFile, dialogueCard("valid").toString(), StandardCharsets.UTF_8);
        Files.writeString(file, "{\"schemaVersion\":2,\"secretText\":\"synthetic-error-body-marker\"",
                StandardCharsets.UTF_8);
        var mixed = PersonaRegistry.load(cards);
        require(mixed.get("valid") != null && mixed.get("dialogue") == null
                        && !mixed.errors().toString().contains("synthetic-error-body-marker"),
                "invalid V2 file cannot disable another card or leak its body through load errors");
    }

    private static void checkPayloadAndHistory(String protocol) throws Exception {
        try (Mock mock = new Mock()) {
            Path directory = configuration(mock, protocol);
            JsonObject original = dialogueCard("dialogue");
            JsonObject otherCard = dialogueCard("second");
            writeCard(directory, original);
            writeCard(directory, otherCard);
            writeCard(directory, legacyCard("legacy"));
            var expected = parse(original);
            UUID player = UUID.randomUUID();
            try (AssistantService service = new AssistantService(directory, Clock.systemUTC())) {
                var first = request(service, player(player, "dialogue", false, "LIVE_FIRST"));
                assertDialoguePrompt(mock.last(), protocol, expected);
                assertMessages(mock.last(), protocol, "user", "LIVE_FIRST");
                request(service, player(player, "dialogue", false, "LIVE_SECOND"));
                assertDialoguePrompt(mock.last(), protocol, expected);
                assertMessages(mock.last(), protocol, "user", "LIVE_FIRST", "assistant", first.text(), "user", "LIVE_SECOND");
                require(!messages(mock.last(), protocol).toString().contains("V2_"),
                        "reference examples never enter real user/assistant history");

                request(service, player(player, "", false, "ORDINARY_FIRST"));
                assertMessages(mock.last(), protocol, "user", "ORDINARY_FIRST");
                String ordinaryPrompt = prompt(mock.last(), protocol);
                require(ordinaryPrompt.contains("For ambiguous items, blocks, mobs, mechanics, or versions")
                                && !ordinaryPrompt.contains("V2_") && !ordinaryPrompt.contains(LEGACY_STYLE),
                        "ordinary mode keeps its own prompt and does not inherit either card schema");
                request(service, player(player, "legacy", false, "LEGACY_FIRST"));
                assertMessages(mock.last(), protocol, "user", "LEGACY_FIRST");
                require(prompt(mock.last(), protocol).contains(LEGACY_STYLE)
                                && prompt(mock.last(), protocol).contains("one or two short sentences")
                                && !prompt(mock.last(), protocol).contains("V2_"),
                        "V1 remains on the historical persona prompt without V2 contamination");
                request(service, player(player, "second", false, "OTHER_ROLE_FIRST"));
                assertMessages(mock.last(), protocol, "user", "OTHER_ROLE_FIRST");
                assertDialoguePrompt(mock.last(), protocol, parse(otherCard));
                require(!prompt(mock.last(), protocol).contains("V2_OTHER_dialogue"),
                        "another V2 role receives only its own examples");

                request(service, player(player, "dialogue", true, "PRIVATE_FIRST"));
                assertMessages(mock.last(), protocol, "user", "PRIVATE_FIRST");
                request(service, player(player, "dialogue", false, "LIVE_THIRD"));
                JsonArray publicHistory = messages(mock.last(), protocol);
                require(publicHistory.size() == 5 && !publicHistory.toString().contains("PRIVATE_FIRST")
                                && !publicHistory.toString().contains("OTHER_ROLE_FIRST")
                                && !publicHistory.toString().contains("LEGACY_FIRST"),
                        "V2 public history is isolated from private, other-role and V1 sessions");
                service.clear(player);
                request(service, player(player, "dialogue", false, "PUBLIC_AFTER_CLEAR"));
                assertMessages(mock.last(), protocol, "user", "PUBLIC_AFTER_CLEAR");
                request(service, player(player, "dialogue", true, "PRIVATE_AFTER_CLEAR"));
                assertMessages(mock.last(), protocol, "user", "PRIVATE_AFTER_CLEAR");
                require(mock.calls() == 9 && service.status().contains("daily=9/100"),
                        "V2, V1 and ordinary requests share the production attempt counter");
            }
        }
    }

    private static void checkRetryAndFallback(String protocol) throws Exception {
        try (Mock mock = new Mock()) {
            Path directory = configuration(mock, protocol);
            JsonObject original = dialogueCard("dialogue");
            writeCard(directory, original);
            UUID player = UUID.randomUUID();
            try (AssistantService service = new AssistantService(directory, Clock.systemUTC())) {
                var seed = request(service, player(player, "dialogue", true, "RETRY_HISTORY"));
                mock.failuresRemaining.set(2);
                request(service, player(player, "dialogue", true, "RETRY_FOLLOWUP"));
                require(mock.calls() == 4 && service.status().contains("daily=4/100"),
                        "two 500s produce one primary retry and one fallback, each counted once");
                List<JsonObject> attempts = mock.bodies.subList(1, 4);
                require(attempts.get(0).get("model").getAsString().equals(PRIMARY)
                                && attempts.get(1).get("model").getAsString().equals(PRIMARY)
                                && attempts.get(2).get("model").getAsString().equals(FALLBACK),
                        "fallback changes the model only after the primary retry");
                for (JsonObject body : attempts) {
                    assertDialoguePrompt(body, protocol, parse(original));
                    assertMessages(body, protocol, "user", "RETRY_HISTORY", "assistant", seed.text(),
                            "user", "RETRY_FOLLOWUP");
                    require(!body.has("tools"), "V2 retry and fallback do not introduce tools");
                    require(body.get(protocol.equals("openai_responses") ? "max_output_tokens" : "max_tokens")
                            .getAsInt() == 1200, "V2 preserves the configured output token budget");
                }
            }
        }
    }

    private static void checkEquivalentReload() throws Exception {
        try (Mock mock = new Mock()) {
            Path directory = configuration(mock, "openai_responses");
            JsonObject original = dialogueCard("dialogue");
            writeCard(directory, original);
            UUID player = UUID.randomUUID();
            try (AssistantService service = new AssistantService(directory, Clock.systemUTC())) {
                var prior = request(service, player(player, "dialogue", false, "BEFORE_EQUIVALENT_RELOAD"));
                Gate gate = mock.stall("EQUIVALENT_PENDING");
                var pending = new CompletableFuture<AssistantService.Outcome>();
                require(service.submit(player(player, "dialogue", false, gate.question), pending::complete).accepted(),
                        "equivalent-reload request accepted");
                require(gate.entered.await(5, TimeUnit.SECONDS), "equivalent-reload request reached transport");
                Files.writeString(personaFile(directory, "dialogue"),
                        "\n" + new GsonBuilder().setPrettyPrinting().create().toJson(reverseFields(original)) + "\n",
                        StandardCharsets.UTF_8);
                service.reload();
                require(!pending.isDone() && service.canDeliver(prior),
                        "format-only V2 reload preserves the active request and already completed outcome");
                gate.release.countDown();
                var outcome = pending.get(8, TimeUnit.SECONDS);
                require(outcome.success() && service.canDeliver(outcome), "equivalent reload permits normal delivery");
                request(service, player(player, "dialogue", false, "AFTER_EQUIVALENT_RELOAD"));
                require(messages(mock.last(), "openai_responses").size() == 5,
                        "format-only V2 reload retains both real exchanges");
            }
        }
    }

    private static void checkChangedReload(String change) throws Exception {
        try (Mock mock = new Mock()) {
            Path directory = configuration(mock, "openai_responses");
            JsonObject original = change.equals("v1_to_v2") ? legacyCard("dialogue") : dialogueCard("dialogue");
            writeCard(directory, original);
            writeCard(directory, dialogueCard("unaffected"));
            UUID player = UUID.randomUUID();
            UUID otherPlayer = UUID.randomUUID();
            try (AssistantService service = new AssistantService(directory, Clock.systemUTC())) {
                var oldPublic = request(service, player(player, "dialogue", false, "OLD_PUBLIC"));
                var oldPrivate = request(service, player(player, "dialogue", true, "OLD_PRIVATE"));
                var unaffected = request(service, player(otherPlayer, "unaffected", false, "UNAFFECTED_HISTORY"));
                Gate gate = mock.stall("STALE_PENDING");
                var pending = new CompletableFuture<AssistantService.Outcome>();
                var completions = new AtomicInteger();
                require(service.submit(player(player, "dialogue", false, gate.question), outcome -> {
                    completions.incrementAndGet();
                    pending.complete(outcome);
                }).accepted(), "old-mode request accepted before " + change);
                require(gate.entered.await(5, TimeUnit.SECONDS), "old-mode request reached transport before " + change);
                JsonObject replacement = original.deepCopy();
                switch (change) {
                    case "text" -> messageAt(replacement, 0, 3).addProperty("text", "Changed synthetic ending.");
                    case "v2_to_v1" -> replacement = legacyCard("dialogue");
                    case "v1_to_v2" -> replacement = dialogueCard("dialogue");
                    case "invalid" -> messageAt(replacement, 0, 3).addProperty("speaker", "unrecognized");
                    default -> throw new AssertionError("unknown synthetic scenario");
                }
                writeCard(directory, replacement);
                service.reload();
                var cancelled = pending.get(5, TimeUnit.SECONDS);
                require(!cancelled.success() && service.status().contains("active=0/"),
                        change + " cancels the old in-flight result and releases concurrency");
                require(!service.canDeliver(oldPublic) && !service.canDeliver(oldPrivate) && service.canDeliver(unaffected),
                        change + " invalidates both visibility modes without invalidating an unrelated role");
                gate.release.countDown();
                require(gate.finished.await(5, TimeUnit.SECONDS), "late HTTP response finishes after " + change);
                int callsBeforeNewRequest = mock.calls();
                if (change.equals("invalid")) {
                    require(!service.submit(player(player, "dialogue", false, "INVALID_CARD_REQUEST"), ignored -> {}).accepted()
                                    && mock.calls() == callsBeforeNewRequest,
                            "invalid replacement fails before transport rather than retaining old V2");
                } else {
                    request(service, player(player, "dialogue", false, "NEW_MODE_PUBLIC"));
                    assertMessages(mock.last(), "openai_responses", "user", "NEW_MODE_PUBLIC");
                    var replacementCard = parse(replacement);
                    if (replacementCard.usesDialogueExamples()) {
                        assertDialoguePrompt(mock.last(), "openai_responses", replacementCard);
                    } else {
                        require(prompt(mock.last(), "openai_responses").endsWith(replacementCard.prompt())
                                        && prompt(mock.last(), "openai_responses").contains("one or two short sentences"),
                                "V2-to-V1 reload actually restores the legacy prompt path");
                    }
                    request(service, player(player, "dialogue", true, "NEW_MODE_PRIVATE"));
                    assertMessages(mock.last(), "openai_responses", "user", "NEW_MODE_PRIVATE");
                }
                writeCard(directory, original);
                service.reload();
                require(!service.canDeliver(oldPublic) && !service.canDeliver(oldPrivate),
                        "restoring the original card must not resurrect stale queued outcomes");
                request(service, player(player, "dialogue", false, "RESTORED_PUBLIC"));
                assertMessages(mock.last(), "openai_responses", "user", "RESTORED_PUBLIC");
                request(service, player(player, "dialogue", true, "RESTORED_PRIVATE"));
                assertMessages(mock.last(), "openai_responses", "user", "RESTORED_PRIVATE");
                request(service, player(otherPlayer, "unaffected", false, "UNAFFECTED_FOLLOWUP"));
                assertMessages(mock.last(), "openai_responses", "user", "UNAFFECTED_HISTORY",
                        "assistant", unaffected.text(), "user", "UNAFFECTED_FOLLOWUP");
                require(completions.get() == 1, "cancelled request completes exactly once despite its late HTTP response");
            }
        }
    }

    private static void assertDialoguePrompt(JsonObject body, String protocol, PersonaRegistry.Card card) {
        String actual = prompt(body, protocol);
        require(actual.equals(card.prompt()), "V2 full minimal prompt is sent without an added legacy system prompt");
        for (String oldRule : List.of("Style data JSON:", LEGACY_STYLE, "Fictional style does not grant a body",
                "Do not claim sensory experiences", "one or two short sentences", "5-30 Chinese characters",
                "Minecraft", "For ambiguous items, blocks, mobs, mechanics, or versions")) {
            require(!actual.contains(oldRule), "V2 request excludes old style, body, length and Minecraft rules");
        }
        for (var example : card.dialogueExamples()) {
            for (var message : example.dialogue()) {
                require(actual.contains(new JsonPrimitive(message.text()).toString()),
                        "V2 system data includes every full example message");
            }
        }
    }

    private static void assertMessages(JsonObject body, String protocol, String... roleAndContent) {
        JsonArray input = messages(body, protocol);
        require(roleAndContent.length % 2 == 0 && input.size() == roleAndContent.length / 2,
                "only the expected real conversation messages reach the adapter");
        for (int i = 0; i < input.size(); i++) {
            JsonObject message = input.get(i).getAsJsonObject();
            require(message.get("role").getAsString().equals(roleAndContent[i * 2])
                            && message.get("content").getAsString().equals(roleAndContent[i * 2 + 1]),
                    "real user/assistant order and content survive the adapter");
        }
    }

    private static String prompt(JsonObject body, String protocol) {
        return body.get(protocol.equals("openai_responses") ? "instructions" : "system").getAsString();
    }

    private static JsonArray messages(JsonObject body, String protocol) {
        return body.getAsJsonArray(protocol.equals("openai_responses") ? "input" : "messages");
    }

    private static JsonObject commonCard(String id) {
        JsonObject card = new JsonObject();
        card.addProperty("id", id);
        card.addProperty("displayName", "Dialogue fixture");
        card.addProperty("enabled", true);
        card.addProperty("kind", "fictional");
        card.addProperty("consentConfirmed", true);
        return card;
    }

    private static JsonObject dialogueCard(String id) {
        JsonObject card = commonCard(id);
        card.addProperty("schemaVersion", 2);
        JsonObject example = new JsonObject();
        example.addProperty("id", "EX01");
        JsonArray dialogue = new JsonArray();
        dialogue.add(message("other", "  V2_OTHER_" + id + "\tline\nnext  "));
        dialogue.add(message("other", "V2_FOLLOWUP_" + id));
        dialogue.add(message("target", "V2_TARGET_" + id));
        dialogue.add(message("target", "  V2_FINAL_" + id + "\r\n\tend  "));
        example.add("dialogue", dialogue);
        JsonArray examples = new JsonArray();
        examples.add(example);
        card.add("examples", examples);
        return card;
    }

    private static JsonObject legacyCard(String id) {
        JsonObject card = commonCard(id);
        card.addProperty("displayName", "Legacy");
        card.addProperty("style", LEGACY_STYLE);
        JsonArray examples = new JsonArray();
        examples.add(LEGACY_EXAMPLE);
        card.add("examples", examples);
        return card;
    }

    private static JsonObject gridCard(int exampleCount, int messagesPerExample, String text) {
        JsonObject card = commonCard("dialogue");
        card.addProperty("schemaVersion", 2);
        JsonArray examples = new JsonArray();
        for (int i = 0; i < exampleCount; i++) {
            JsonObject example = new JsonObject();
            example.addProperty("id", "EX" + (i + 1));
            JsonArray messages = new JsonArray();
            for (int j = 0; j < messagesPerExample; j++) {
                messages.add(message(j == 0 ? "other" : "target", text));
            }
            example.add("dialogue", messages);
            examples.add(example);
        }
        card.add("examples", examples);
        return card;
    }

    private static JsonObject message(String speaker, String text) {
        JsonObject message = new JsonObject();
        message.addProperty("speaker", speaker);
        message.addProperty("text", text);
        return message;
    }

    private static JsonObject exampleAt(JsonObject card, int example) {
        return card.getAsJsonArray("examples").get(example).getAsJsonObject();
    }

    private static JsonObject messageAt(JsonObject card, int example, int message) {
        return exampleAt(card, example).getAsJsonArray("dialogue").get(message).getAsJsonObject();
    }

    private static PersonaRegistry.Card parse(JsonObject card) {
        return PersonaRegistry.parse(card.toString(), card.get("id").getAsString() + ".json");
    }

    private static void assertNewVersion(JsonObject original, Consumer<JsonObject> mutation, String description) {
        JsonObject changed = original.deepCopy();
        mutation.accept(changed);
        require(!parse(original).version().equals(parse(changed).version()), description);
    }

    private static void rejectMutation(JsonObject original, Consumer<JsonObject> mutation, String description) {
        JsonObject changed = original.deepCopy();
        mutation.accept(changed);
        reject(changed, description);
    }

    private static void reject(JsonObject card, String description) {
        try {
            PersonaRegistry.parse(card.toString(), "dialogue.json");
        } catch (RuntimeException expected) {
            return;
        }
        throw new AssertionError("Persona dialogue validation accepted: " + description);
    }

    private static void requireImmutable(Runnable mutation, String description) {
        try {
            mutation.run();
        } catch (UnsupportedOperationException expected) {
            return;
        }
        throw new AssertionError("Persona dialogue mutability: " + description);
    }

    private static JsonElement reverseFields(JsonElement element) {
        if (element.isJsonObject()) {
            JsonObject result = new JsonObject();
            List<String> keys = new ArrayList<>(element.getAsJsonObject().keySet());
            Collections.reverse(keys);
            for (String key : keys) result.add(key, reverseFields(element.getAsJsonObject().get(key)));
            return result;
        }
        if (element.isJsonArray()) {
            JsonArray result = new JsonArray();
            for (JsonElement item : element.getAsJsonArray()) result.add(reverseFields(item));
            return result;
        }
        return element.deepCopy();
    }

    private static String padded(JsonObject card, int bytes) {
        String json = card.toString();
        int padding = bytes - json.getBytes(StandardCharsets.UTF_8).length;
        require(padding >= 0, "synthetic card fits the requested byte-boundary fixture");
        return json + " ".repeat(padding);
    }

    private static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static Path configuration(Mock mock, String protocol) throws Exception {
        Path directory = Files.createTempDirectory("mc-ai-dialogue-service-");
        Files.writeString(directory.resolve("synthetic.secret"), MOCK_CREDENTIAL, StandardCharsets.UTF_8);
        AssistantConfig config = new AssistantConfig();
        config.baseUrl = mock.baseUrl();
        config.protocol = protocol;
        config.primaryModel = PRIMARY;
        config.fallbackModel = FALLBACK;
        config.secretFile = "synthetic.secret";
        config.firecrawlSecretFile = "synthetic.secret";
        config.firecrawlBaseUrl = mock.baseUrl();
        config.wikiApiUrl = "http://127.0.0.1:" + mock.server.getAddress().getPort() + "/api.php";
        config.playerCooldownSeconds = 0;
        config.requestTimeoutSeconds = 5;
        config.dailyRequestLimit = 100;
        config.fullConversationLog = false;
        config.timezone = "UTC";
        config.save(directory.resolve("mc_ai_assistant.json"));
        return directory;
    }

    private static Path personaFile(Path directory, String id) {
        return directory.resolve("mc_ai_assistant/personas").resolve(id + ".json");
    }

    private static void writeCard(Path directory, JsonObject card) throws IOException {
        Path file = personaFile(directory, card.get("id").getAsString());
        Files.createDirectories(file.getParent());
        Files.writeString(file, card.toString(), StandardCharsets.UTF_8);
    }

    private static AssistantService.PlayerRequest player(UUID id, String persona, boolean privateReply, String question) {
        return new AssistantService.PlayerRequest(id, "SyntheticDialogue", question,
                AssistantService.Retrieval.NONE, privateReply, persona);
    }

    private static AssistantService.Outcome request(AssistantService service, AssistantService.PlayerRequest player)
            throws Exception {
        var pending = new CompletableFuture<AssistantService.Outcome>();
        require(service.submit(player, pending::complete).accepted(), "synthetic request should be accepted");
        var outcome = pending.get(8, TimeUnit.SECONDS);
        require(outcome.success(), "synthetic loopback request should succeed");
        return outcome;
    }

    private static void require(boolean condition, String description) {
        if (!condition) throw new AssertionError("Persona dialogue self-check failed: " + description);
    }

    private static final class Gate {
        final String question;
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);

        Gate(String question) { this.question = question; }
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
                    require(exchange.getRequestMethod().equals("POST"), "mock accepts model POST only");
                    require(("Bearer " + MOCK_CREDENTIAL).equals(exchange.getRequestHeaders().getFirst("Authorization")),
                            "mock receives only its synthetic credential");
                    String path = exchange.getRequestURI().getPath();
                    boolean anthropic = path.equals("/api/v1/messages");
                    require(anthropic || path.equals("/api/v1/responses"), "only known loopback adapter endpoints called");
                    JsonObject body = JsonParser.parseString(
                            new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                    bodies.add(body);
                    JsonArray input = body.getAsJsonArray(anthropic ? "messages" : "input");
                    String question = input.get(input.size() - 1).getAsJsonObject().get("content").getAsString();
                    current = gate != null && gate.question.equals(question) ? gate : null;
                    if (current != null) {
                        current.entered.countDown();
                        require(current.release.await(8, TimeUnit.SECONDS), "test releases its in-flight HTTP gate");
                    }
                    if (failuresRemaining.getAndUpdate(remaining -> Math.max(0, remaining - 1)) > 0) {
                        respond(exchange, 500, "{\"error\":{\"message\":\"synthetic retryable failure\"}}");
                    } else {
                        JsonObject reply = new JsonObject();
                        reply.addProperty("model", body.get("model").getAsString());
                        String answer = "Synthetic reply to " + question;
                        if (anthropic) {
                            JsonObject part = new JsonObject();
                            part.addProperty("type", "text");
                            part.addProperty("text", answer);
                            JsonArray content = new JsonArray();
                            content.add(part);
                            reply.add("content", content);
                        } else {
                            reply.addProperty("output_text", answer);
                        }
                        respond(exchange, 200, reply.toString());
                    }
                } catch (IOException | InterruptedException cancelled) {
                    if (current == null) failure.compareAndSet(null, cancelled);
                    if (cancelled instanceof InterruptedException) Thread.currentThread().interrupt();
                } catch (Throwable unexpected) {
                    failure.compareAndSet(null, unexpected);
                } finally {
                    exchange.close();
                    if (current != null) current.finished.countDown();
                }
            });
            server.start();
        }

        String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1"; }
        int calls() { return bodies.size(); }
        JsonObject last() { return bodies.getLast(); }
        Gate stall(String question) { gate = new Gate(question); return gate; }

        private static void respond(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }

        @Override
        public void close() {
            if (gate != null) gate.release.countDown();
            server.stop(0);
            executor.shutdownNow();
            if (failure.get() != null) throw new AssertionError("Synthetic dialogue HTTP mock failed", failure.get());
        }
    }
}
