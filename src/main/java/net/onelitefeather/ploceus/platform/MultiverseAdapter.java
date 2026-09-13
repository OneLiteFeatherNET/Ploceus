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
public class MultiverseAdapter {

    private final MultiverseCoreApi api;
    private final SchedulerAdapter scheduler;

    public MultiverseAdapter(MultiverseCoreApi api, SchedulerAdapter scheduler) {
        this.api = api;
        this.scheduler = scheduler;
    }

    private WorldManager worldManager() {
        return api.getWorldManager();
    }

    /// Must be called on the main thread. The returned snapshots are immutable and safe
    /// to hand to the async executor afterwards.
    public List<WorldSnapshot> snapshotAll() {
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
        World world = org.bukkit.Bukkit.getWorld(worldName);
        return world == null ? 0 : world.getPlayers().size();
    }

    public boolean isLoaded(String worldName) {
        return worldManager().isLoadedWorld(worldName);
    }

    public OperationResult<Void> load(String worldName) {
        var attempt = worldManager().loadWorld(worldName);
        return attempt.isSuccess()
                ? OperationResult.success(null)
                : OperationResult.failure("world.load.failed", String.valueOf(attempt.getFailureReason()));
    }

    public OperationResult<Void> unload(String worldName) {
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
        var world = worldManager().getWorld(worldName);
        if (world.isEmpty()) {
            return OperationResult.failure("world.unknown", worldName);
        }
        var result = world.get().setAutoLoad(autoLoad);
        return result.isSuccess()
                ? OperationResult.success(null)
                : OperationResult.failure("world.autoload.failed", String.valueOf(result.getCause()));
    }

    public OperationResult<Void> setAlias(String worldName, String alias) {
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
        CreateWorldOptions options = CreateWorldOptions
                .worldKey(new NamespacedKey(PloceusKey.NAMESPACE, request.key().value()))
                .environment(World.Environment.valueOf(request.environment()))
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
            if (!isLoaded(worldName)) {
                OperationResult<Void> loadResult = load(worldName);
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
            api.getSafetyTeleporter()
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
        GeneratorProvider provider = api.getGeneratorProvider();
        List<GeneratorOption> options = new ArrayList<>();
        provider.getRegisteredGeneratorPlugins().forEach(plugin -> {
            String name = plugin.getPluginName();
            options.add(new GeneratorOption(name, List.copyOf(provider.suggestGeneratorString(name))));
        });
        return List.copyOf(options);
    }
}
