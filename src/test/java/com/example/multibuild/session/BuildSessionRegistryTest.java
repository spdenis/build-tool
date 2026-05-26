package com.example.multibuild.session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class BuildSessionRegistryTest {

    private BuildSessionRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new BuildSessionRegistry();
    }

    @Test
    void create_returnsSessionWithUniqueId() {
        BuildSession first = registry.create();
        BuildSession second = registry.create();

        assertThat(first.getId()).isNotBlank();
        assertThat(second.getId()).isNotBlank();
        assertThat(first.getId()).isNotEqualTo(second.getId());
    }

    @Test
    void find_existingSession_returnsIt() {
        BuildSession created = registry.create();

        Optional<BuildSession> found = registry.find(created.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(created.getId());
    }

    @Test
    void find_unknownId_returnsEmpty() {
        Optional<BuildSession> result = registry.find("non-existent-id");

        assertThat(result).isEmpty();
    }

    @Test
    void listRecent_multipleCreated_newestFirst() throws InterruptedException {
        BuildSession first = registry.create();
        // Small sleep to ensure distinct timestamps
        Thread.sleep(5);
        BuildSession second = registry.create();
        Thread.sleep(5);
        BuildSession third = registry.create();

        List<BuildSession> recent = registry.listRecent();

        assertThat(recent).hasSize(3);
        assertThat(recent.get(0).getId()).isEqualTo(third.getId());
        assertThat(recent.get(1).getId()).isEqualTo(second.getId());
        assertThat(recent.get(2).getId()).isEqualTo(first.getId());
    }

    @Test
    @SuppressWarnings("unchecked")
    void evictExpired_oldSession_removed() {
        BuildSession session = registry.create();
        String sessionId = session.getId();

        // Set startedAt to 3 hours ago via reflection on the internal sessions map
        ConcurrentHashMap<String, BuildSession> sessions =
                (ConcurrentHashMap<String, BuildSession>) ReflectionTestUtils.getField(registry, "sessions");
        // Replace the session with a new one that has an old timestamp
        BuildSession oldSession = new BuildSession(sessionId) {
            @Override
            public Instant getStartedAt() {
                return Instant.now().minus(3, ChronoUnit.HOURS);
            }
        };
        sessions.put(sessionId, oldSession);

        registry.evictExpired();

        assertThat(registry.find(sessionId)).isEmpty();
    }
}
