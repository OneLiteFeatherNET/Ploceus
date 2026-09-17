package net.onelitefeather.ploceus.ui;

import net.onelitefeather.ploceus.PloceusConfig;
import net.onelitefeather.ploceus.domain.AuditEntry;
import net.onelitefeather.ploceus.domain.OperationResult;
import net.onelitefeather.ploceus.domain.PloceusKey;
import net.onelitefeather.ploceus.domain.Project;
import net.onelitefeather.ploceus.domain.ProjectWorld;
import net.onelitefeather.ploceus.platform.SchedulerAdapter;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class UiRouterTest {

    private static final int MIN_DIALOG_PROTOCOL = 771;

    @Test
    void aModernClientGetsTheDialogChannel() {
        assertThat(UiRouter.choose(772, Optional.empty(), MIN_DIALOG_PROTOCOL))
                .isEqualTo(UiChannel.DIALOG);
    }

    @Test
    void aClientExactlyAtTheThresholdGetsTheDialogChannel() {
        assertThat(UiRouter.choose(771, Optional.empty(), MIN_DIALOG_PROTOCOL))
                .isEqualTo(UiChannel.DIALOG);
    }

    @Test
    void anOlderClientFallsBackToTheInventory() {
        assertThat(UiRouter.choose(770, Optional.empty(), MIN_DIALOG_PROTOCOL))
                .isEqualTo(UiChannel.INVENTORY);
    }

    @Test
    void anExplicitPreferenceBeatsTheProtocolVersion() {
        assertThat(UiRouter.choose(772, Optional.of(UiChannel.CHAT), MIN_DIALOG_PROTOCOL))
                .isEqualTo(UiChannel.CHAT);
        assertThat(UiRouter.choose(700, Optional.of(UiChannel.DIALOG), MIN_DIALOG_PROTOCOL))
                .isEqualTo(UiChannel.DIALOG);
    }

    /// A cache miss must answer from the protocol default right away, without waiting on a
    /// repository read. Uses a scheduler double that captures the async work instead of
    /// running it, so any synchronous call to `preferences.find` inside `resolve` itself
    /// (rather than inside the captured work) would fail the zero-calls assertion.
    @Test
    void resolveAnswersImmediatelyOnACacheMissWithoutCallingPreferencesSynchronously() {
        NoopUi dialogUi = new NoopUi();
        NoopUi inventoryUi = new NoopUi();
        Map<UiChannel, ProjectUi> channels = Map.of(UiChannel.DIALOG, dialogUi, UiChannel.INVENTORY, inventoryUi);
        RecordingPreferences preferences = new RecordingPreferences(new HashMap<>());
        CapturingScheduler scheduler = new CapturingScheduler();
        UiRouter router = new UiRouter(channels, preferences, config(MIN_DIALOG_PROTOCOL), scheduler);

        ProjectUi result = router.resolve(UUID.randomUUID(), 772);

        assertThat(result).isSameAs(dialogUi);
        assertThat(preferences.findCalls.get()).isZero();
        assertThat(scheduler.captured).isNotNull();
    }

    /// Once the (captured, manually-run) async lookup completes, the cache is warmed with
    /// the stored preference, and a subsequent call is served straight from the cache
    /// without a second repository read.
    @Test
    void resolvePopulatesTheCacheOnceTheAsyncLookupRuns() {
        NoopUi dialogUi = new NoopUi();
        NoopUi chatUi = new NoopUi();
        Map<UiChannel, ProjectUi> channels = Map.of(
                UiChannel.DIALOG, dialogUi, UiChannel.INVENTORY, new NoopUi(), UiChannel.CHAT, chatUi);
        UUID uuid = UUID.randomUUID();
        Map<UUID, UiChannel> stored = new HashMap<>();
        stored.put(uuid, UiChannel.CHAT);
        RecordingPreferences preferences = new RecordingPreferences(stored);
        CapturingScheduler scheduler = new CapturingScheduler();
        UiRouter router = new UiRouter(channels, preferences, config(MIN_DIALOG_PROTOCOL), scheduler);

        ProjectUi beforeWarming = router.resolve(uuid, 772);
        assertThat(beforeWarming).isSameAs(dialogUi);

        scheduler.runCaptured();

        assertThat(preferences.findCalls.get()).isEqualTo(1);
        ProjectUi afterWarming = router.resolve(uuid, 772);
        assertThat(afterWarming).isSameAs(chatUi);
        assertThat(preferences.findCalls.get()).isEqualTo(1);
    }

    /// The defect `UiRouterListener.onJoin` closes: a stored preference was ignored on the
    /// first command after every login, because the cache is emptied on quit and `resolve`
    /// answers a miss from the protocol default. Warming at join means the very first
    /// `resolve` already honours the preference.
    @Test
    void warmingOnJoinMakesTheFirstResolveHonourTheStoredPreference() {
        NoopUi dialogUi = new NoopUi();
        NoopUi chatUi = new NoopUi();
        Map<UiChannel, ProjectUi> channels = Map.of(
                UiChannel.DIALOG, dialogUi, UiChannel.INVENTORY, new NoopUi(), UiChannel.CHAT, chatUi);
        UUID uuid = UUID.randomUUID();
        Map<UUID, UiChannel> stored = new HashMap<>();
        stored.put(uuid, UiChannel.CHAT);
        RecordingPreferences preferences = new RecordingPreferences(stored);
        CapturingScheduler scheduler = new CapturingScheduler();
        UiRouter router = new UiRouter(channels, preferences, config(MIN_DIALOG_PROTOCOL), scheduler);

        router.warm(uuid, 772);
        assertThat(preferences.findCalls.get())
                .as("warming must not block the join event on a database read").isZero();
        scheduler.runCaptured();

        assertThat(router.resolve(uuid, 772))
                .as("a player who chose chat must not be handed a dialog on their first command")
                .isSameAs(chatUi);
    }

    /// A preference set between the join warming being scheduled and it completing must not
    /// be clobbered by the older stored value.
    @Test
    void aPreferenceSetWhileWarmingIsStillPendingWins() {
        NoopUi inventoryUi = new NoopUi();
        NoopUi chatUi = new NoopUi();
        Map<UiChannel, ProjectUi> channels = Map.of(
                UiChannel.DIALOG, new NoopUi(), UiChannel.INVENTORY, inventoryUi, UiChannel.CHAT, chatUi);
        UUID uuid = UUID.randomUUID();
        Map<UUID, UiChannel> stored = new HashMap<>();
        stored.put(uuid, UiChannel.CHAT);
        // Queues every task rather than keeping only the last one, so the warming lookup and
        // the write-through of setPreference can both be pending at the same time.
        java.util.List<Runnable> queued = new java.util.ArrayList<>();
        SchedulerAdapter scheduler = new SchedulerAdapter(null) {
            @Override public void async(Runnable work) { queued.add(work); }
        };
        UiRouter router = new UiRouter(channels, new RecordingPreferences(stored),
                config(MIN_DIALOG_PROTOCOL), scheduler);

        router.warm(uuid, 772);
        router.setPreference(uuid, UiChannel.INVENTORY);
        queued.forEach(Runnable::run);
        scheduler.close();

        assertThat(router.resolve(uuid, 772)).isSameAs(inventoryUi);
    }

    /// End-to-end with a real `SchedulerAdapter` (its `async` is unaffected by a `null`
    /// plugin) rather than a same-thread double: proves the repository read genuinely
    /// happens off the thread that called `resolve`.
    @Test
    void resolveReadsTheStoredPreferenceOffTheCallingThread() throws InterruptedException {
        Thread callingThread = Thread.currentThread();
        ArrayBlockingQueue<Thread> lookupThread = new ArrayBlockingQueue<>(1);
        UUID uuid = UUID.randomUUID();
        UiPreferences preferences = new UiPreferences() {
            @Override public Optional<UiChannel> find(UUID id) {
                lookupThread.add(Thread.currentThread());
                return Optional.of(UiChannel.CHAT);
            }
            @Override public void save(UUID id, UiChannel channel) { }
        };
        NoopUi dialogUi = new NoopUi();
        Map<UiChannel, ProjectUi> channels = Map.of(
                UiChannel.DIALOG, dialogUi, UiChannel.INVENTORY, new NoopUi(), UiChannel.CHAT, new NoopUi());
        SchedulerAdapter scheduler = new SchedulerAdapter(null);
        UiRouter router = new UiRouter(channels, preferences, config(MIN_DIALOG_PROTOCOL), scheduler);

        try {
            ProjectUi immediate = router.resolve(uuid, 772);
            assertThat(immediate).isSameAs(dialogUi);

            Thread threadThatCalledFind = lookupThread.poll(2, TimeUnit.SECONDS);
            assertThat(threadThatCalledFind).isNotNull().isNotSameAs(callingThread);
        } finally {
            scheduler.close();
        }
    }

    /// `setPreference` must update the cache before returning (so the very next call on
    /// this thread sees it) and must not write to storage inline.
    @Test
    void setPreferenceUpdatesTheCacheImmediatelyAndWritesThroughAsynchronously() {
        java.util.List<String> events = new java.util.ArrayList<>();
        SchedulerAdapter scheduler = new SchedulerAdapter(null) {
            @Override public void async(Runnable work) {
                events.add("async-start");
                work.run();
                events.add("async-end");
            }
        };
        Map<UUID, UiChannel> stored = new HashMap<>();
        UiPreferences preferences = new UiPreferences() {
            @Override public Optional<UiChannel> find(UUID id) {
                return Optional.ofNullable(stored.get(id));
            }
            @Override public void save(UUID id, UiChannel channel) {
                events.add("save:" + channel);
                stored.put(id, channel);
            }
        };
        NoopUi chatUi = new NoopUi();
        Map<UiChannel, ProjectUi> channels = Map.of(UiChannel.CHAT, chatUi);
        UiRouter router = new UiRouter(channels, preferences, config(MIN_DIALOG_PROTOCOL), scheduler);
        UUID uuid = UUID.randomUUID();

        router.setPreference(uuid, UiChannel.CHAT);

        assertThat(router.resolve(uuid, 700)).isSameAs(chatUi);
        assertThat(events).containsExactly("async-start", "save:CHAT", "async-end");
    }

    private static PloceusConfig config(int minDialogProtocol) {
        return new PloceusConfig(null, 30, 60, 30, List.of(), null, minDialogProtocol, 10);
    }

    /// Records how many times `find` was called, without needing a real database. The
    /// separate thread-of-call check lives in {@code resolveReadsTheStoredPreferenceOffTheCallingThread},
    /// which uses its own inline `UiPreferences` and a real `SchedulerAdapter`.
    private static final class RecordingPreferences implements UiPreferences {
        private final Map<UUID, UiChannel> stored;
        private final AtomicInteger findCalls = new AtomicInteger();

        RecordingPreferences(Map<UUID, UiChannel> stored) {
            this.stored = stored;
        }

        @Override
        public Optional<UiChannel> find(UUID playerUuid) {
            findCalls.incrementAndGet();
            return Optional.ofNullable(stored.get(playerUuid));
        }

        @Override
        public void save(UUID playerUuid, UiChannel channel) {
            stored.put(playerUuid, channel);
        }
    }

    /// A `SchedulerAdapter` double that captures the work passed to `async` instead of
    /// running it, so a test can assert nothing happened synchronously and then run the
    /// captured work itself to simulate the scheduler completing it later.
    private static final class CapturingScheduler extends SchedulerAdapter {
        private Runnable captured;

        CapturingScheduler() {
            super(null);
        }

        @Override
        public void async(Runnable work) {
            captured = work;
        }

        void runCaptured() {
            captured.run();
        }
    }

    /// A `ProjectUi` that does nothing; only used so distinct instances can be told apart
    /// by identity in `Map<UiChannel, ProjectUi>` assertions.
    private static final class NoopUi implements ProjectUi {
        @Override public void mainMenu(Player player) { }
        @Override public void projectList(Player player, Page<Project> page) { }
        @Override public void projectDetail(Player player, Project project, List<ProjectWorld> worlds) { }
        @Override public void worldList(Player player, Project project, Page<ProjectWorld> page) { }
        @Override public void unassignedWorlds(Player player, Page<ProjectWorld> page) { }
        @Override public void importableWorlds(Player player, Page<String> page) { }
        @Override public void searchPrompt(Player player) { }
        @Override public void searchResults(Player player, String term, Page<Project> projects,
                                            Page<ProjectWorld> worlds) { }
        @Override public void createProjectForm(Player player) { }
        @Override public void editProjectForm(Player player, Project project) { }
        @Override public void createWorldForm(Player player, Project project, String suggestedName,
                                              List<String> generators) { }
        @Override public void auditTrail(Player player, PloceusKey target, Page<AuditEntry> entries) { }
        @Override public void confirm(Player player, String messageKey, Runnable onConfirm) { }
        @Override public void feedback(Player player, OperationResult<?> result, String successKey) { }
    }
}
