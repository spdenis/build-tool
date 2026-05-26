package com.example.multibuild.session;

import com.example.multibuild.web.sse.BuildEventEmitter;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class BuildSession {

    public enum Status {
        PENDING, RUNNING, SUCCESS, FAILED, CANCELLED
    }

    private static final int MAX_LOG_LINES = 5000;

    private final String id;
    private final Instant startedAt;
    private volatile Status status;
    private volatile String errorMessage;
    private final CopyOnWriteArrayList<String> logLines = new CopyOnWriteArrayList<>();
    private volatile BuildEventEmitter emitter;
    private volatile Thread executionThread;

    public BuildSession(String id) {
        this.id = id;
        this.startedAt = Instant.now();
        this.status = Status.PENDING;
    }

    public String getId() { return id; }

    public Instant getStartedAt() { return startedAt; }

    public Status getStatus() { return status; }

    public void setStatus(Status status) { this.status = status; }

    public String getErrorMessage() { return errorMessage; }

    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public List<String> getLogLines() { return logLines; }

    public void appendLogLine(String line) {
        logLines.add(line);
        if (logLines.size() > MAX_LOG_LINES) {
            logLines.remove(0);
        }
    }

    public BuildEventEmitter getEmitter() { return emitter; }

    public void setEmitter(BuildEventEmitter emitter) { this.emitter = emitter; }

    public void setExecutionThread(Thread thread) { this.executionThread = thread; }

    public void cancel() {
        Thread t = executionThread;
        if (t != null) {
            t.interrupt();
        }
        status = Status.CANCELLED;
    }
}
