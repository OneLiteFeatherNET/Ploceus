package net.onelitefeather.ploceus.platform;

import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public class SchedulerAdapter implements AutoCloseable {

    private final Plugin plugin;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public SchedulerAdapter(Plugin plugin) {
        this.plugin = plugin;
    }

    public void async(Runnable work) {
        executor.execute(work);
    }

    public <T> CompletableFuture<T> supplyAsync(Supplier<T> work) {
        return CompletableFuture.supplyAsync(work, executor);
    }

    /// Hops back onto the main thread. Every Bukkit call must go through here.
    public void sync(Runnable work) {
        if (plugin.getServer().isPrimaryThread()) {
            work.run();
        } else {
            plugin.getServer().getScheduler().runTask(plugin, work);
        }
    }

    public AutoCloseable repeating(Runnable work, long initialDelayTicks, long periodTicks) {
        BukkitTask task = plugin.getServer().getScheduler()
                .runTaskTimer(plugin, work, initialDelayTicks, periodTicks);
        return task::cancel;
    }

    /// Waits up to 5 seconds for queued work (e.g. `VisitTracker`'s final flush on plugin
    /// disable) to finish before tearing the executor down, so a shutdown never silently
    /// drops an in-flight write.
    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                List<Runnable> dropped = executor.shutdownNow();
                plugin.getLogger().warning("SchedulerAdapter did not terminate within 5 seconds; "
                        + dropped.size() + " queued task(s) were dropped.");
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
