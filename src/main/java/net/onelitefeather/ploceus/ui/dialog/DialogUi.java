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
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import static net.onelitefeather.ploceus.ui.Messages.placeholder;
import static net.onelitefeather.ploceus.ui.dialog.DialogCallbacks.button;

/// The primary channel: Paper's native Dialog API. Every navigation opens a fresh dialog
/// because dialogs cannot be refreshed in place, and lists paginate by hand because dialogs
/// do not scroll.
///
/// ## Permissions
///
/// The command layer's `@Permission` annotations only ever gate the Cloud command tree —
/// they say nothing about this class, because Paper dialog buttons are custom-click actions
/// registered server-side and fired by a plain click packet naming a callback id. Two
/// consequences follow, and both are handled here rather than left to the caller:
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
/// `permitted`/`guarded`/`addIfPermitted` below apply both checks from a single call site
/// next to each button, so the permission node lives in exactly one place. Screens that
/// only navigate between places the player already reached (back links, pagination within
/// the same list, the exit button) still re-check `ploceus.use` — cheap, and it means no
/// custom-click callback in this class ever runs unchecked.
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

    @Inject
    public DialogUi(ProjectService projects, WorldService worlds, WorldLifecycleService lifecycle,
                    AuditService audit, SchedulerAdapter scheduler, PloceusConfig config) {
        this.projects = projects;
        this.worlds = worlds;
        this.lifecycle = lifecycle;
        this.audit = audit;
        this.scheduler = scheduler;
        this.config = config;
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
        buttons.addAll(DialogCallbacks.paginationButtons(page,
                guardedPage(player, "ploceus.use", target -> openProjectList(player, target))));
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
        buttons.addAll(DialogCallbacks.paginationButtons(page,
                guardedPage(player, "ploceus.use", target -> openWorldList(player, project.getKey(), target))));
        player.showDialog(DialogFactory.multiAction(
                Component.text(project.getDisplayName()), buttons,
                backTo(player, () -> openProjectDetail(player, project.getKey())), COLUMNS));
    }

    @Override
    public void unassignedWorlds(Player player, Page<ProjectWorld> page) {
        List<ActionButton> buttons = new ArrayList<>(worldButtons(player, page.items()));
        buttons.addAll(DialogCallbacks.paginationButtons(page,
                guardedPage(player, "ploceus.use", target -> openUnassigned(player, target))));
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
        List<ActionButton> buttons = new ArrayList<>(DialogCallbacks.paginationButtons(entries,
                guardedPage(player, "ploceus.use", pageNumber -> openAudit(player, target, pageNumber))));
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
        // has no way to know that permission itself. "No" only closes the dialog, which is
        // never privileged.
        player.showDialog(DialogFactory.confirmation(
                Messages.get("menu.title"),
                List.of(DialogBody.plainMessage(Messages.get(messageKey))),
                button(Messages.get("button.confirm"), null, onConfirm),
                button(Messages.get("button.cancel"), null, player::closeDialog)));
    }

    @Override
    public void searchPrompt(Player player) {
        var term = DialogInput.text("term", Messages.get("menu.search")).build();
        player.showDialog(DialogFactory.notice(
                Messages.get("menu.search"),
                List.of(DialogBody.plainMessage(Messages.get("search.prompt"))),
                List.of(term),
                submitButton(view -> {
                    if (!permitted(player, "ploceus.search")) {
                        return;
                    }
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
                submitButton(view -> {
                    if (!permitted(player, "ploceus.project.create")) {
                        return;
                    }
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
                submitButton(view -> {
                    if (!permitted(player, "ploceus.project.edit")) {
                        return;
                    }
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

    /// World-creation form arrives with Task 18, once world creation options (generators,
    /// environments, seeds) are wired through `MultiverseAdapter`. Its submit callback must
    /// check `ploceus.world.create` the same way the other two forms check theirs.
    @Override
    public void createWorldForm(Player player, Project project, String suggestedName,
                                List<String> generators) {
        throw new UnsupportedOperationException("Task 18");
    }

    // --- helpers: every one of these loads asynchronously and shows synchronously ---

    /// Every world button teleports, so a single permission check up front covers the whole
    /// list instead of one player-and-permission-identical check per item; the click itself
    /// still re-checks via `guarded` for the reason explained on the class.
    private List<ActionButton> worldButtons(Player player, List<ProjectWorld> items) {
        if (!player.hasPermission("ploceus.world.teleport")) {
            return List.of();
        }
        return items.stream().map(world -> button(
                Component.text(world.getDisplayName()),
                Messages.get("world.entry",
                        placeholder("name", world.getWorldName()),
                        Messages.component("state", loadStateLabel(world))),
                guarded(player, "ploceus.world.teleport", () -> {
                    player.sendMessage(Messages.chat("world.teleporting",
                            placeholder("name", world.getDisplayName())));
                    player.closeDialog();
                    lifecycle.teleport(player, world).thenAccept(result -> {
                        if (!result.isSuccess()) {
                            scheduler.sync(() -> feedback(player, result, ""));
                        }
                    });
                }))).toList();
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

    /// A submit button whose callback reads the entered values. Permission checks live
    /// inside each `onSubmit` (via {@link #permitted}), not here, since the permission node
    /// differs per form.
    private ActionButton submitButton(Consumer<DialogResponseView> onSubmit) {
        return ActionButton.builder(Messages.get("button.confirm"))
                .action(DialogAction.customClick(
                        (view, audience) -> onSubmit.accept(view),
                        ClickCallback.Options.builder().uses(ClickCallback.UNLIMITED_USES).build()))
                .build();
    }

    private static String textOf(DialogResponseView view, String key) {
        String value = view.getText(key);
        return value == null ? "" : value;
    }

    private ActionButton exitButton(Player player) {
        return button(Messages.get("button.close"), null, guarded(player, "ploceus.use", () -> { }));
    }

    private ActionButton backTo(Player player, Runnable target) {
        return button(Messages.get("page.back"), null, guarded(player, "ploceus.use", target));
    }

    private Component tagList(Project project) {
        List<String> tagValues = project.getTags().stream()
                .map(tag -> tag.getKey().value())
                .toList();
        return tagValues.isEmpty()
                ? Messages.get("project.noTags")
                : Component.text(String.join(", ", tagValues));
    }

    // --- permission enforcement: render-time gating and execute-time re-checking ---

    /// The check that actually matters: dialog actions are client-driven custom-click
    /// payloads, so a forged client could invoke a registered callback without ever having
    /// been shown the button that would normally trigger it. Every mutating or
    /// data-revealing callback in this class goes through this (directly, or via
    /// {@link #guarded}/{@link #addIfPermitted}) immediately before touching a service.
    /// Delegates to {@link DialogPermissions}, which holds the actual (`Player`-free, unit
    /// testable) enforcement logic.
    private boolean permitted(Player player, String permission) {
        return DialogPermissions.permitted(player::hasPermission, permission, () -> denyPermission(player));
    }

    /// Wraps a button/callback action so the permission is re-checked the instant it fires,
    /// not just when the button was rendered.
    private Runnable guarded(Player player, String permission, Runnable action) {
        return DialogPermissions.guarded(player::hasPermission, permission, () -> denyPermission(player), action);
    }

    /// Same idea as {@link #guarded}, for the `IntConsumer` shape pagination callbacks use.
    private IntConsumer guardedPage(Player player, String permission, IntConsumer target) {
        return pageNumber -> {
            if (permitted(player, permission)) {
                target.accept(pageNumber);
            }
        };
    }

    /// Render-time half of the control: a player who may not perform `permission` never
    /// even sees the button. Paired with {@link #guarded} for the execute-time half, so the
    /// permission node for a given action lives in exactly one call site.
    private void addIfPermitted(List<ActionButton> buttons, Player player, String permission,
                                Component label, Component tooltip, Runnable action) {
        if (player.hasPermission(permission)) {
            buttons.add(button(label, tooltip, guarded(player, permission, action)));
        }
    }

    private void addIfPermitted(List<ActionButton> buttons, Player player, String permission,
                                Component label, Runnable action) {
        addIfPermitted(buttons, player, permission, label, null, action);
    }

    private void denyPermission(Player player) {
        player.showDialog(DialogFactory.notice(Messages.get("menu.title"),
                List.of(DialogBody.plainMessage(Messages.get("error.noPermission"))),
                List.of(), backTo(player, () -> mainMenu(player))));
    }
}
