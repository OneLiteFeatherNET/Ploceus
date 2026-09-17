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
import org.mvplugins.multiverse.external.vavr.control.Try;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

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
///
/// ## Persistence
///
/// Every setter on a Multiverse `MultiverseWorld` (`setAutoLoad`, `setAlias`, ...) only
/// mutates the in-memory `WorldConfig`; nothing under Multiverse ever calls
/// `WorldManager.saveWorldsConfig()` on the setter's behalf, and Multiverse does not flush
/// it at shutdown either. A world whose auto-load was "successfully" disabled here and never
/// saved loads again on the very next boot, having told the operator otherwise.
///
/// {@link #setAutoLoad} and {@link #setAlias} are therefore durable by contract: each folds
/// the mutation and the follow-up `saveWorldsConfig()` into a single `OperationResult`
/// through {@link #foldMutationThenSave}, so a caller that receives a success knows the
/// change is on disk, not just in memory, and a failed save is reported the same way a
/// failed mutation is. This was deliberately chosen over an explicit
/// `saveWorldConfiguration()` that callers must remember to invoke — that shape is exactly
/// what produced this bug (`PloceusPlugin.importExistingWorlds` called `setAutoLoad` once
/// per world and nothing ever saved), and `worlds.yml` is small enough (six worlds on the
/// server that found this) that a write per call is not worth the risk of a caller
/// forgetting again.
public class MultiverseAdapter {

    private static final Logger LOGGER = Logger.getLogger(MultiverseAdapter.class.getName());

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

    /// Durable: a `Success` here means auto-load is off both in memory and in `worlds.yml`
    /// (see the class Javadoc). A caller never needs to save separately.
    public OperationResult<Void> setAutoLoad(String worldName, boolean autoLoad) {
        return scheduler.onMainThread(() -> setAutoLoadOnMainThread(worldName, autoLoad));
    }

    private OperationResult<Void> setAutoLoadOnMainThread(String worldName, boolean autoLoad) {
        var world = worldManager().getWorld(worldName);
        if (world.isEmpty()) {
            return OperationResult.failure("world.unknown", worldName);
        }
        return foldMutationThenSave(world.get().setAutoLoad(autoLoad), worldName, "world.autoload.failed",
                worldManager()::saveWorldsConfig);
    }

    /// Mutates Multiverse, so it hops to the main thread itself. `WorldService.rename`
    /// calls this from a virtual thread and must keep doing so *before* it persists
    /// anything — mirroring the alias first is what makes the rename all-or-nothing, since
    /// the alias is the retryable half. Only the thread was ever wrong there, not the order.
    ///
    /// Durable, same as {@link #setAutoLoad}: a `Success` means the alias is saved to
    /// `worlds.yml`, not just held in memory.
    public OperationResult<Void> setAlias(String worldName, String alias) {
        return scheduler.onMainThread(() -> setAliasOnMainThread(worldName, alias));
    }

    private OperationResult<Void> setAliasOnMainThread(String worldName, String alias) {
        var world = worldManager().getWorld(worldName);
        if (world.isEmpty()) {
            return OperationResult.failure("world.unknown", worldName);
        }
        return foldMutationThenSave(world.get().setAlias(alias), worldName, "world.alias.failed",
                worldManager()::saveWorldsConfig);
    }

    /// The one place that decides whether a Multiverse property change counts as done:
    /// not on a successful in-memory mutation alone, but only once the follow-up
    /// `saveWorldsConfig()` also succeeds. This is deliberately a pure function of two
    /// `Try` values and a `Supplier` — it needs no live `WorldManager` or
    /// `MultiverseCoreApi` (both `final`, neither constructible in a test here) to exercise,
    /// because the defect this guards against was never about Multiverse's behaviour, only
    /// about this fold being missing. `save` is a `Supplier` rather than an already-evaluated
    /// `Try` so a failed mutation never touches disk at all.
    static OperationResult<Void> foldMutationThenSave(Try<Void> mutation, String worldName, String failureMessageKey,
                                                       Supplier<Try<Void>> save) {
        if (!mutation.isSuccess()) {
            // Vavr's getCause() is a Throwable. OperationResult.detail is rendered to the
            // player, so the exception goes to the log and the player gets the world name.
            LOGGER.log(Level.WARNING, "Could not change '" + worldName + "'", mutation.getCause());
            return OperationResult.failure(failureMessageKey, worldName);
        }
        Try<Void> saved = save.get();
        if (!saved.isSuccess()) {
            LOGGER.log(Level.WARNING,
                    "Changed '" + worldName + "' but could not persist worlds.yml", saved.getCause());
            return OperationResult.failure(failureMessageKey, worldName);
        }
        return OperationResult.success(null);
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
