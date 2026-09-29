package dev.mcai.assistant;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Only explicit player preferences are persisted; conversation history remains in memory. */
final class PlayerPreferences {
    record Settings(String personaId, boolean privateReply) {
        static final Settings DEFAULT = new Settings("", false);

        Settings {
            if (personaId == null || (!personaId.isEmpty() && !personaId.matches("[a-z0-9_-]{1,32}"))) {
                throw new IllegalArgumentException("Invalid preferred persona ID");
            }
        }
    }

    private static final int MAX_PLAYERS = 4096;
    private static final int MAX_BYTES = 1_048_576;
    private final Path file;
    private Map<UUID, Settings> players = new HashMap<>();

    PlayerPreferences(Path file) throws IOException {
        this.file = file;
        if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) return;
        // Never silently reset a damaged private preference to the public default.
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_BYTES) {
            throw new IOException("Invalid player preferences file");
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.get("schemaVersion").getAsInt() != 1 || !root.get("players").isJsonObject()) {
                throw new IllegalArgumentException("Unsupported preferences format");
            }
            JsonObject entries = root.getAsJsonObject("players");
            if (entries.size() > MAX_PLAYERS) throw new IllegalArgumentException("Too many player preferences");
            for (var entry : entries.entrySet()) {
                UUID playerId = UUID.fromString(entry.getKey());
                JsonObject value = entry.getValue().getAsJsonObject();
                if (!playerId.toString().equals(entry.getKey())
                        || !value.get("personaId").getAsJsonPrimitive().isString()
                        || !value.get("privateReply").getAsJsonPrimitive().isBoolean()) {
                    throw new IllegalArgumentException("Invalid player preferences entry");
                }
                Settings settings = new Settings(value.get("personaId").getAsString(), value.get("privateReply").getAsBoolean());
                if (!settings.equals(Settings.DEFAULT)) players.put(playerId, settings);
            }
        } catch (RuntimeException exception) {
            throw new IOException("Invalid player preferences; refusing to reset visibility", exception);
        }
    }

    synchronized Settings get(UUID playerId) {
        return players.getOrDefault(playerId, Settings.DEFAULT);
    }

    synchronized void set(UUID playerId, Settings settings) throws IOException {
        if (get(playerId).equals(settings)) return;
        Map<UUID, Settings> replacement = new HashMap<>(players);
        if (settings.equals(Settings.DEFAULT)) replacement.remove(playerId);
        else replacement.put(playerId, settings);
        if (replacement.size() > MAX_PLAYERS) throw new IOException("Player preferences capacity exceeded");
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        JsonObject entries = new JsonObject();
        replacement.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            JsonObject value = new JsonObject();
            value.addProperty("personaId", entry.getValue().personaId());
            value.addProperty("privateReply", entry.getValue().privateReply());
            entries.add(entry.getKey().toString(), value);
        });
        root.add("players", entries);
        Files.createDirectories(file.getParent());
        if (Files.isSymbolicLink(file)) throw new IOException("Player preferences must not be a symbolic link");
        Path temporary = Files.createTempFile(file.getParent(), "player-preferences-", ".tmp");
        try {
            Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(root) + "\n", StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            players = replacement;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
