package net.onelitefeather.ploceus.service;

import net.onelitefeather.ploceus.domain.PloceusKey;
import net.onelitefeather.ploceus.persistence.WorldRepository;
import net.onelitefeather.ploceus.platform.SchedulerAdapter;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/// Buffers world visits in memory and writes them in one batch per interval.
public class VisitTracker implements AutoCloseable {

    private final WorldRepository worlds;
    private final SchedulerAdapter scheduler;
    private final Map<PloceusKey, Instant> pending = new ConcurrentHashMap<>();
    private AutoCloseable task;

    public VisitTracker(WorldRepository worlds, SchedulerAdapter scheduler) {
        this.worlds = worlds;
        this.scheduler = scheduler;
    }

    /// Worlds Ploceus does not know are silently ignored.
    public void recordVisit(String worldName, Instant at) {
        worlds.findByWorldName(worldName).ifPresent(world ->
                pending.merge(world.getKey(), at, (existing, candidate) ->
                        candidate.isAfter(existing) ? candidate : existing));
    }

    public int pendingCount() {
        return pending.size();
    }

    public void flush() {
        if (pending.isEmpty()) {
            return;
        }
        Map<PloceusKey, Instant> batch = Map.copyOf(pending);
        pending.clear();
        worlds.updateLastVisited(batch);
    }

    public void start(int flushSeconds) {
        long ticks = flushSeconds * 20L;
        task = scheduler.repeating(() -> scheduler.async(this::flush), ticks, ticks);
    }

    @Override
    public void close() {
        if (task != null) {
            try {
                task.close();
            } catch (Exception ignored) {
                // cancelling a Bukkit task cannot fail meaningfully
            }
        }
        flush();
    }
}
