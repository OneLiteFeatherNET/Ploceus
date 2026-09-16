package net.onelitefeather.ploceus.ui.dialog;

import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import jakarta.inject.Inject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.onelitefeather.ploceus.PloceusConfig;
import net.onelitefeather.ploceus.domain.*;
import net.onelitefeather.ploceus.platform.SchedulerAdapter;
import net.onelitefeather.ploceus.service.*;
import net.onelitefeather.ploceus.ui.Messages;
import net.onelitefeather.ploceus.ui.Page;
import net.onelitefeather.ploceus.ui.ProjectUi;
import net.onelitefeather.ploceus.ui.UiPermissions;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import static net.onelitefeather.ploceus.ui.Messages.placeholder;

/// The primary channel: Paper's native Dialog API. Every navigation opens a fresh dialog
/// because dialogs cannot be refreshed in place, and lists paginate by hand because dialogs
/// do not scroll.
///
/// ## Permissions
///
/// The command layer's `@Permission` annotations only ever gate the Cloud command tree —
/// they say nothing about this class, because Paper dialog buttons are custom-click actions
/// registered server-side and fired by a plain click packet naming a callback id. Two
/// consequences follow:
///
/// 1. Reaching a screen is not authorization to act on it. `projectDetail` is shown to
///    anyone holding `ploceus.project.list`, but its delete button performs an action
///    gated by the separate, stricter `ploceus.project.delete` in the command layer — so
///    the button must not even be rendered for a player who lacks it.
/// 2. Hiding a button is not a control by itself. A modified client can send a custom-click
///    packet for a registered callback id without ever having been shown the dialog that
///    contains it, so the permission must be re-checked inside the callback, immediately
///    before the service call — that is the check a forged payload cannot skip.
///
/// Both halves are enforced by construction rather than by convention: every button in this
/// class is built through {@link DialogCallbacks#button}, which requires a permission node
/// and returns no button at all when the player lacks it, and wraps the action in an
/// execute-time re-check when the player has it. There is no other way in this class to
/// build a button that reaches a service, so a callback cannot silently go unguarded again.
public class DialogUi implements ProjectUi {

    private static final int COLUMNS = 2;
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final ProjectService projects;
    private final WorldService worlds;
    private final WorldLifecycleService lifecycle;
    private final AuditService audit;
    private final SchedulerAdapter scheduler;
    private final PloceusConfig config;
    private final WorldCreationService creation;
    private final NameSuggester suggester;

    @Inject
    public DialogUi(ProjectService projects, WorldService worlds, WorldLifecycleService lifecycle,
                    AuditService audit, SchedulerAdapter scheduler, PloceusConfig config,
                    WorldCreationService creation, NameSuggester suggester) {
        this.projects = projects;
        this.worlds = worlds;
        this.lifecycle = lifecycle;
        this.audit = audit;
        this.scheduler = scheduler;
        this.config = config;
        this.creation = creation;
        this.suggester = suggester;
    }

    @Override
    public void mainMenu(Player player) {
        List<ActionButton> buttons = new ArrayList<>();
        addIfPermitted(buttons, player, "ploceus.project.list",
                Messages.get("menu.projects"), () -> openProjectList(player, 0));
        addIfPermitted(buttons, player, "ploceus.world.list",
                Messages.get("menu.unassigned"), () -> openUnassigned(player, 0));
        addIfPermitted(buttons, player, "ploceus.search",
                Messages.get("menu.search"), () -> searchPrompt(player));
        player.showDialog(DialogFactory.multiAction(
                Messages.get("menu.title"), buttons, exitButton(player), COLUMNS));
    }

    @Override
    public void projectList(Player player, Page<Project> page) {
        List<ActionButton> buttons = new ArrayList<>();
        for (Project project : page.items()) {
            addIfPermitted(buttons, player, "ploceus.project.list",
                    Component.text(project.getDisplayName()),
                    Messages.get("project.entry",
                            placeholder("name", project.getKey().asString()),
                            placeholder("worlds", String.valueOf(project.getWorlds().size())),
                            Messages.component("tags", tagList(project))),
                    () -> openProjectDetail(player, project.getKey()));
        }
        buttons.addAll(DialogCallbacks.paginationButtons(player, "ploceus.use", () -> denyPermission(player),
                page, target -> openProjectList(player, target)));
        player.showDialog(DialogFactory.multiAction(
                Messages.get("project.list.title",
                        placeholder("page", String.valueOf(page.pageNumber() + 1)),
                        placeholder("pages", String.valueOf(page.totalPages()))),
                buttons, backTo(player, () -> mainMenu(player)), COLUMNS));
    }

    @Override
    public void projectDetail(Player player, Project project, List<ProjectWorld> projectWorlds) {
        List<ActionButton> buttons = new ArrayList<>();
        addIfPermitted(buttons, player, "ploceus.world.list",
                Messages.get("menu.worlds", placeholder("count", String.valueOf(projectWorlds.size()))),
                () -> openWorldList(player, project.getKey(), 0));
        addIfPermitted(buttons, player, "ploceus.project.edit",
                Messages.get("menu.edit"), () -> editProjectForm(player, project));
        addIfPermitted(buttons, player, "ploceus.audit",
                Messages.get("menu.audit"), () -> openAudit(player, project.getKey(), 0));
        // Deliberate second check on the most destructive action: `addIfPermitted` already
        // gates whether this button exists at all and re-checks on click, but the action it
        // opens (the confirm dialog's "Yes") is wrapped in its own explicit `guarded(...)`
        // for the same permission, so deletion is checked once when the delete button fires
        // and again when the confirmation is actually accepted.
        addIfPermitted(buttons, player, "ploceus.project.delete",
                Messages.get("menu.delete"), () -> confirm(player, "project.delete.prompt",
                        guarded(player, "ploceus.project.delete", () -> {
                            Actor actor = new Actor(player.getUniqueId(), player.getName());
                            scheduler.async(() -> {
                                var result = projects.delete(project.getKey(), actor);
                                scheduler.sync(() -> feedback(player, result, "project.deleted"));
                            });
                        })));
        player.showDialog(DialogFactory.multiAction(
                Component.text(project.getDisplayName()), buttons,
                backTo(player, () -> openProjectList(player, 0)), COLUMNS));
    }

    @Override
    public void worldList(Player player, Project project, Page<ProjectWorld> page) {
        List<ActionButton> buttons = new ArrayList<>(worldButtons(player, page.items()));
        buttons.addAll(DialogCallbacks.paginationButtons(player, "ploceus.use", () -> denyPermission(player),
                page, target -> openWorldList(player, project.getKey(), target)));
        player.showDialog(DialogFactory.multiAction(
                Component.text(project.getDisplayName()), buttons,
                backTo(player, () -> openProjectDetail(player, project.getKey())), COLUMNS));
    }

    @Override
    public void unassignedWorlds(Player player, Page<ProjectWorld> page) {
        List<ActionButton> buttons = new ArrayList<>(worldButtons(player, page.items()));
        buttons.addAll(DialogCallbacks.paginationButtons(player, "ploceus.use", () -> denyPermission(player),
                page, target -> openUnassigned(player, target)));
        player.showDialog(DialogFactory.multiAction(
                Messages.get("menu.unassigned"), buttons, backTo(player, () -> mainMenu(player)), COLUMNS));
    }

    @Override
    public void searchResults(Player player, String term, Page<Project> foundProjects,
                              Page<ProjectWorld> foundWorlds) {
        List<ActionButton> buttons = new ArrayList<>();
        foundProjects.items().forEach(project -> addIfPermitted(buttons, player, "ploceus.project.list",
                Component.text(project.getDisplayName()),
                Messages.get("project.entry",
                        placeholder("name", project.getKey().asString()),
                        placeholder("worlds", String.valueOf(project.getWorlds().size())),
                        Messages.component("tags", tagList(project))),
                () -> openProjectDetail(player, project.getKey())));
        buttons.addAll(worldButtons(player, foundWorlds.items()));
        Component title = foundProjects.totalItems() == 0 && foundWorlds.totalItems() == 0
                ? Messages.get("search.empty", placeholder("term", term))
                : Messages.get("search.results", placeholder("term", term));
        player.showDialog(DialogFactory.multiAction(title, buttons, backTo(player, () -> mainMenu(player)), COLUMNS));
    }

    @Override
    public void auditTrail(Player player, PloceusKey target, Page<AuditEntry> entries) {
        List<DialogBody> body = entries.items().stream()
                .<DialogBody>map(entry -> DialogBody.plainMessage(
                        Messages.get("audit.entry",
                                placeholder("at", TIMESTAMP.format(entry.getOccurredAt())),
                                placeholder("actor", entry.getActorName()),
                                placeholder("action", entry.getAction().name()),
                                placeholder("field", entry.getField() == null ? "" : entry.getField()),
                                placeholder("old", entry.getOldValue()),
                                placeholder("new", entry.getNewValue()))))
                .toList();
        List<ActionButton> buttons = new ArrayList<>(DialogCallbacks.paginationButtons(
                player, "ploceus.use", () -> denyPermission(player),
                entries, pageNumber -> openAudit(player, target, pageNumber)));
        player.showDialog(DialogFactory.multiAction(Messages.get("audit.title"), body, buttons,
                backTo(player, () -> openProjectDetail(player, target)), COLUMNS));
    }

    @Override
    public void feedback(Player player, OperationResult<?> result, String successKey) {
        Component message = switch (result) {
            case OperationResult.Success<?> ignored -> Messages.get(successKey);
            case OperationResult.Failure<?> failure ->
                    Messages.get(failure.messageKey(), placeholder("detail", failure.detail()));
        };
        player.showDialog(DialogFactory.notice(Messages.get("menu.title"),
                List.of(DialogBody.plainMessage(message)),
                List.of(), backTo(player, () -> mainMenu(player))));
    }

    @Override
    public void confirm(Player player, String messageKey, Runnable onConfirm) {
        // `onConfirm` is supplied by the caller and is expected to already be wrapped in
        // `guarded(...)` for whatever permission the confirmed action needs — this method
        // has no way to know that permission itself, so its own button only asserts
        // `ploceus.use` (reaching a confirm screen at all). "No" only closes the dialog,
        // which is never privileged.
        player.showDialog(DialogFactory.confirmation(
                Messages.get("menu.title"),
                List.of(DialogBody.plainMessage(Messages.get(messageKey))),
                button(player, "ploceus.use", Messages.get("button.confirm"), onConfirm),
                button(player, "ploceus.use", Messages.get("button.cancel"), player::closeDialog)));
    }

    @Override
    public void searchPrompt(Player player) {
        var term = DialogInput.text("term", Messages.get("menu.search")).build();
        player.showDialog(DialogFactory.notice(
                Messages.get("menu.search"),
                List.of(DialogBody.plainMessage(Messages.get("search.prompt"))),
                List.of(term),
                submitButton(player, "ploceus.search", view -> {
                    String entered = textOf(view, "term");
                    scheduler.async(() -> {
                        Page<Project> foundProjects =
                                Page.of(projects.search(entered), 0, config.entriesPerPage());
                        Page<ProjectWorld> foundWorlds =
                                Page.of(worlds.search(entered), 0, config.entriesPerPage());
                        scheduler.sync(() -> searchResults(player, entered, foundProjects, foundWorlds));
                    });
                })));
    }

    @Override
    public void createProjectForm(Player player) {
        var keyInput = DialogInput.text("key", Messages.get("form.key")).build();
        var nameInput = DialogInput.text("displayName", Messages.get("form.displayName")).build();
        var descriptionInput = DialogInput.text("description", Messages.get("form.description")).build();
        var tagsInput = DialogInput.text("tags", Messages.get("form.tags")).build();

        player.showDialog(DialogFactory.notice(
                Messages.get("menu.projects"), List.of(),
                List.of(keyInput, nameInput, descriptionInput, tagsInput),
                submitButton(player, "ploceus.project.create", view -> {
                    OperationResult<PloceusKey> key = FormInputs.validateKey(view.getText("key"));
                    if (!(key instanceof OperationResult.Success<PloceusKey> success)) {
                        feedback(player, key, "");
                        return;
                    }
                    PloceusKey projectKey = success.value();
                    Actor actor = new Actor(player.getUniqueId(), player.getName());
                    String displayName = textOf(view, "displayName");
                    String description = textOf(view, "description");
                    List<PloceusKey> tags = FormInputs.parseTags(view.getText("tags"));
                    scheduler.async(() -> {
                        OperationResult<Project> created = projects.create(projectKey, displayName, actor);
                        if (created.isSuccess()) {
                            projects.describe(projectKey, description, actor);
                            tags.forEach(tag -> projects.addTag(projectKey, tag, actor));
                        }
                        scheduler.sync(() -> feedback(player, created, "project.created"));
                    });
                })));
    }

    @Override
    public void editProjectForm(Player player, Project project) {
        var nameInput = DialogInput.text("displayName", Messages.get("form.displayName"))
                .initial(project.getDisplayName()).build();
        var descriptionInput = DialogInput.text("description", Messages.get("form.description"))
                .initial(project.getDescription()).build();
        var statusInput = DialogInput.singleOption("status", Messages.get("form.status"),
                        Arrays.stream(ProjectStatus.values())
                                .map(status -> SingleOptionDialogInput.OptionEntry.create(
                                        status.name(), Component.text(status.name()), status == project.getStatus()))
                                .toList())
                .build();

        player.showDialog(DialogFactory.notice(
                Component.text(project.getDisplayName()), List.of(),
                List.of(nameInput, descriptionInput, statusInput),
                submitButton(player, "ploceus.project.edit", view -> {
                    Actor actor = new Actor(player.getUniqueId(), player.getName());
                    PloceusKey key = project.getKey();
                    String displayName = textOf(view, "displayName");
                    String description = textOf(view, "description");
                    String status = textOf(view, "status");
                    scheduler.async(() -> {
                        projects.rename(key, displayName, actor);
                        projects.describe(key, description, actor);
                        OperationResult<Project> result =
                                projects.setStatus(key, ProjectStatus.valueOf(status), actor);
                        scheduler.sync(() -> feedback(player, result, "project.updated"));
                    });
                })));
    }

    /// Two actions instead of `notice`'s one: "Create" submits, "Suggest another name"
    /// reopens this same form with a fresh suggestion, because the Dialog API cannot change
    /// a value inside an already-open dialog. Every value the player typed besides the name
    /// is lost on re-roll — an accepted trade-off for a name suggester, not a general-purpose
    /// form refresh. The generator/environment pickers and the structures toggle are
    /// pre-filled from {@link WorldCreationService#defaultsFor}, i.e. the project's preset
    /// falling back to the config default; the player may still override any of them, and a
    /// deviating generator is recorded to the audit trail by the service, not here.
    ///
    /// Both buttons are built through this class's own `submitButton`/`button` helpers —
    /// the same mandatory-permission factories every other form in this class uses — so
    /// `ploceus.world.create` is re-checked the instant either callback fires, exactly like
    /// `createProjectForm` and `editProjectForm`.
    @Override
    public void createWorldForm(Player player, Project project, String suggestedName,
                                List<String> generators) {
        WorldDefaults defaults = creation.defaultsFor(project);

        var worldNameInput = DialogInput.text("worldName", Messages.get("form.key"))
                .initial(suggestedName).build();
        var displayNameInput = DialogInput.text("displayName", Messages.get("form.displayName"))
                .initial(suggestedName).build();
        var generatorInput = DialogInput.singleOption("generator", Messages.get("form.generator"),
                        generatorOptions(generators, defaults.getGenerator()))
                .build();
        var environmentInput = DialogInput.singleOption("environment", Messages.get("form.environment"),
                        environmentOptions(defaults.getEnvironment()))
                .build();
        var structuresInput = DialogInput.bool("structures", Messages.get("form.structures"))
                .initial(defaults.isGenerateStructures()).build();
        var seedInput = DialogInput.text("seed", Messages.get("form.seed")).build();

        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(submitButton(player, "ploceus.world.create", view -> {
            Long seed = parseSeed(view.getText("seed"));
            var form = new WorldCreationService.WorldCreationForm(
                    textOf(view, "worldName"), textOf(view, "displayName"),
                    textOf(view, "generator"), "", textOf(view, "environment"),
                    Boolean.TRUE.equals(view.getBoolean("structures")), seed);
            Actor actor = new Actor(player.getUniqueId(), player.getName());
            // `creation.create` crosses threads on its own (main thread only for the
            // Multiverse calls) and completes on whichever thread its last phase runs on —
            // same contract as `MultiverseAdapter.teleport` — so the continuation still needs
            // its own hop back to main before touching the player.
            creation.create(project, form, actor).thenAccept(result -> scheduler.sync(() -> {
                feedback(player, result.map(WorldCreationService.CreationOutcome::world), "world.created");
                if (result instanceof OperationResult.Success<WorldCreationService.CreationOutcome> success) {
                    success.value().warnings().forEach(warning -> player.sendMessage(
                            Messages.chat(warning.messageKey(), placeholder("detail", warning.detail()))));
                }
            }));
        }));
        addIfPermitted(buttons, player, "ploceus.world.create", Messages.get("button.reroll"),
                () -> rerollWorldName(player, project, generators));

        player.showDialog(DialogFactory.multiAction(
                Component.text(project.getDisplayName()),
                List.of(worldNameInput, displayNameInput, generatorInput, environmentInput,
                        structuresInput, seedInput),
                buttons, COLUMNS));
    }

    /// Re-suggests a name unique against every currently known world and reopens the form
    /// with it, discarding whatever else the player had typed — the Dialog API cannot patch
    /// a value inside an already-open dialog, so a fresh dialog is the only way to show a
    /// new suggestion.
    private void rerollWorldName(Player player, Project project, List<String> generators) {
        scheduler.async(() -> {
            java.util.Set<String> taken = worlds.all().stream()
                    .map(ProjectWorld::getWorldName)
                    .collect(java.util.stream.Collectors.toSet());
            String fresh = suggester.suggestUnique(taken::contains);
            scheduler.sync(() -> createWorldForm(player, project, fresh, generators));
        });
    }

    /// The vanilla option (empty generator string) always comes first so the picker is never
    /// empty even when Multiverse reports no installed generator plugins; it is selected by
    /// default whenever the resolved default generator is blank.
    private List<SingleOptionDialogInput.OptionEntry> generatorOptions(List<String> generators,
                                                                       String defaultGenerator) {
        List<SingleOptionDialogInput.OptionEntry> options = new ArrayList<>();
        boolean defaultIsVanilla = defaultGenerator == null || defaultGenerator.isBlank();
        options.add(SingleOptionDialogInput.OptionEntry.create(
                "", Messages.get("form.generator.vanilla"), defaultIsVanilla));
        for (String generator : generators) {
            options.add(SingleOptionDialogInput.OptionEntry.create(
                    generator, Component.text(generator), generator.equals(defaultGenerator)));
        }
        return List.copyOf(options);
    }

    private List<SingleOptionDialogInput.OptionEntry> environmentOptions(String defaultEnvironment) {
        return Arrays.stream(new String[] {"NORMAL", "NETHER", "THE_END"})
                .map(name -> SingleOptionDialogInput.OptionEntry.create(
                        name, Component.text(name), name.equals(defaultEnvironment)))
                .toList();
    }

    /// A blank seed means "random"; anything that does not parse as a `long` is hashed into
    /// one rather than rejected, so a memorable phrase works as a seed too.
    private Long parseSeed(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException exception) {
            return (long) raw.trim().hashCode();
        }
    }

    // --- helpers: every one of these loads asynchronously and shows synchronously ---

    /// Every world button teleports, gated by `ploceus.world.teleport` through the same
    /// mandatory-permission factory as every other button; a player without it gets no
    /// world buttons at all, since {@link DialogCallbacks#button} returns `null` per item.
    private List<ActionButton> worldButtons(Player player, List<ProjectWorld> items) {
        return items.stream()
                .map(world -> button(player, "ploceus.world.teleport",
                        Component.text(world.getDisplayName()),
                        Messages.get("world.entry",
                                placeholder("name", world.getWorldName()),
                                Messages.component("state", loadStateLabel(world))),
                        () -> {
                            player.sendMessage(Messages.chat("world.teleporting",
                                    placeholder("name", world.getDisplayName())));
                            player.closeDialog();
                            lifecycle.teleport(player, world).thenAccept(result -> {
                                if (!result.isSuccess()) {
                                    scheduler.sync(() -> feedback(player, result, ""));
                                }
                            });
                        }))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /// Bukkit's world registry is the source of truth for "loaded"; this reads it while on
    /// the main thread (every caller reaches this from a `scheduler.sync` continuation).
    private Component loadStateLabel(ProjectWorld world) {
        boolean loaded = Bukkit.getWorld(world.getWorldName()) != null;
        return Messages.get(loaded ? "world.state.loaded" : "world.state.unloaded");
    }

    private void openProjectList(Player player, int pageNumber) {
        scheduler.async(() -> {
            Page<Project> page = Page.of(projects.all(), pageNumber, config.entriesPerPage());
            scheduler.sync(() -> projectList(player, page));
        });
    }

    private void openProjectDetail(Player player, PloceusKey key) {
        scheduler.async(() -> projects.find(key).ifPresentOrElse(project -> {
            List<ProjectWorld> projectWorlds = worlds.byProject(project);
            scheduler.sync(() -> projectDetail(player, project, projectWorlds));
        }, () -> scheduler.sync(() -> feedback(player,
                OperationResult.failure("project.unknown", key.asString()), ""))));
    }

    private void openWorldList(Player player, PloceusKey projectKey, int pageNumber) {
        scheduler.async(() -> projects.find(projectKey).ifPresent(project -> {
            Page<ProjectWorld> page = Page.of(worlds.byProject(project), pageNumber, config.entriesPerPage());
            scheduler.sync(() -> worldList(player, project, page));
        }));
    }

    private void openUnassigned(Player player, int pageNumber) {
        scheduler.async(() -> {
            Page<ProjectWorld> page = Page.of(worlds.unassigned(), pageNumber, config.entriesPerPage());
            scheduler.sync(() -> unassignedWorlds(player, page));
        });
    }

    /// `AuditService` exposes only `history(target, limit, offset)`, with no total-count
    /// query. One extra row is fetched per page to detect whether a next page exists, so
    /// `Page.hasNext` stays accurate without a count query — the same trick the command
    /// layer uses in `PloceusCommands.auditTrail`.
    private void openAudit(Player player, PloceusKey target, int pageNumber) {
        scheduler.async(() -> {
            int size = config.entriesPerPage();
            List<AuditEntry> fetched = audit.history(target, size + 1, pageNumber * size);
            boolean hasNext = fetched.size() > size;
            List<AuditEntry> items = hasNext ? fetched.subList(0, size) : fetched;
            int totalItems = pageNumber * size + items.size() + (hasNext ? 1 : 0);
            Page<AuditEntry> page = new Page<>(items, pageNumber, size, totalItems);
            scheduler.sync(() -> auditTrail(player, target, page));
        });
    }

    /// A submit button whose callback reads the entered values, gated by `permission`
    /// immediately before `onSubmit` runs. Forms don't go through
    /// {@link DialogCallbacks#button} because a submit button's callback needs the
    /// `DialogResponseView` (a `Consumer<DialogResponseView>`, not a bare `Runnable`) — but
    /// the permission is still mandatory here for the same reason: every form is reachable
    /// only via a path the caller already permission-checked (a command's `@Permission`, or
    /// this class's own render-gated menu buttons), yet the callback is still a
    /// client-driven custom-click payload, so it is re-checked the instant it fires, exactly
    /// like every other callback in this class.
    private ActionButton submitButton(Player player, String permission, Consumer<DialogResponseView> onSubmit) {
        return ActionButton.builder(Messages.get("button.confirm"))
                .action(DialogAction.customClick(
                        (view, audience) -> {
                            if (permitted(player, permission)) {
                                onSubmit.accept(view);
                            }
                        },
                        ClickCallback.Options.builder().uses(ClickCallback.UNLIMITED_USES).build()))
                .build();
    }

    private static String textOf(DialogResponseView view, String key) {
        String value = view.getText(key);
        return value == null ? "" : value;
    }

    private ActionButton exitButton(Player player) {
        return button(player, "ploceus.use", Messages.get("button.close"), () -> { });
    }

    private ActionButton backTo(Player player, Runnable target) {
        return button(player, "ploceus.use", Messages.get("page.back"), target);
    }

    private Component tagList(Project project) {
        List<String> tagValues = project.getTags().stream()
                .map(tag -> tag.getKey().value())
                .toList();
        return tagValues.isEmpty()
                ? Messages.get("project.noTags")
                : Component.text(String.join(", ", tagValues));
    }

    // --- permission enforcement ---

    /// This class's only entry point into {@link DialogCallbacks#button}: fills in
    /// `() -> denyPermission(player)` as the denial callback so call sites never repeat it.
    private ActionButton button(Player player, String permission, Component label, Component tooltip,
                                Runnable action) {
        return DialogCallbacks.button(player, permission, label, tooltip, () -> denyPermission(player), action);
    }

    private ActionButton button(Player player, String permission, Component label, Runnable action) {
        return button(player, permission, label, null, action);
    }

    /// Render-time half of the control for lists built one item at a time: a player who may
    /// not perform `permission` never even sees the button, because {@link #button} already
    /// returns `null` for them.
    private void addIfPermitted(List<ActionButton> buttons, Player player, String permission,
                                Component label, Component tooltip, Runnable action) {
        ActionButton built = button(player, permission, label, tooltip, action);
        if (built != null) {
            buttons.add(built);
        }
    }

    private void addIfPermitted(List<ActionButton> buttons, Player player, String permission,
                                Component label, Runnable action) {
        addIfPermitted(buttons, player, permission, label, null, action);
    }

    /// The check that actually matters: dialog actions are client-driven custom-click
    /// payloads, so a forged client could invoke a registered callback without ever having
    /// been shown the button that would normally trigger it. Used directly only where a
    /// `Runnable` needs a *second*, explicit guard beyond what {@link #button} already gives
    /// its own click (the delete confirmation's "Yes", and each form's `submitButton`).
    /// Delegates to {@link UiPermissions}, which holds the actual (`Player`-free, unit
    /// testable) enforcement logic.
    private boolean permitted(Player player, String permission) {
        return UiPermissions.permitted(player::hasPermission, permission, () -> denyPermission(player));
    }

    /// Wraps a `Runnable` action so the permission is re-checked the instant it fires, not
    /// just when the button that offers it was rendered.
    private Runnable guarded(Player player, String permission, Runnable action) {
        return UiPermissions.guarded(player::hasPermission, permission, () -> denyPermission(player), action);
    }

    private void denyPermission(Player player) {
        player.showDialog(DialogFactory.notice(Messages.get("menu.title"),
                List.of(DialogBody.plainMessage(Messages.get("error.noPermission"))),
                List.of(), backTo(player, () -> mainMenu(player))));
    }
}
