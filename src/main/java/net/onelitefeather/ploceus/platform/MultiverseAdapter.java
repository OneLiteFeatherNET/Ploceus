package net.onelitefeather.ploceus.platform;

import net.onelitefeather.ploceus.domain.OperationResult;
import net.onelitefeather.ploceus.domain.PloceusKey;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.mvplugins.multiverse.core.MultiverseCoreApi;
import org.mvplugins.multiverse.core.world.MultiverseWorld;
import org.mvplugins.multiverse.core.world.WorldManager;
import org.mvplugins.multiverse.core.world.generators.GeneratorProvider;
import org.mvplugins.multiverse.core.world.options.CreateWorldOptions;
import org.mvplugins.multiverse.core.world.options.UnloadWorldOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/// Every Multiverse call is wrapped here, and every shaded Vavr `Attempt`/`Try`/`Option`
/// is turned into an `OperationResult` before it leaves this class.
///
/// ## Threading
///
/// **Every method here is safe to call from any thread**, and that is a property of this
/// class rather than of its callers. Each one runs its Multiverse work through
/// {@link SchedulerAdapter#onMainThread}: a call already on the main thread runs inline,
/// and a call from the async executor parks its (virtual) thread until the next tick.
///
/// This used to be a documented expectation instead, and one caller broke it —
/// `WorldService.rename` mirrors the display name onto the Multiverse alias before
/// persisting, and its only caller runs that on a virtual thread, so `setAlias` mutated a
/// Multiverse world off the main thread. Nothing noticed, because the only test for that
/// path substitutes a `MultiverseAdapter` subclass.
///
/// The guarantee is now enforced rather than described: {@link #api()} is the single point
/// at which this class reaches Multiverse at all, and it asserts the main thread. A method
/// added later that forgets to hop throws immediately instead of corrupting world state.
public class MultiverseAdapter {

    private final MultiverseCoreApi multiverseApi;
    private final SchedulerAdapter scheduler;

    public MultiverseAdapter(MultiverseCoreApi api, SchedulerAdapter scheduler) {
        this.multiverseApi = api;
        this.scheduler = scheduler;
    }

    /// The only way into Multiverse from this class, and the reason the threading rule is
    /// structural: everything below goes through here, and here asserts the thread.
    private MultiverseCoreApi api() {
        scheduler.requireMainThread("the Multiverse API");
        return multiverseApi;
    }

    private WorldManager worldManager() {
        return api().getWorldManager();
    }

    /// Safe from any thread (see the class Javadoc). The returned snapshots are immutable
    /// and safe to hand to the async executor afterwards.
    public List<WorldSnapshot> snapshotAll() {
        return scheduler.onMainThread(this::snapshotAllOnMainThread);
    }

    private List<WorldSnapshot> snapshotAllOnMainThread() {
        List<WorldSnapshot> snapshots = new ArrayList<>();
        for (MultiverseWorld world : worldManager().getWorlds()) {
            String name = world.getName();
            boolean loaded = worldManager().isLoadedWorld(name);
            snapshots.add(new WorldSnapshot(name, PloceusKey.fromWorldName(name), loaded,
                    world.isAutoLoad(), world.getAlias(), playerCount(name)));
        }
        return List.copyOf(snapshots);
    }

    public int playerCount(String worldName) {
        return scheduler.onMainThread(() -> {
            scheduler.requireMainThread("the Bukkit world registry");
            World world = org.bukkit.Bukkit.getWorld(worldName);
            return world == null ? 0 : world.getPlayers().size();
        });
    }

    public boolean isLoaded(String worldName) {
        return scheduler.onMainThread(() -> worldManager().isLoadedWorld(worldName));
    }

    public OperationResult<Void> load(String worldName) {
        return scheduler.onMainThread(() -> loadOnMainThread(worldName));
    }

    private OperationResult<Void> loadOnMainThread(String worldName) {
        var attempt = worldManager().loadWorld(worldName);
        return attempt.isSuccess()
                ? OperationResult.success(null)
                : OperationResult.failure("world.load.failed", String.valueOf(attempt.getFailureReason()));
    }

    public OperationResult<Void> unload(String worldName) {
        return scheduler.onMainThread(() -> unloadOnMainThread(worldName));
    }

    private OperationResult<Void> unloadOnMainThread(String worldName) {
        // Checked directly against the loaded set: getWorld(...) can be non-empty for an
        // unloaded world, and calling get() on the (then empty) getLoadedWorld(...) Option
        // would throw NoSuchElementException.
        var loaded = worldManager().getLoadedWorld(worldName);
        if (loaded.isEmpty()) {
            return OperationResult.failure("world.unknown", worldName);
        }
        var attempt = worldManager().unloadWorld(UnloadWorldOptions.world(loaded.get()));
        return attempt.isSuccess()
                ? OperationResult.success(null)
                : OperationResult.failure("world.unload.failed", String.valueOf(attempt.getFailureReason()));
    }

    public OperationResult<Void> setAutoLoad(String worldName, boolean autoLoad) {
        return scheduler.onMainThread(() -> setAutoLoadOnMainThread(worldName, autoLoad));
    }

    private OperationResult<Void> setAutoLoadOnMainThread(String worldName, boolean autoLoad) {
        var world = worldManager().getWorld(worldName);
        if (world.isEmpty()) {
            return OperationResult.failure("world.unknown", worldName);
        }
        var result = world.get().setAutoLoad(autoLoad);
        return result.isSuccess()
                ? OperationResult.success(null)
                : OperationResult.failure("world.autoload.failed", String.valueOf(result.getCause()));
    }

    /// Mutates Multiverse, so it hops to the main thread itself. `WorldService.rename`
    /// calls this from a virtual thread and must keep doing so *before* it persists
    /// anything — mirroring the alias first is what makes the rename all-or-nothing, since
    /// the alias is the retryable half. Only the thread was ever wrong there, not the order.
    public OperationResult<Void> setAlias(String worldName, String alias) {
        return scheduler.onMainThread(() -> setAliasOnMainThread(worldName, alias));
    }

    private OperationResult<Void> setAliasOnMainThread(String worldName, String alias) {
        var world = worldManager().getWorld(worldName);
        if (world.isEmpty()) {
            return OperationResult.failure("world.unknown", worldName);
        }
        var result = world.get().setAlias(alias);
        return result.isSuccess()
                ? OperationResult.success(null)
                : OperationResult.failure("world.alias.failed", String.valueOf(result.getCause()));
    }

    public OperationResult<String> createWorld(WorldCreationRequest request) {
        return scheduler.onMainThread(() -> createWorldOnMainThread(request));
    }

    private OperationResult<String> createWorldOnMainThread(WorldCreationRequest request) {
        World.Environment environment;
        try {
            environment = World.Environment.valueOf(request.environment());
        } catch (IllegalArgumentException | NullPointerException e) {
            // Reachable from a dialog input, not just a programming error — every path
            // out of this class must be an OperationResult, never a thrown exception.
            return OperationResult.failure("world.create.failed", String.valueOf(request.environment()));
        }

        CreateWorldOptions options = CreateWorldOptions
                .worldKey(new NamespacedKey(PloceusKey.NAMESPACE, request.key().value()))
                .environment(environment)
                .generateStructures(request.generateStructures());

        if (request.generator() != null && !request.generator().isBlank()) {
            options = options.generator(request.generator());
        }
        if (request.generatorSettings() != null && !request.generatorSettings().isBlank()) {
            options = options.generatorSettings(request.generatorSettings());
        }
        if (request.biome() != null && !request.biome().isBlank()) {
            options = options.biome(request.biome());
        }
        if (request.seed() != null) {
            options = options.seed(request.seed());
        }

        var attempt = worldManager().createWorld(options);
        return attempt.isSuccess()
                ? OperationResult.success(request.worldName())
                : OperationResult.failure("world.create.failed", String.valueOf(attempt.getFailureReason()));
    }

    /// Loads the world first if necessary, then teleports through Multiverse's own safety
    /// teleporter so the player never lands inside a block. The teleport itself is async
    /// (`AsyncAttempt`), so the future is completed from its callbacks rather than right
    /// after the call returns.
    public CompletableFuture<OperationResult<Void>> teleport(Player player, String worldName) {
        CompletableFuture<OperationResult<Void>> future = new CompletableFuture<>();
        scheduler.sync(() -> {
            if (!worldManager().isLoadedWorld(worldName)) {
                OperationResult<Void> loadResult = loadOnMainThread(worldName);
                if (!loadResult.isSuccess()) {
                    future.complete(loadResult);
                    return;
                }
            }
            World world = org.bukkit.Bukkit.getWorld(worldName);
            if (world == null) {
                future.complete(OperationResult.failure("world.unknown", worldName));
                return;
            }
            api().getSafetyTeleporter()
                    .to(world.getSpawnLocation())
                    .teleport(player)
                    .onSuccess(() -> future.complete(OperationResult.success(null)))
                    .onFailureReason(reason -> future.complete(
                            OperationResult.failure("world.teleport.failed", String.valueOf(reason))));
        });
        return future;
    }

    /// Populates the generator picker. VoidGen and anything else installed appears here by
    /// itself — nothing is hardcoded.
    public List<GeneratorOption> generators() {
        return scheduler.onMainThread(this::generatorsOnMainThread);
    }

    private List<GeneratorOption> generatorsOnMainThread() {
        GeneratorProvider provider = api().getGeneratorProvider();
        List<GeneratorOption> options = new ArrayList<>();
        provider.getRegisteredGeneratorPlugins().forEach(plugin -> {
            String name = plugin.getPluginName();
            options.add(new GeneratorOption(name, List.copyOf(provider.suggestGeneratorString(name))));
        });
        return List.copyOf(options);
    }
}
