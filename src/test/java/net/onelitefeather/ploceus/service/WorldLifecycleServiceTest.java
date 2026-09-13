package net.onelitefeather.ploceus.service;

import net.onelitefeather.ploceus.domain.OperationResult;
import net.onelitefeather.ploceus.domain.PloceusKey;
import net.onelitefeather.ploceus.domain.ProjectWorld;
import net.onelitefeather.ploceus.persistence.WorldRepository;
import net.onelitefeather.ploceus.platform.MultiverseAdapter;
import net.onelitefeather.ploceus.platform.SchedulerAdapter;
import net.onelitefeather.ploceus.platform.WorldSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/// Covers the decision logic of the synchronous {@code runReaper} (given a set of
/// snapshots and matching known worlds, it unloads exactly what {@link UnloadPolicy}
/// selects and leaves the rest alone), and the thread hand-off sequence of the
/// production {@code runReaperAsync}.
class WorldLifecycleServiceTest {

    private static final Instant NOW = Instant.now();

    private WorldSnapshot snapshot(String name, boolean loaded, int players) {
        return new WorldSnapshot(name, PloceusKey.fromWorldName(name), loaded, false, "", players);
    }

    private ProjectWorld world(String name, Instant lastVisited) {
        ProjectWorld world = new ProjectWorld(PloceusKey.fromWorldName(name), name);
        world.setLastVisitedAt(lastVisited);
        return world;
    }

    @Test
    void unloadsExactlyTheWorldsThePolicySelects() {
        // idle past its idle time -> unload
        WorldSnapshot pastIdle = snapshot("elytrarace-desert", true, 0);
        ProjectWorld pastIdleWorld = world("elytrarace-desert", NOW.minus(60, ChronoUnit.MINUTES));

        // idle but on the never-unload list -> keep
        WorldSnapshot neverUnload = snapshot("spawn", true, 0);
        ProjectWorld neverUnloadWorld = world("spawn", NOW.minus(60, ChronoUnit.MINUTES));

        // idle but still inside its idle window -> keep
        WorldSnapshot stillFresh = snapshot("hub", true, 0);
        ProjectWorld stillFreshWorld = world("hub", NOW.minus(5, ChronoUnit.MINUTES));

        // occupied -> not even a candidate
        WorldSnapshot occupied = snapshot("arena", true, 3);
        ProjectWorld occupiedWorld = world("arena", NOW.minus(60, ChronoUnit.MINUTES));

        // unknown to Ploceus -> skipped, never touches the policy
        WorldSnapshot unknown = snapshot("unmanaged", true, 0);

        List<WorldSnapshot> snapshots = List.of(pastIdle, neverUnload, stillFresh, occupied, unknown);
        Map<String, ProjectWorld> known = Map.of(
                "elytrarace-desert", pastIdleWorld,
                "spawn", neverUnloadWorld,
                "hub", stillFreshWorld,
                "arena", occupiedWorld);

        List<String> unloadedWorlds = new ArrayList<>();
        MultiverseAdapter multiverse = new MultiverseAdapter(null, null) {
            @Override public List<WorldSnapshot> snapshotAll() {
                return snapshots;
            }
            @Override public OperationResult<Void> unload(String worldName) {
                unloadedWorlds.add(worldName);
                return OperationResult.success(null);
            }
        };

        WorldRepository worlds = new WorldRepository(null) {
            @Override public Optional<ProjectWorld> findByWorldName(String name) {
                return Optional.ofNullable(known.get(name));
            }
        };

        UnloadPolicy policy = new UnloadPolicy(30, Set.of("spawn"), "world");

        WorldLifecycleService service = new WorldLifecycleService(
                multiverse, worlds, null, null, policy, null);

        int unloadedCount = service.runReaper();

        assertThat(unloadedWorlds).containsExactly("elytrarace-desert");
        assertThat(unloadedCount).isEqualTo(1);
    }

    @Test
    void unloadsNothingWhenNoCandidatesAreEligible() {
        WorldSnapshot stillFresh = snapshot("hub", true, 0);
        ProjectWorld stillFreshWorld = world("hub", NOW.minus(5, ChronoUnit.MINUTES));

        List<WorldSnapshot> snapshots = List.of(stillFresh);
        Map<String, ProjectWorld> known = Map.of("hub", stillFreshWorld);

        List<String> unloadedWorlds = new ArrayList<>();
        MultiverseAdapter multiverse = new MultiverseAdapter(null, null) {
            @Override public List<WorldSnapshot> snapshotAll() {
                return snapshots;
            }
            @Override public OperationResult<Void> unload(String worldName) {
                unloadedWorlds.add(worldName);
                return OperationResult.success(null);
            }
        };

        WorldRepository worlds = new WorldRepository(null) {
            @Override public Optional<ProjectWorld> findByWorldName(String name) {
                return Optional.ofNullable(known.get(name));
            }
        };

        UnloadPolicy policy = new UnloadPolicy(30, Set.of(), "world");

        WorldLifecycleService service = new WorldLifecycleService(
                multiverse, worlds, null, null, policy, null);

        assertThat(service.runReaper()).isZero();
        assertThat(unloadedWorlds).isEmpty();
    }

    /// Proves the hop sequence of the production reaper: a snapshot on the main thread,
    /// then a decision handed to the async executor, then the unload handed back to the
    /// main thread. A regression that dropped the final `scheduler.sync` hop — unloading
    /// worlds off the main thread — would fail this test's ordering assertion even though
    /// it would slip past `unloadsExactlyTheWorldsThePolicySelects`, which never touches
    /// `runReaperAsync` at all.
    @Test
    void runReaperAsyncSnapshotsOnMainThreadDecidesAsyncAndUnloadsOnMainThread() {
        List<String> events = new ArrayList<>();

        WorldSnapshot pastIdle = snapshot("elytrarace-desert", true, 0);
        ProjectWorld pastIdleWorld = world("elytrarace-desert", NOW.minus(60, ChronoUnit.MINUTES));
        WorldSnapshot stillFresh = snapshot("hub", true, 0);
        ProjectWorld stillFreshWorld = world("hub", NOW.minus(5, ChronoUnit.MINUTES));

        List<WorldSnapshot> snapshots = List.of(pastIdle, stillFresh);
        List<ProjectWorld> knownWorlds = List.of(pastIdleWorld, stillFreshWorld);

        MultiverseAdapter multiverse = new MultiverseAdapter(null, null) {
            @Override public List<WorldSnapshot> snapshotAll() {
                events.add("snapshotAll");
                return snapshots;
            }
            @Override public OperationResult<Void> unload(String worldName) {
                events.add("unload:" + worldName);
                return OperationResult.success(null);
            }
        };

        WorldRepository worlds = new WorldRepository(null) {
            @Override public List<ProjectWorld> findAll() {
                events.add("findAll");
                return knownWorlds;
            }
        };

        // Records the hand-off order and executes each piece of work immediately, so the
        // test stays deterministic without a real scheduler or thread hop.
        SchedulerAdapter scheduler = new SchedulerAdapter(null) {
            @Override public void async(Runnable work) {
                events.add("async-start");
                work.run();
                events.add("async-end");
            }
            @Override public void sync(Runnable work) {
                events.add("sync-start");
                work.run();
                events.add("sync-end");
            }
        };

        UnloadPolicy policy = new UnloadPolicy(30, Set.of(), "world");

        WorldLifecycleService service = new WorldLifecycleService(
                multiverse, worlds, scheduler, null, policy, null);

        service.runReaperAsync();

        assertThat(events).containsExactly(
                "snapshotAll", "async-start", "findAll", "sync-start",
                "unload:elytrarace-desert", "sync-end", "async-end");
    }
}
