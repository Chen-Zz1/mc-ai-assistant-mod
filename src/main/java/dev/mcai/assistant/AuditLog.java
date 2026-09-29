package dev.mcai.assistant;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

final class AuditLog {
    private static final Gson GSON = new Gson();

    private final Path directory;
    private final ZoneId zoneId;
    private final int retentionDays;
    private final boolean enabled;
    private final Clock clock;

    AuditLog(Path directory, ZoneId zoneId, int retentionDays, boolean enabled, Clock clock) {
        this.directory = directory;
        this.zoneId = zoneId;
        this.retentionDays = retentionDays;
        this.enabled = enabled;
        this.clock = clock;
    }

    synchronized void append(UUID playerId, String playerName, String question, String answer,
                             boolean search, boolean privateReply, String model, String status,
                             long elapsedMillis, String retrieval) throws IOException {
        append(playerId, playerName, question, answer, search, privateReply, model, status,
                elapsedMillis, retrieval, null, null, null);
    }

    synchronized void append(UUID playerId, String playerName, String question, String answer,
                             boolean search, boolean privateReply, String model, String status,
                             long elapsedMillis, String retrieval, JsonObject wikiDiagnostics,
                             String personaId, String personaVersion) throws IOException {
        if (!enabled) {
            return;
        }
        Files.createDirectories(directory);
        LocalDate today = LocalDate.now(clock.withZone(zoneId));
        Path file = directory.resolve("conversations-" + today + ".jsonl");
        Entry entry = new Entry(clock.instant().toString(), playerId.toString(), playerName, question, answer,
                search, privateReply, model, status, elapsedMillis, retrieval, wikiDiagnostics, personaId, personaVersion);
        Files.writeString(file, GSON.toJson(entry) + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        deleteExpiredFiles();
    }

    private void deleteExpiredFiles() throws IOException {
        Instant cutoff = clock.instant().minus(Duration.ofDays(retentionDays));
        try (var files = Files.list(directory)) {
            files.filter(path -> path.getFileName().toString().matches("conversations-\\d{4}-\\d{2}-\\d{2}\\.jsonl"))
                    .filter(path -> {
                        try {
                            return Files.getLastModifiedTime(path).toInstant().isBefore(cutoff);
                        } catch (IOException exception) {
                            return false;
                        }
                    })
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException exception) {
                            MinecraftAiAssistant.LOGGER.warn("Unable to remove expired AI audit log {}", path.getFileName());
                        }
                    });
        }
    }

    private record Entry(String timestamp, String playerId, String playerName, String question, String answer,
                         boolean search, boolean privateReply, String model, String status, long elapsedMillis,
                         String retrieval, JsonObject wikiDiagnostics, String personaId, String personaVersion) {
    }
}
