package com.example.multibuild.web.sse;

import com.example.multibuild.event.BuildProgressEvent;
import com.example.multibuild.session.BuildSessionRegistry;
import com.example.multibuild.web.dto.BuildEventDto;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class BuildEventBroadcaster {

    private final BuildSessionRegistry registry;

    public BuildEventBroadcaster(BuildSessionRegistry registry) {
        this.registry = registry;
    }

    @EventListener
    public void onBuildProgress(BuildProgressEvent event) {
        registry.find(event.sessionId()).ifPresent(session -> {
            BuildEventEmitter emitter = session.getEmitter();
            if (emitter == null) return;
            emitter.send(event.payload());
            if (event.payload() instanceof BuildEventDto.BuildFinished) {
                session.setEmitter(null);
                emitter.complete();
            }
        });
    }
}
