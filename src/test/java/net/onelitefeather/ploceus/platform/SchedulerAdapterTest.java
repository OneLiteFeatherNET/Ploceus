package net.onelitefeather.ploceus.platform;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    /// On the main thread `onMainThread` must not schedule anything: `sync` already runs
    /// inline there, and the callers that matter (`WorldCreationService`, the reaper) are
    /// inside a `sync` block when they reach it.
    @Test
    void onMainThreadRunsInlineWhenAlreadyOnTheMainThread() {
        AtomicBoolean scheduled = new AtomicBoolean(false);
        SchedulerAdapter scheduler = new SchedulerAdapter(null) {
            @Override public boolean isMainThread() { return true; }
            @Override public void sync(Runnable work) { scheduled.set(true); work.run(); }
        };

        assertThat(scheduler.onMainThread(() -> "value")).isEqualTo("value");
        assertThat(scheduled).isFalse();
        scheduler.close();
    }

    /// Off the main thread the caller blocks on the hop — and gets the real exception back,
    /// rather than losing it on the main thread while the call silently returns null.
    @Test
    void onMainThreadHopsAndRethrowsWhatTheWorkThrew() {
        SchedulerAdapter scheduler = new SchedulerAdapter(null) {
            @Override public boolean isMainThread() { return false; }
            @Override public void sync(Runnable work) { Thread.ofVirtual().start(work); }
        };

        assertThatThrownBy(() -> scheduler.onMainThread(() -> { throw new IllegalArgumentException("boom"); }))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
        scheduler.close();
    }

    @Test
    void requireMainThreadFailsOffTheMainThread() {
        SchedulerAdapter scheduler = new SchedulerAdapter(null) {
            @Override public boolean isMainThread() { return false; }
        };

        assertThatThrownBy(() -> scheduler.requireMainThread("the Multiverse API"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("the Multiverse API");
        scheduler.close();
    }
}
