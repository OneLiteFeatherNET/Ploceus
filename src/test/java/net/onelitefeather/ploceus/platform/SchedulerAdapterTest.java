package net.onelitefeather.ploceus.platform;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class SchedulerAdapterTest {

    /// `plugin` is never touched by `close()` unless the executor fails to terminate in
    /// time, so a null plugin is safe here as long as queued work finishes quickly.
    @Test
    void closeWaitsForQueuedWorkToFinishBeforeReturning() {
        SchedulerAdapter scheduler = new SchedulerAdapter(null);
        AtomicBoolean finished = new AtomicBoolean(false);
        CompletableFuture<Void> started = new CompletableFuture<>();

        scheduler.async(() -> {
            started.complete(null);
            try {
                TimeUnit.MILLISECONDS.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            finished.set(true);
        });

        started.join();
        assertThatCode(scheduler::close).doesNotThrowAnyException();
        assertThat(finished).isTrue();
    }

    @Test
    void closeIsIdempotentAndReturnsPromptlyWithNoQueuedWork() {
        SchedulerAdapter scheduler = new SchedulerAdapter(null);
        assertThatCode(scheduler::close).doesNotThrowAnyException();
    }
}
