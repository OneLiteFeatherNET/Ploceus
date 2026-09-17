package net.onelitefeather.ploceus.command;

import jakarta.inject.Inject;
import net.onelitefeather.ploceus.domain.PloceusKey;
import net.onelitefeather.ploceus.platform.MultiverseAdapter;
import net.onelitefeather.ploceus.service.ProjectService;
import net.onelitefeather.ploceus.service.WorldService;
import net.onelitefeather.ploceus.ui.UiChannel;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.incendo.cloud.annotations.AnnotationParser;
import org.incendo.cloud.bukkit.BukkitCommandManager;
import org.incendo.cloud.bukkit.CloudBukkitCapabilities;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.paper.LegacyPaperCommandManager;
import org.incendo.cloud.parser.ParserDescriptor;
import org.incendo.cloud.parser.standard.EnumParser;
import org.incendo.cloud.suggestion.SuggestionProvider;

import java.util.List;
import java.util.logging.Level;
import java.util.stream.Stream;

/// Builds and registers the whole Cloud command tree from {@link PloceusCommands}.
///
/// Uses {@link LegacyPaperCommandManager} rather than the newer {@code PaperCommandManager}
/// from the same module. `PaperCommandManager`'s native sender type is Paper's
/// {@code CommandSourceStack}, and it needs a {@code SenderMapper} that can map both ways:
/// forward for parsing, and *backward*, from whatever sender type `C` the manager is
/// declared with back to a `CommandSourceStack` — used internally for permission checks,
/// exception handling and (if Brigadier is enabled) command-tree redirects. Paper's public
/// API (`paper-api` 26.2.build.123-stable, checked via `javap`) has no supported way to
/// construct a `CommandSourceStack` from an arbitrary `CommandSender`, so a manager typed
/// as `PaperCommandManager<CommandSender>` cannot implement that reverse mapping correctly.
/// `LegacyPaperCommandManager.createNative(...)` instead uses `CommandSender` as its native
/// sender type directly (`SenderMapper.identity()`, verified by decompiling
/// `LegacyPaperCommandManager.createNative` in `cloud-paper-2.0.0-sources.jar`), which is
/// what lets every `PloceusCommands` method declare a plain `Player` parameter and have
/// Cloud's annotation processor narrow the sender type automatically. It still runs on
/// modern Paper; "Legacy" here means "classic Bukkit command registration", not deprecated.
public class CommandRegistrar {

    private final Plugin plugin;
    private final PloceusCommands commands;
    private final ProjectService projects;
    private final WorldService worlds;
    private final MultiverseAdapter multiverse;

    @Inject
    public CommandRegistrar(Plugin plugin, PloceusCommands commands,
                            ProjectService projects, WorldService worlds, MultiverseAdapter multiverse) {
        this.plugin = plugin;
        this.commands = commands;
        this.projects = projects;
        this.worlds = worlds;
        this.multiverse = multiverse;
    }

    public void register() {
        LegacyPaperCommandManager<CommandSender> manager =
                LegacyPaperCommandManager.createNative(plugin, ExecutionCoordinator.asyncCoordinator());

        registerCompletionSupport(manager);

        // One key parser serves both project and world keys: suggestions are the union of
        // both pools, and PloceusKey itself carries no notion of "project key" vs "world key".
        // Behind a short-TTL cache, because Cloud asks for suggestions once per keystroke and
        // `allKeys` is two unbounded selects - see CachedKeys.
        manager.parserRegistry().registerParser(ParserDescriptor.of(
                new PloceusKeyParser(new CachedKeys<>(this::allKeys)), PloceusKey.class));
        manager.parserRegistry().registerParser(EnumParser.enumParser(UiChannel.class));

        // `/ploceus world import <folderName>` tab completion. Named rather than typed
        // (`@Argument(suggestions = "potentialWorlds")`) because the argument itself is a
        // plain `String` — registering a parser against `String.class` would hijack every
        // other plain-string argument in the command tree (`worldName`, `displayName`, ...).
        // Cached the same way `allKeys` is: `MultiverseAdapter.potentialWorlds()` walks the
        // world container directory on the main thread, and Cloud asks for suggestions once
        // per keystroke.
        CachedKeys<String> potentialWorlds = new CachedKeys<>(multiverse::potentialWorlds);
        manager.parserRegistry().registerSuggestionProvider("potentialWorlds",
                SuggestionProvider.blockingStrings((context, input) -> potentialWorlds.get()));

        new AnnotationParser<>(manager, CommandSender.class).parse(commands);
    }

    /// Prefers native Brigadier (client-side red/green argument validation as the player
    /// types, plus non-blocking suggestions built in) over the classic async-completion
    /// fallback. Cloud's own Javadoc on {@code registerAsynchronousCompletions} explicitly
    /// advises against combining the two — Brigadier already gives non-blocking suggestions
    /// at higher fidelity — so the fallback is only registered when Brigadier could not be.
    ///
    /// {@code registerBrigadier()} declares a checked-looking
    /// {@code BrigadierInitializationException}, but it actually extends
    /// {@code IllegalStateException} (unchecked, confirmed via `javap`), so a failure would
    /// otherwise propagate out of `onEnable` and abort the whole plugin. Caught explicitly:
    /// losing Brigadier's client-side validation is acceptable degradation, losing every
    /// Ploceus command because one optional capability failed to initialize is not.
    private void registerCompletionSupport(LegacyPaperCommandManager<CommandSender> manager) {
        if (manager.hasCapability(CloudBukkitCapabilities.NATIVE_BRIGADIER)) {
            try {
                manager.registerBrigadier();
                return;
            } catch (BukkitCommandManager.BrigadierInitializationException exception) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to register native Brigadier support; falling back to "
                                + "classic command registration. Client-side argument "
                                + "validation will be unavailable.", exception);
            }
        } else {
            plugin.getLogger().info(
                    "Server does not support native Brigadier commands; falling back to "
                            + "classic command registration.");
        }

        // BukkitCommandManager's own Javadoc warns that classic tab completion blocks the
        // main thread by default; PloceusKeyParser's suggestions call straight into the
        // project/world repositories, so without this every keystroke while tab-completing
        // a key would run a JDBC query on the main thread. This routes suggestion queries
        // through Paper's AsyncTabCompleteEvent instead, reached only when Brigadier is
        // unavailable or failed to initialize.
        if (manager.hasCapability(CloudBukkitCapabilities.ASYNCHRONOUS_COMPLETION)) {
            manager.registerAsynchronousCompletions();
        } else {
            plugin.getLogger().log(Level.WARNING,
                    "Server does not support asynchronous tab completion; "
                            + "Ploceus key suggestions may briefly block the main thread.");
        }
    }

    /// Two full table scans. Only ever called through {@link CachedKeys}, never per
    /// keystroke.
    private List<PloceusKey> allKeys() {
        return Stream.concat(
                projects.all().stream().map(project -> project.getKey()),
                worlds.all().stream().map(world -> world.getKey())).toList();
    }
}
