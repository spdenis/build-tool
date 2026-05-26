package com.example.multibuild.web.sse;

import com.example.multibuild.web.dto.BuildEventDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

public class BuildEventEmitter {

    private final SseEmitter delegate;
    private final ObjectMapper objectMapper;

    public BuildEventEmitter(long timeoutMs, ObjectMapper objectMapper) {
        this.delegate = new SseEmitter(timeoutMs);
        this.objectMapper = objectMapper;
    }

    public void send(BuildEventDto event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            delegate.send(SseEmitter.event().data(json));
        } catch (IOException ignored) {
        }
    }

    public void complete() {
        delegate.complete();
    }

    public SseEmitter raw() {
        return delegate;
    }
}
