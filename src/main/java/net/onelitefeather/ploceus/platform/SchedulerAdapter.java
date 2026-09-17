package net.onelitefeather.ploceus.platform;

import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public class SchedulerAdapter implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SchedulerAdapter.class);

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

    /// Whether the caller is already on the server's main thread.
    public boolean isMainThread() {
        return plugin.getServer().isPrimaryThread();
    }

    /// Runs `work` on the main thread and hands back what it returned, so a Bukkit or
    /// Multiverse call can be made from anywhere without the caller having to restructure
    /// itself around a callback.
    ///
    /// On the main thread this is a plain call — {@link #sync} runs inline there, so there
    /// is no round trip and no chance of deadlock. Off it, the calling thread blocks until
    /// the next tick; every off-main caller in this project is a virtual thread from
    /// {@link #async}, where blocking is cheap and parking until the next tick is the point.
    /// Whatever `work` throws is rethrown to the caller rather than swallowed on the main
    /// thread.
    public <T> T onMainThread(Supplier<T> work) {
        if (isMainThread()) {
            return work.get();
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        sync(() -> {
            try {
                result.complete(work.get());
            } catch (Throwable throwable) {
                result.completeExceptionally(throwable);
            }
        });
        return result.join();
    }

    /// Fails loudly instead of corrupting server state from the wrong thread. Used at the
    /// single point where {@link net.onelitefeather.ploceus.platform.MultiverseAdapter}
    /// reaches the Multiverse API, so a method that forgets {@link #onMainThread} throws
    /// rather than silently mutating a world off-thread.
    public void requireMainThread(String what) {
        if (!isMainThread()) {
            throw new IllegalStateException(
                    what + " must only be touched from the main thread, but this is " + Thread.currentThread());
        }
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
                log.warn("SchedulerAdapter did not terminate within 5 seconds; {} queued task(s) "
                        + "were dropped.", dropped.size());
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
