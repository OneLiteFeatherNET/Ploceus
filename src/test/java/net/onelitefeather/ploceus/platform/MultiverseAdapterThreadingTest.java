package net.onelitefeather.ploceus.platform;

import net.onelitefeather.ploceus.domain.PloceusKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Proves the rule the project states everywhere and had stopped enforcing: every Multiverse
/// call happens on the main thread.
///
/// `WorldService.rename` called `MultiverseAdapter.setAlias` from a virtual thread, and the
/// adapter went straight to `worldManager().getWorld(...).setAlias(...)`. No test could see
/// it, because the only test of that path substitutes a `MultiverseAdapter` subclass whose
/// `setAlias` is overridden — the real adapter never ran.
///
/// ## How this asserts it without a server
///
/// The adapter is built with a `null` `MultiverseCoreApi`, which cannot be faked
/// (`MultiverseCoreApi` is `final`), and a scheduler double that knows which thread is
/// "main". Each call then has exactly two possible outcomes, and they are distinguishable:
///
/// - `IllegalStateException` — `MultiverseAdapter.api()` refused, because the call reached
///   Multiverse from the wrong thread. **This is the regression.**
/// - `NullPointerException` — the call reached the API from the main thread and only then
///   fell over the null stand-in. **This is correct behaviour.**
class MultiverseAdapterThreadingTest {

    private final FakeScheduler scheduler = new FakeScheduler();

    @AfterEach
    void tearDown() {
        scheduler.shutdown();
    }

    /// Every public entry point that reaches Multiverse. Each is invoked from the test's own
    /// thread, which is deliberately *not* the fake main thread.
    private Map<String, Runnable> multiverseCalls(MultiverseAdapter adapter) {
        Map<String, Runnable> calls = new LinkedHashMap<>();
        calls.put("snapshotAll", adapter::snapshotAll);
        calls.put("playerCount", () -> adapter.playerCount("elytrarace-alien"));
        calls.put("isLoaded", () -> adapter.isLoaded("elytrarace-alien"));
        calls.put("load", () -> adapter.load("elytrarace-alien"));
        calls.put("unload", () -> adapter.unload("elytrarace-alien"));
        calls.put("setAutoLoad", () -> adapter.setAutoLoad("elytrarace-alien", false));
        calls.put("setAlias", () -> adapter.setAlias("elytrarace-alien", "Alien"));
        calls.put("createWorld", () -> adapter.createWorld(new WorldCreationRequest(
                PloceusKey.of("elytrarace-alien"), "elytrarace-alien", "", "", "NORMAL", null, false, null)));
        calls.put("generators", adapter::generators);
        return calls;
    }

    @TestFactory
    Stream<DynamicTest> everyMultiverseCallHopsToTheMainThread() {
        MultiverseAdapter adapter = new MultiverseAdapter(null, scheduler);
        return multiverseCalls(adapter).entrySet().stream().map(call -> DynamicTest.dynamicTest(
                call.getKey() + " hops", () -> {
                    int before = scheduler.syncCalls.get();
                    assertThatThrownBy(() -> call.getValue().run())
                            .as("%s reached the Multiverse API from %s instead of hopping to the "
                                            + "main thread first", call.getKey(), Thread.currentThread())
                            .hasRootCauseInstanceOf(NullPointerException.class);
                    assertThat(scheduler.syncCalls.get())
                            .as("%s must schedule its Multiverse work onto the main thread", call.getKey())
                            .isGreaterThan(before);
                }));
    }

    /// The other half: a caller that is already on the main thread must not pay for a round
    /// trip. `WorldCreationService` and the reaper both call this adapter from inside a
    /// `scheduler.sync(...)` block and would otherwise deadlock or stall a tick.
    @Test
    void aCallAlreadyOnTheMainThreadIsNotRescheduled() throws Exception {
        MultiverseAdapter adapter = new MultiverseAdapter(null, scheduler);
        scheduler.runOnMainThread(() -> {
            int before = scheduler.syncCalls.get();
            assertThatThrownBy(() -> adapter.setAlias("elytrarace-alien", "Alien"))
                    .isInstanceOf(NullPointerException.class);
            assertThat(scheduler.syncCalls.get()).isEqualTo(before);
        });
    }

    /// A scheduler double with a real, identifiable "main" thread: one single-threaded
    /// executor, whose thread answers `isMainThread`.
    private static final class FakeScheduler extends SchedulerAdapter {

        private final ExecutorService mainThreadExecutor = Executors.newSingleThreadExecutor();
        private final Thread mainThread;
        final AtomicInteger syncCalls = new AtomicInteger();

        FakeScheduler() {
            super(null);
            try {
                mainThread = mainThreadExecutor.submit(Thread::currentThread).get(5, TimeUnit.SECONDS);
            } catch (Exception exception) {
                throw new IllegalStateException("could not start the fake main thread", exception);
            }
        }

        @Override
        public boolean isMainThread() {
            return Thread.currentThread() == mainThread;
        }

        @Override
        public void sync(Runnable work) {
            syncCalls.incrementAndGet();
            if (isMainThread()) {
                work.run();
            } else {
                mainThreadExecutor.execute(work);
            }
        }

        void runOnMainThread(Runnable work) throws Exception {
            mainThreadExecutor.submit(work).get(5, TimeUnit.SECONDS);
        }

        void shutdown() {
            mainThreadExecutor.shutdownNow();
            close();
        }
    }
}
