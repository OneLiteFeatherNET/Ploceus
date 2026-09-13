package net.onelitefeather.ploceus.platform;

import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    @Override
    public void close() {
        executor.shutdown();
    }
}
