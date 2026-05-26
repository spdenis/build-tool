package com.example.multibuild.web.sse;

import com.example.multibuild.session.BuildSession;
import com.example.multibuild.session.BuildSessionRegistry;
import com.example.multibuild.web.dto.BuildEventDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/sessions")
public class SseController {

    private final BuildSessionRegistry registry;
    private final ObjectMapper objectMapper;

    public SseController(BuildSessionRegistry registry, ObjectMapper objectMapper) {
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    @GetMapping(value = "/{sessionId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> streamEvents(@PathVariable String sessionId) {
        BuildSession session = registry.find(sessionId).orElse(null);
        if (session == null) {
            return ResponseEntity.notFound().build();
        }

        BuildEventEmitter emitter = new BuildEventEmitter(1800000L, objectMapper);
        session.setEmitter(emitter);

        BuildSession.Status status = session.getStatus();
        if (status == BuildSession.Status.SUCCESS || status == BuildSession.Status.FAILED) {
            emitter.send(new BuildEventDto.BuildFinished(
                    status == BuildSession.Status.SUCCESS,
                    session.getErrorMessage(),
                    0L
            ));
            emitter.complete();
        } else {
            SseEmitter raw = emitter.raw();
            raw.onCompletion(() -> session.setEmitter(null));
            raw.onTimeout(() -> session.setEmitter(null));
        }

        return ResponseEntity.ok(emitter.raw());
    }
}
