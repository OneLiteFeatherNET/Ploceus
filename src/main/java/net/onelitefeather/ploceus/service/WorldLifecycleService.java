package net.onelitefeather.ploceus.service;

import net.onelitefeather.ploceus.PloceusConfig;
import net.onelitefeather.ploceus.domain.OperationResult;
import net.onelitefeather.ploceus.domain.ProjectWorld;
import net.onelitefeather.ploceus.persistence.WorldRepository;
import net.onelitefeather.ploceus.platform.MultiverseAdapter;
import net.onelitefeather.ploceus.platform.SchedulerAdapter;
import net.onelitefeather.ploceus.platform.Telemetry;
import net.onelitefeather.ploceus.platform.WorldSnapshot;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class WorldLifecycleService implements AutoCloseable {

    /// Looked up fresh rather than cached - see {@link Telemetry}.
    private static LongCounter worldsUnloadedByReaperCounter() {
        return Telemetry.meter().counterBuilder("ploceus.reaper.worlds_unloaded")
                .setDescription("Worlds unloaded by the idle-world reaper, cumulative across passes")
                .setUnit("{world}")
                .build();
    }

    private final MultiverseAdapter multiverse;
    private final WorldRepository worlds;
    private final SchedulerAdapter scheduler;
    private final VisitTracker visits;
    private final UnloadPolicy policy;
    private final PloceusConfig config;
    private AutoCloseable reaperTask;

    public WorldLifecycleService(MultiverseAdapter multiverse, WorldRepository worlds,
                                 SchedulerAdapter scheduler, VisitTracker visits,
                                 UnloadPolicy policy, PloceusConfig config) {
        this.multiverse = multiverse;
        this.worlds = worlds;
        this.scheduler = scheduler;
        this.visits = visits;
        this.policy = policy;
        this.config = config;
    }

    /// Main thread only. Loading an already-loaded world is a no-op success.
    public OperationResult<Void> ensureLoaded(String worldName) {
        return multiverse.isLoaded(worldName)
                ? OperationResult.success(null)
                : multiverse.load(worldName);
    }

    public CompletableFuture<OperationResult<Void>> teleport(Player player, ProjectWorld world) {
        visits.recordVisit(world.getWorldName(), Instant.now());
        return multiverse.teleport(player, world.getWorldName());
    }

    /// Returns how many worlds were unloaded. Synchronous decision logic, kept for tests
    /// only — it calls {@code worlds.findByWorldName}, a database access, which must never
    /// happen on the main thread. Production code uses {@link #runReaperAsync()} instead.
    int runReaper() {
        List<WorldSnapshot> snapshots = multiverse.snapshotAll();
        Instant now = Instant.now();
        int unloaded = 0;
        List<String> candidates = new ArrayList<>();

        for (WorldSnapshot snapshot : snapshots) {
            if (!snapshot.isIdle()) {
                continue;
            }
            Optional<ProjectWorld> known = worlds.findByWorldName(snapshot.worldName());
            if (known.isPresent() && policy.shouldUnload(snapshot, known.get(), now)) {
                candidates.add(snapshot.worldName());
            }
        }
        for (String worldName : candidates) {
            if (multiverse.unload(worldName).isSuccess()) {
                unloaded++;
            }
        }
        return unloaded;
    }

    /// Snapshots on the main thread, decides on the async executor (the only place the
    /// database may be touched), then hops back to the main thread to unload.
    ///
    /// The span covers one whole pass, from the initial snapshot to the last unload; it is
    /// not made "current" via {@code Scope} because the work crosses the async-executor/main
    /// thread boundary twice, and an OpenTelemetry {@code Scope} must be closed on the same
    /// thread that opened it - trying to carry one across `scheduler.async`/`scheduler.sync`
    /// would either leak it or close it from the wrong thread. The {@link Span} object itself
    /// is safe to pass across threads and close from wherever the pass actually finishes.
    public void runReaperAsync() {
        Span span = Telemetry.tracer().spanBuilder("ploceus.reaper.pass").startSpan();
        List<WorldSnapshot> snapshots = multiverse.snapshotAll();
        span.setAttribute("ploceus.reaper.snapshot_count", snapshots.size());
        scheduler.async(() -> {
            try {
                Map<String, ProjectWorld> known = worlds.findAll().stream()
                        .collect(Collectors.toMap(ProjectWorld::getWorldName, w -> w));
                Instant now = Instant.now();
                List<String> doomed = snapshots.stream()
                        .filter(WorldSnapshot::isIdle)
                        .filter(s -> known.containsKey(s.worldName()))
                        .filter(s -> policy.shouldUnload(s, known.get(s.worldName()), now))
                        .map(WorldSnapshot::worldName)
                        .toList();
                span.setAttribute("ploceus.reaper.candidate_count", doomed.size());
                scheduler.sync(() -> {
                    try {
                        long unloaded = doomed.stream().filter(name -> multiverse.unload(name).isSuccess()).count();
                        worldsUnloadedByReaperCounter().add(unloaded);
                        span.setAttribute("ploceus.reaper.unloaded_count", unloaded);
                    } catch (RuntimeException exception) {
                        span.recordException(exception);
                        span.setStatus(StatusCode.ERROR);
                        throw exception;
                    } finally {
                        span.end();
                    }
                });
            } catch (RuntimeException exception) {
                span.recordException(exception);
                span.setStatus(StatusCode.ERROR);
                span.end();
                throw exception;
            }
        });
    }

    public void start() {
        visits.start(config.visitFlushSeconds());
        long ticks = config.reaperIntervalSeconds() * 20L;
        reaperTask = scheduler.repeating(this::runReaperAsync, ticks, ticks);
    }

    @Override
    public void close() {
        if (reaperTask != null) {
            try {
                reaperTask.close();
            } catch (Exception ignored) {
                // cancelling a Bukkit task cannot fail meaningfully
            }
        }
        visits.close();
    }
}
