package com.example.multibuild.session;

import org.junit.jupiter.api.Test;

import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class BuildSessionTest {

    @Test
    void newSession_initialStatus_isPending() {
        BuildSession session = new BuildSession("test-id");

        assertThat(session.getId()).isEqualTo("test-id");
        assertThat(session.getStatus()).isEqualTo(BuildSession.Status.PENDING);
        assertThat(session.getLogLines()).isEmpty();
        assertThat(session.getErrorMessage()).isNull();
    }

    @Test
    void appendLogLine_underLimit_retainsAll() {
        BuildSession session = new BuildSession("test-id");

        session.appendLogLine("line1");
        session.appendLogLine("line2");
        session.appendLogLine("line3");

        assertThat(session.getLogLines()).containsExactly("line1", "line2", "line3");
    }

    @Test
    void appendLogLine_overLimit_trimsToLimit() {
        BuildSession session = new BuildSession("test-id");

        IntStream.rangeClosed(1, 5001).forEach(i -> session.appendLogLine("line" + i));

        assertThat(session.getLogLines()).hasSize(5000);
        assertThat(session.getLogLines().get(0)).isEqualTo("line2");
        assertThat(session.getLogLines().get(4999)).isEqualTo("line5001");
    }

    @Test
    void cancel_whileRunning_setsStatusCancelled() throws InterruptedException {
        BuildSession session = new BuildSession("test-id");
        session.setStatus(BuildSession.Status.RUNNING);

        // Thread blocks on a latch and captures its interrupt flag before exiting
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch interrupted = new java.util.concurrent.CountDownLatch(1);
        Thread thread = new Thread(() -> {
            started.countDown();
            try {
                // Park indefinitely; interrupted by cancel()
                Thread.sleep(Long.MAX_VALUE);
            } catch (InterruptedException e) {
                interrupted.countDown();
                // do NOT re-interrupt so we can verify cancel() did the interrupt
            }
        });
        thread.setDaemon(true);
        thread.start();
        started.await(1, java.util.concurrent.TimeUnit.SECONDS);

        session.setExecutionThread(thread);
        session.cancel();

        assertThat(session.getStatus()).isEqualTo(BuildSession.Status.CANCELLED);
        // Thread was interrupted — wait briefly for it to respond
        assertThat(interrupted.await(1, java.util.concurrent.TimeUnit.SECONDS))
                .as("thread should have been interrupted by cancel()")
                .isTrue();
    }

    @Test
    void cancel_whenNotRunning_setsStatusCancelled() {
        BuildSession session = new BuildSession("test-id");

        assertThatCode(session::cancel).doesNotThrowAnyException();
        assertThat(session.getStatus()).isEqualTo(BuildSession.Status.CANCELLED);
    }
}
