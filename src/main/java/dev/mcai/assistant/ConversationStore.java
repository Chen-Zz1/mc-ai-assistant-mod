package dev.mcai.assistant;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class ConversationStore {
    record Exchange(String question, String answer) {
    }

    record Key(UUID playerId, String personaId, boolean privateReply) {
        static Key ordinary(UUID playerId) {
            return new Key(playerId, "", false);
        }
    }

    private static final class Session {
        private final ArrayDeque<Exchange> exchanges = new ArrayDeque<>();
        private Instant lastActive;
    }

    private final Map<Key, Session> sessions = new HashMap<>();
    private final Clock clock;

    ConversationStore(Clock clock) {
        this.clock = clock;
    }

    synchronized List<Exchange> history(UUID playerId, int maxTurns, Duration idleTimeout) {
        return history(Key.ordinary(playerId), maxTurns, idleTimeout);
    }

    synchronized List<Exchange> history(Key key, int maxTurns, Duration idleTimeout) {
        pruneExpired(idleTimeout);
        Session session = sessions.get(key);
        if (session == null) {
            return List.of();
        }
        while (session.exchanges.size() > maxTurns) {
            session.exchanges.removeFirst();
        }
        session.lastActive = clock.instant();
        return List.copyOf(session.exchanges);
    }

    synchronized void record(UUID playerId, String question, String answer, int maxTurns) {
        record(Key.ordinary(playerId), question, answer, maxTurns);
    }

    synchronized void record(Key key, String question, String answer, int maxTurns) {
        if (maxTurns == 0) {
            sessions.remove(key);
            return;
        }
        if (!key.personaId().isEmpty() && !sessions.containsKey(key)) {
            var existing = sessions.entrySet().stream()
                    .filter(entry -> entry.getKey().playerId().equals(key.playerId()) && !entry.getKey().personaId().isEmpty())
                    .sorted(java.util.Comparator.<Map.Entry<Key, Session>, Instant>comparing(entry -> entry.getValue().lastActive)
                            .thenComparing(entry -> entry.getKey().personaId())
                            .thenComparing(entry -> entry.getKey().privateReply())).toList();
            for (int index = 0; index <= existing.size() - 4; index++) {
                sessions.remove(existing.get(index).getKey());
            }
        }
        Session session = sessions.computeIfAbsent(key, ignored -> new Session());
        session.exchanges.addLast(new Exchange(question, answer));
        while (session.exchanges.size() > maxTurns) {
            session.exchanges.removeFirst();
        }
        session.lastActive = clock.instant();
    }

    synchronized void clear(UUID playerId) {
        sessions.keySet().removeIf(key -> key.playerId().equals(playerId));
    }

    synchronized void clearPersona(String personaId) {
        sessions.keySet().removeIf(key -> key.personaId().equals(personaId));
    }

    synchronized int personaSessionCount() {
        return (int) sessions.keySet().stream().filter(key -> !key.personaId().isEmpty()).count();
    }

    synchronized void pruneExpired(Duration idleTimeout) {
        Instant cutoff = clock.instant().minus(idleTimeout);
        sessions.values().removeIf(session -> !session.lastActive.isAfter(cutoff));
    }

    synchronized int clearAll() {
        int count = activePlayers().size();
        sessions.clear();
        return count;
    }

    synchronized List<UUID> activePlayers() {
        return sessions.keySet().stream().map(Key::playerId).distinct().toList();
    }
}
