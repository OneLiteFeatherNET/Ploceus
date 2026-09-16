package net.onelitefeather.ploceus.service;

import net.onelitefeather.ploceus.domain.PloceusKey;
import net.onelitefeather.ploceus.persistence.WorldRepository;
import net.onelitefeather.ploceus.platform.SchedulerAdapter;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/// Buffers world visits in memory and writes them in one batch per interval.
///
/// `recordVisit` is called straight from Bukkit event handlers on the main thread, so it
/// must never touch the database: it only merges into an in-memory map keyed by world
/// name. The `worldName` -> `PloceusKey` resolution — which does query the database — is
/// deferred to `flush()`, which always runs on the async executor. A world unknown to
/// Ploceus is therefore dropped at flush time rather than at record time; a world imported
/// between the visit and the flush still gets its timestamp instead of losing it.
public class VisitTracker implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(VisitTracker.class.getName());

    private final WorldRepository worlds;
    private final SchedulerAdapter scheduler;
    private final Map<String, Instant> pending = new ConcurrentHashMap<>();
    private AutoCloseable task;

    public VisitTracker(WorldRepository worlds, SchedulerAdapter scheduler) {
        this.worlds = worlds;
        this.scheduler = scheduler;
    }

    /// Allocation-only: no repository call on this path.
    public void recordVisit(String worldName, Instant at) {
        pending.merge(worldName, at, (existing, candidate) ->
                candidate.isAfter(existing) ? candidate : existing);
    }

    public int pendingCount() {
        return pending.size();
    }

    /// Resolves buffered world names to their `PloceusKey` here, off the main thread.
    /// Worlds Ploceus does not know are silently dropped.
    public void flush() {
        if (pending.isEmpty()) {
            return;
        }
        Map<String, Instant> batch = Map.copyOf(pending);
        pending.clear();

        Map<PloceusKey, Instant> resolved = new HashMap<>();
        batch.forEach((worldName, at) -> worlds.findByWorldName(worldName)
                .ifPresent(world -> resolved.put(world.getKey(), at)));
        worlds.updateLastVisited(resolved);
    }

    public void start(int flushSeconds) {
        long ticks = flushSeconds * 20L;
        task = scheduler.repeating(() -> scheduler.async(this::flush), ticks, ticks);
    }

    /// Flushes synchronously and deliberately: this runs while the scheduler is being torn
    /// down, so handing the final flush to the (about-to-close) async executor would drop
    /// it instead of writing it.
    ///
    /// The flush is allowed to fail. On shutdown the database may already be gone — a
    /// PostgreSQL server stopping alongside the Minecraft server, a pool closed by another
    /// shutdown hook — and this is called from `WorldLifecycleService.close`, which
    /// `PloceusPlugin.onDisable` calls *before* it closes the session factory and the
    /// connection pool. A propagating exception here therefore leaked both. Losing the last
    /// few `lastVisitedAt` timestamps only costs the unload reaper one extra idle interval;
    /// leaking the pool costs a hung shutdown.
    @Override
    public void close() {
        if (task != null) {
            try {
                task.close();
            } catch (Exception ignored) {
                // cancelling a Bukkit task cannot fail meaningfully
            }
        }
        try {
            flush();
        } catch (RuntimeException exception) {
            LOGGER.log(Level.WARNING, "The final visit flush failed; up to "
                    + pendingCount() + " world visit timestamp(s) were not written", exception);
        }
    }
}
