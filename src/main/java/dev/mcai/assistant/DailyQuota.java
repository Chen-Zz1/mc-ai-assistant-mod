package dev.mcai.assistant;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

final class DailyQuota {
    record Snapshot(LocalDate date, int used, int limit) {
    }

    private static final Gson GSON = new Gson();

    private final Path stateFile;
    private final int limit;
    private final ZoneId zoneId;
    private final Clock clock;
    private LocalDate date;
    private int used;

    DailyQuota(Path stateFile, int limit, ZoneId zoneId, Clock clock) throws IOException {
        this.stateFile = stateFile;
        this.limit = limit;
        this.zoneId = zoneId;
        this.clock = clock;
        load();
        rollDateIfNeeded();
    }

    synchronized boolean tryAcquire() throws IOException {
        LocalDate candidateDate = LocalDate.now(clock.withZone(zoneId));
        int candidateUsed = candidateDate.equals(date) ? used : 0;
        if (candidateUsed >= limit) {
            return false;
        }
        persist(candidateDate, candidateUsed + 1);
        date = candidateDate;
        used = candidateUsed + 1;
        return true;
    }

    synchronized Snapshot snapshot() {
        rollDateIfNeeded();
        return new Snapshot(date, used, limit);
    }

    private void load() throws IOException {
        date = LocalDate.now(clock.withZone(zoneId));
        used = 0;
        if (Files.notExists(stateFile)) {
            return;
        }
        try {
            State state = GSON.fromJson(Files.readString(stateFile, StandardCharsets.UTF_8), State.class);
            if (state == null || state.date == null || state.used < 0) {
                throw new IOException("Invalid daily quota state");
            }
            date = LocalDate.parse(state.date);
            used = state.used;
        } catch (JsonSyntaxException | IllegalArgumentException exception) {
            throw new IOException("Invalid daily quota state", exception);
        }
    }

    private void rollDateIfNeeded() {
        LocalDate today = LocalDate.now(clock.withZone(zoneId));
        if (!today.equals(date)) {
            date = today;
            used = 0;
        }
    }

    private void persist(LocalDate candidateDate, int candidateUsed) throws IOException {
        Files.createDirectories(stateFile.getParent());
        Path temporary = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
        try {
            Files.writeString(temporary, GSON.toJson(new State(candidateDate.toString(), candidateUsed)),
                    StandardCharsets.UTF_8);
            try {
                Files.move(temporary, stateFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, stateFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
    }

    private record State(String date, int used) {
    }
}
