package com.example.multibuild.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.example.multibuild.session.BuildSession;
import com.example.multibuild.web.dto.BuildEventDto;
import jakarta.annotation.PostConstruct;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Component
public class SessionLogAppender extends AppenderBase<ILoggingEvent> {

    private final ConcurrentHashMap<String, BuildSession> activeSessions = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        start();
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        ctx.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(this);
    }

    public void register(String sessionId, BuildSession session) {
        activeSessions.put(sessionId, session);
    }

    public void deregister(String sessionId) {
        activeSessions.remove(sessionId);
    }

    @Override
    protected void append(ILoggingEvent event) {
        String sessionId = event.getMDCPropertyMap().get("sessionId");
        if (sessionId == null) return;

        BuildSession session = activeSessions.get(sessionId);
        if (session == null) return;

        String formatted = event.getFormattedMessage();
        session.appendLogLine(formatted);

        BuildSession.Status status = session.getStatus();
        if (status == BuildSession.Status.RUNNING || status == BuildSession.Status.PENDING) {
            var emitter = session.getEmitter();
            if (emitter != null) {
                emitter.send(new BuildEventDto.LogLine(
                        formatted,
                        event.getLevel().toString(),
                        event.getTimeStamp()
                ));
            }
        }
    }
}
