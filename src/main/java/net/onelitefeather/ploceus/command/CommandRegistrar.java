package net.onelitefeather.ploceus.command;

import jakarta.inject.Inject;
import net.onelitefeather.ploceus.domain.PloceusKey;
import net.onelitefeather.ploceus.service.ProjectService;
import net.onelitefeather.ploceus.service.WorldService;
import net.onelitefeather.ploceus.ui.UiChannel;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.incendo.cloud.annotations.AnnotationParser;
import org.incendo.cloud.bukkit.CloudBukkitCapabilities;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.paper.LegacyPaperCommandManager;
import org.incendo.cloud.parser.ParserDescriptor;
import org.incendo.cloud.parser.standard.EnumParser;

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

    @Inject
    public CommandRegistrar(Plugin plugin, PloceusCommands commands,
                            ProjectService projects, WorldService worlds) {
        this.plugin = plugin;
        this.commands = commands;
        this.projects = projects;
        this.worlds = worlds;
    }

    public void register() {
        LegacyPaperCommandManager<CommandSender> manager =
                LegacyPaperCommandManager.createNative(plugin, ExecutionCoordinator.asyncCoordinator());

        // BukkitCommandManager's own Javadoc warns that classic tab completion blocks the
        // main thread by default; PloceusKeyParser's suggestions call straight into the
        // project/world repositories, so without this every keystroke while tab-completing
        // a key would run a JDBC query on the main thread. This routes suggestion queries
        // through Paper's AsyncTabCompleteEvent instead.
        if (manager.hasCapability(CloudBukkitCapabilities.ASYNCHRONOUS_COMPLETION)) {
            manager.registerAsynchronousCompletions();
        } else {
            plugin.getLogger().log(Level.WARNING,
                    "Server does not support asynchronous tab completion; "
                            + "Ploceus key suggestions may briefly block the main thread.");
        }

        // One key parser serves both project and world keys: suggestions are the union of
        // both pools, and PloceusKey itself carries no notion of "project key" vs "world key".
        manager.parserRegistry().registerParser(
                ParserDescriptor.of(new PloceusKeyParser(this::allKeys), PloceusKey.class));
        manager.parserRegistry().registerParser(EnumParser.enumParser(UiChannel.class));

        new AnnotationParser<>(manager, CommandSender.class).parse(commands);
    }

    private List<PloceusKey> allKeys() {
        return Stream.concat(
                projects.all().stream().map(project -> project.getKey()),
                worlds.all().stream().map(world -> world.getKey())).toList();
    }
}
