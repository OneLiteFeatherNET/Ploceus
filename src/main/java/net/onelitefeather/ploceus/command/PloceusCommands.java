package net.onelitefeather.ploceus.command;

import jakarta.inject.Inject;
import net.onelitefeather.ploceus.PloceusConfig;
import net.onelitefeather.ploceus.domain.*;
import net.onelitefeather.ploceus.platform.GeneratorOption;
import net.onelitefeather.ploceus.platform.MultiverseAdapter;
import net.onelitefeather.ploceus.platform.SchedulerAdapter;
import net.onelitefeather.ploceus.service.*;
import net.onelitefeather.ploceus.ui.Page;
import net.onelitefeather.ploceus.ui.UiChannel;
import net.onelitefeather.ploceus.ui.UiRouter;
import org.bukkit.entity.Player;
import org.incendo.cloud.annotation.specifier.Greedy;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.Default;
import org.incendo.cloud.annotations.Permission;

import java.util.List;
import java.util.Locale;

/// Every Cloud command Ploceus exposes. The command manager runs commands on an async
/// execution coordinator (see `CommandRegistrar`), so every method here starts off the main
/// thread: database access can happen directly, but every call into a `ProjectUi` (a Bukkit
/// call, even though today's only implementation just sends chat, per the project-wide rule
/// that UI calls always cross back through `SchedulerAdapter.sync`) is wrapped in
/// `scheduler.sync(...)`.
///
/// `/ploceus project delete <key> --confirm` intentionally has no `@Flag`-backed boolean
/// parameter: `--confirm` is a required literal token in the syntax string, so the command
/// simply does not exist in the tree without it — there is no code path that reaches
/// `projects.delete(...)` without the caller having typed it. See
/// `CommandSyntaxTest.deleteRequiresTheConfirmLiteral` and this class's Javadoc on
/// `projectDelete` for the verification of that claim.
public class PloceusCommands {

    private final ProjectService projects;
    private final WorldService worlds;
    private final WorldLifecycleService lifecycle;
    private final AuditService audit;
    private final UiRouter router;
    private final SchedulerAdapter scheduler;
    private final PloceusConfig config;
    private final NameSuggester suggester;
    private final MultiverseAdapter multiverse;
    private final WorldCreationService creation;

    @Inject
    public PloceusCommands(ProjectService projects, WorldService worlds, WorldLifecycleService lifecycle,
                           AuditService audit, UiRouter router, SchedulerAdapter scheduler,
                           PloceusConfig config, NameSuggester suggester, MultiverseAdapter multiverse,
                           WorldCreationService creation) {
        this.projects = projects;
        this.worlds = worlds;
        this.lifecycle = lifecycle;
        this.audit = audit;
        this.router = router;
        this.scheduler = scheduler;
        this.config = config;
        this.suggester = suggester;
        this.multiverse = multiverse;
        this.creation = creation;
    }

    private Actor actorOf(Player player) {
        return new Actor(player.getUniqueId(), player.getName());
    }

    @Command("ploceus|projects|pj")
    @Permission("ploceus.use")
    public void root(Player player) {
        scheduler.sync(() -> router.forPlayer(player).mainMenu(player));
    }

    @Command("ploceus|projects|pj project list [page]")
    @Permission("ploceus.project.list")
    public void projectList(Player player, @Argument("page") @Default("1") int page) {
        scheduler.async(() -> {
            Page<Project> result = Page.of(projects.all(), Math.max(0, page - 1), config.entriesPerPage());
            scheduler.sync(() -> router.forPlayer(player).projectList(player, result));
        });
    }

    @Command("ploceus|projects|pj project info <key>")
    @Permission("ploceus.project.list")
    public void projectInfo(Player player, @Argument("key") PloceusKey key) {
        scheduler.async(() -> projects.find(key).ifPresentOrElse(
                project -> {
                    var projectWorlds = worlds.byProject(project);
                    scheduler.sync(() -> router.forPlayer(player).projectDetail(player, project, projectWorlds));
                },
                () -> scheduler.sync(() -> router.forPlayer(player).feedback(player,
                        OperationResult.failure("project.unknown", key.asString()), ""))));
    }

    @Command("ploceus|projects|pj project create")
    @Permission("ploceus.project.create")
    public void projectCreateForm(Player player) {
        scheduler.sync(() -> router.forPlayer(player).createProjectForm(player));
    }

    @Command("ploceus|projects|pj project create <key> <displayName>")
    @Permission("ploceus.project.create")
    public void projectCreate(Player player, @Argument("key") PloceusKey key,
                              @Argument("displayName") @Greedy String displayName) {
        scheduler.async(() -> {
            var result = projects.create(key, displayName, actorOf(player));
            scheduler.sync(() -> router.forPlayer(player).feedback(player, result, "project.created"));
        });
    }

    @Command("ploceus|projects|pj project rename <key> <displayName>")
    @Permission("ploceus.project.edit")
    public void projectRename(Player player, @Argument("key") PloceusKey key,
                              @Argument("displayName") @Greedy String displayName) {
        scheduler.async(() -> {
            var result = projects.rename(key, displayName, actorOf(player));
            scheduler.sync(() -> router.forPlayer(player).feedback(player, result, "project.updated"));
        });
    }

    /// The command-layer route to `ProjectService.describe`, closing the gap where only the
    /// dialog channel (`DialogUi.createProjectForm`/`editProjectForm`, calling
    /// `projects.describe(...)` directly) could ever set a project's description. A player
    /// on the inventory or chat channel had no way to reach it at all.
    ///
    /// The description is a `@Greedy` trailing argument, like `displayName` above, which
    /// means an empty description cannot be typed here — Cloud requires at least one
    /// character for a required string argument, so there is no way to submit zero tokens
    /// for it. This command can therefore set or overwrite a description, but never clear
    /// one back to empty; the dialog channel's edit form remains the only route to that,
    /// since it submits whatever text is in the input field, including none. That is an
    /// accepted, documented gap (see `docs/operations.md`), not an oversight: a sentinel
    /// value or a second `describe --clear` command would both work, but neither was worth
    /// the extra surface for a feature no one has asked for yet.
    @Command("ploceus|projects|pj project describe <key> <description>")
    @Permission("ploceus.project.edit")
    public void projectDescribe(Player player, @Argument("key") PloceusKey key,
                                @Argument("description") @Greedy String description) {
        scheduler.async(() -> {
            var result = projects.describe(key, description, actorOf(player));
            scheduler.sync(() -> router.forPlayer(player).feedback(player, result, "project.updated"));
        });
    }

    /// Requires the literal `--confirm` token (see the class Javadoc). Without it, this
    /// exact command string does not match any registered command, so Cloud rejects it
    /// before this method — or any project deletion logic — is ever reached.
    @Command("ploceus|projects|pj project delete <key> --confirm")
    @Permission("ploceus.project.delete")
    public void projectDelete(Player player, @Argument("key") PloceusKey key) {
        scheduler.async(() -> {
            var result = projects.delete(key, actorOf(player));
            scheduler.sync(() -> router.forPlayer(player).feedback(player, result, "project.deleted"));
        });
    }

    /// The chat channel's world-list screen and both graphical channels' pagination
    /// linked at `/ploceus world list <key> <page>`, which was never registered — the link
    /// simply failed to parse. `ProjectUi.worldList` is part of the channel contract and
    /// every channel implements it, so the command that reaches it is what was missing, not
    /// the screen.
    @Command("ploceus|projects|pj world list <projectKey> [page]")
    @Permission("ploceus.world.list")
    public void worldList(Player player, @Argument("projectKey") PloceusKey projectKey,
                          @Argument("page") @Default("1") int page) {
        scheduler.async(() -> projects.find(projectKey).ifPresentOrElse(project -> {
            Page<ProjectWorld> result = Page.of(worlds.byProject(project), Math.max(0, page - 1),
                    config.entriesPerPage());
            scheduler.sync(() -> router.forPlayer(player).worldList(player, project, result));
        }, () -> scheduler.sync(() -> router.forPlayer(player).feedback(player,
                OperationResult.failure("project.unknown", projectKey.asString()), ""))));
    }

    @Command("ploceus|projects|pj world list-unassigned [page]")
    @Permission("ploceus.world.list")
    public void unassigned(Player player, @Argument("page") @Default("1") int page) {
        scheduler.async(() -> {
            Page<ProjectWorld> result = Page.of(worlds.unassigned(), Math.max(0, page - 1),
                    config.entriesPerPage());
            scheduler.sync(() -> router.forPlayer(player).unassignedWorlds(player, result));
        });
    }

    /// Suggests a unique name off the main thread (it only reads the world list), then hops
    /// back to the main thread to read Multiverse's installed generators and open the form —
    /// the same split `DialogUi.rerollWorldName` uses to re-suggest without ever touching
    /// Multiverse from an async task.
    @Command("ploceus|projects|pj world create <projectKey>")
    @Permission("ploceus.world.create")
    public void createWorldForm(Player player, @Argument("projectKey") PloceusKey projectKey) {
        scheduler.async(() -> projects.find(projectKey).ifPresentOrElse(project -> {
            var taken = worlds.all().stream().map(ProjectWorld::getWorldName)
                    .collect(java.util.stream.Collectors.toSet());
            String suggestion = suggester.suggestUnique(taken::contains);
            scheduler.sync(() -> {
                List<String> generators = multiverse.generators().stream()
                        .map(GeneratorOption::pluginName).toList();
                router.forPlayer(player).createWorldForm(player, project, suggestion, generators);
            });
        }, () -> scheduler.sync(() -> router.forPlayer(player).feedback(player,
                OperationResult.failure("project.unknown", projectKey.asString()), ""))));
    }

    /// The text-only counterpart of the dialog channel's creation form, and the command
    /// both the chat and the inventory channel suggest in its place — they suggested this
    /// exact syntax before it existed, so the suggestion could not be run. Everything the
    /// form would ask for besides the name comes from {@link WorldCreationService#defaultsFor},
    /// i.e. the project's preset falling back to the config default.
    @Command("ploceus|projects|pj world create <projectKey> <worldName>")
    @Permission("ploceus.world.create")
    public void createWorld(Player player, @Argument("projectKey") PloceusKey projectKey,
                            @Argument("worldName") String worldName) {
        scheduler.async(() -> projects.find(projectKey).ifPresentOrElse(project -> {
            WorldDefaults defaults = creation.defaultsFor(project);
            var form = new WorldCreationService.WorldCreationForm(
                    worldName, worldName, defaults.getGenerator(), defaults.getGeneratorSettings(),
                    defaults.getEnvironment(), defaults.isGenerateStructures(), null);
            // `creation.create` crosses threads on its own and completes on whichever thread
            // its last phase runs on, so the continuation needs its own hop back to main.
            creation.create(project, form, actorOf(player)).thenAccept(result -> scheduler.sync(() -> {
                router.forPlayer(player).feedback(player,
                        result.map(WorldCreationService.CreationOutcome::world), "world.created");
                if (result instanceof OperationResult.Success<WorldCreationService.CreationOutcome> success) {
                    success.value().warnings().forEach(warning -> player.sendMessage(
                            net.onelitefeather.ploceus.ui.Messages.chat(warning.messageKey(),
                                    net.onelitefeather.ploceus.ui.Messages.placeholder(
                                            "detail", warning.detail()))));
                }
            }));
        }, () -> scheduler.sync(() -> router.forPlayer(player).feedback(player,
                OperationResult.failure("project.unknown", projectKey.asString()), ""))));
    }

    @Command("ploceus|projects|pj world assign <worldKey> <projectKey>")
    @Permission("ploceus.world.assign")
    public void assign(Player player, @Argument("worldKey") PloceusKey worldKey,
                       @Argument("projectKey") PloceusKey projectKey) {
        scheduler.async(() -> {
            var result = worlds.assign(worldKey, projectKey, actorOf(player));
            scheduler.sync(() -> router.forPlayer(player).feedback(player, result, "world.assigned"));
        });
    }

    @Command("ploceus|projects|pj world unassign <worldKey>")
    @Permission("ploceus.world.assign")
    public void unassign(Player player, @Argument("worldKey") PloceusKey worldKey) {
        scheduler.async(() -> {
            var result = worlds.unassign(worldKey, actorOf(player));
            scheduler.sync(() -> router.forPlayer(player).feedback(player, result, "world.unassigned"));
        });
    }

    @Command("ploceus|projects|pj world rename <worldKey> <displayName>")
    @Permission("ploceus.world.edit")
    public void renameWorld(Player player, @Argument("worldKey") PloceusKey worldKey,
                            @Argument("displayName") @Greedy String displayName) {
        scheduler.async(() -> {
            var result = worlds.rename(worldKey, displayName, actorOf(player));
            scheduler.sync(() -> router.forPlayer(player).feedback(player, result, "world.renamed"));
        });
    }

    /// The command-layer route to `WorldService.describe`, which previously had no caller
    /// at all — no channel and no command reached it. Same empty-description caveat as
    /// `projectDescribe` above: the `@Greedy` argument can overwrite a description but
    /// cannot clear it back to empty.
    @Command("ploceus|projects|pj world describe <worldKey> <description>")
    @Permission("ploceus.world.edit")
    public void describeWorld(Player player, @Argument("worldKey") PloceusKey worldKey,
                              @Argument("description") @Greedy String description) {
        scheduler.async(() -> {
            var result = worlds.describe(worldKey, description, actorOf(player));
            scheduler.sync(() -> router.forPlayer(player).feedback(player, result, "world.updated"));
        });
    }

    @Command("ploceus|projects|pj world idle <worldKey> <minutes>")
    @Permission("ploceus.world.edit")
    public void setIdle(Player player, @Argument("worldKey") PloceusKey worldKey,
                        @Argument("minutes") int minutes) {
        scheduler.async(() -> {
            Integer value = minutes <= 0 ? null : minutes;
            var result = worlds.setIdleMinutes(worldKey, value, actorOf(player));
            scheduler.sync(() -> router.forPlayer(player).feedback(player, result, "world.settingsUpdated"));
        });
    }

    @Command("ploceus|projects|pj world pin <worldKey> <pinned>")
    @Permission("ploceus.world.edit")
    public void setPinned(Player player, @Argument("worldKey") PloceusKey worldKey,
                          @Argument("pinned") boolean pinned) {
        scheduler.async(() -> {
            var result = worlds.setPinned(worldKey, pinned, actorOf(player));
            scheduler.sync(() -> router.forPlayer(player).feedback(player, result, "world.settingsUpdated"));
        });
    }

    @Command("ploceus|projects|pj world tag <worldKey> <tagKey>")
    @Permission("ploceus.world.edit")
    public void tagWorld(Player player, @Argument("worldKey") PloceusKey worldKey,
                         @Argument("tagKey") PloceusKey tagKey) {
        scheduler.async(() -> {
            var result = worlds.addTag(worldKey, tagKey, actorOf(player));
            scheduler.sync(() -> router.forPlayer(player).feedback(player, result, "world.settingsUpdated"));
        });
    }

    /// `WorldLifecycleService.teleport` records the visit (allocation-only, safe on any
    /// thread) and delegates to `MultiverseAdapter.teleport`, which schedules its own
    /// main-thread work internally and completes the returned future from a callback — so
    /// no extra `scheduler.sync` is needed around the call itself, only around reporting
    /// the outcome back to the player.
    @Command("ploceus|projects|pj world tp <worldKey>")
    @Permission("ploceus.world.teleport")
    public void teleport(Player player, @Argument("worldKey") PloceusKey worldKey) {
        scheduler.async(() -> worlds.find(worldKey).ifPresentOrElse(
                world -> lifecycle.teleport(player, world).thenAccept(result -> {
                    if (!result.isSuccess()) {
                        scheduler.sync(() -> router.forPlayer(player).feedback(player, result, ""));
                    }
                }),
                () -> scheduler.sync(() -> router.forPlayer(player).feedback(player,
                        OperationResult.failure("world.unknown", worldKey.asString()), ""))));
    }

    @Command("ploceus|projects|pj search <term>")
    @Permission("ploceus.search")
    public void search(Player player, @Argument("term") @Greedy String term) {
        scheduler.async(() -> {
            Page<Project> foundProjects = Page.of(projects.search(term), 0, config.entriesPerPage());
            Page<ProjectWorld> foundWorlds = Page.of(worlds.search(term), 0, config.entriesPerPage());
            scheduler.sync(() -> router.forPlayer(player)
                    .searchResults(player, term, foundProjects, foundWorlds));
        });
    }

    /// `AuditService` exposes only `history(target, limit, offset)`, with no total-count
    /// query (adding one would mean a new repository method, out of this task's scope). One
    /// extra row is fetched per page to detect whether a next page exists, so `Page.hasNext`
    /// stays accurate without a count query.
    @Command("ploceus|projects|pj audit <key> [page]")
    @Permission("ploceus.audit")
    public void auditTrail(Player player, @Argument("key") PloceusKey key,
                           @Argument("page") @Default("1") int page) {
        scheduler.async(() -> {
            int pageNumber = Math.max(0, page - 1);
            int size = config.entriesPerPage();
            List<AuditEntry> fetched = audit.history(key, size + 1, pageNumber * size);
            boolean hasNext = fetched.size() > size;
            List<AuditEntry> items = hasNext ? fetched.subList(0, size) : fetched;
            int totalItems = pageNumber * size + items.size() + (hasNext ? 1 : 0);
            Page<AuditEntry> result = new Page<>(items, pageNumber, size, totalItems);
            scheduler.sync(() -> router.forPlayer(player).auditTrail(player, key, result));
        });
    }

    @Command("ploceus|projects|pj ui <channel>")
    @Permission("ploceus.use")
    public void setChannel(Player player, @Argument("channel") UiChannel channel) {
        scheduler.async(() -> {
            router.setPreference(player.getUniqueId(), channel);
            scheduler.sync(() -> player.sendMessage(net.onelitefeather.ploceus.ui.Messages.chat(
                    "ui.changed", net.onelitefeather.ploceus.ui.Messages.placeholder(
                            "channel", channel.name().toLowerCase(Locale.ROOT)))));
        });
    }
}
