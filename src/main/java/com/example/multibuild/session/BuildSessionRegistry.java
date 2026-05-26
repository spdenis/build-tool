package com.example.multibuild.session;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class BuildSessionRegistry {

    private final ConcurrentHashMap<String, BuildSession> sessions = new ConcurrentHashMap<>();

    public BuildSession create() {
        String id = UUID.randomUUID().toString();
        BuildSession session = new BuildSession(id);
        sessions.put(id, session);
        return session;
    }

    public Optional<BuildSession> find(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    public List<BuildSession> listRecent() {
        return sessions.values().stream()
                .sorted(Comparator.comparing(BuildSession::getStartedAt).reversed())
                .limit(50)
                .toList();
    }

    @Scheduled(fixedDelay = 1800000)
    public void evictExpired() {
        Instant cutoff = Instant.now().minus(2, ChronoUnit.HOURS);
        sessions.values().removeIf(s -> s.getStartedAt().isBefore(cutoff));
    }
}
