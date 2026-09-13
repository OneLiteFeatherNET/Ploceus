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

import static net.onelitefeather.ploceus.ui.Messages.placeholder;
import static net.onelitefeather.ploceus.ui.dialog.DialogCallbacks.button;

/// The primary channel: Paper's native Dialog API. Every navigation opens a fresh dialog
/// because dialogs cannot be refreshed in place, and lists paginate by hand because dialogs
/// do not scroll.
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
        List<ActionButton> buttons = List.of(
                button(Messages.get("menu.projects"), null, () -> openProjectList(player, 0)),
                button(Messages.get("menu.unassigned"), null, () -> openUnassigned(player, 0)),
                button(Messages.get("menu.search"), null, () -> searchPrompt(player)));
        player.showDialog(DialogFactory.multiAction(
                Messages.get("menu.title"), buttons, exitButton(), COLUMNS));
    }

    @Override
    public void projectList(Player player, Page<Project> page) {
        List<ActionButton> buttons = new ArrayList<>();
        for (Project project : page.items()) {
            buttons.add(button(
                    Component.text(project.getDisplayName()),
                    Messages.get("project.entry",
                            placeholder("name", project.getKey().asString()),
                            placeholder("worlds", String.valueOf(project.getWorlds().size())),
                            Messages.component("tags", tagList(project))),
                    () -> openProjectDetail(player, project.getKey())));
        }
        buttons.addAll(DialogCallbacks.paginationButtons(page, target -> openProjectList(player, target)));
        player.showDialog(DialogFactory.multiAction(
                Messages.get("project.list.title",
                        placeholder("page", String.valueOf(page.pageNumber() + 1)),
                        placeholder("pages", String.valueOf(page.totalPages()))),
                buttons, backTo(() -> mainMenu(player)), COLUMNS));
    }

    @Override
    public void projectDetail(Player player, Project project, List<ProjectWorld> projectWorlds) {
        List<ActionButton> buttons = List.of(
                button(Messages.get("menu.worlds", placeholder("count", String.valueOf(projectWorlds.size()))),
                        null, () -> openWorldList(player, project.getKey(), 0)),
                button(Messages.get("menu.edit"), null, () -> editProjectForm(player, project)),
                button(Messages.get("menu.audit"), null, () -> openAudit(player, project.getKey(), 0)),
                button(Messages.get("menu.delete"), null, () -> confirm(player, "project.delete.prompt", () -> {
                    Actor actor = new Actor(player.getUniqueId(), player.getName());
                    scheduler.async(() -> {
                        var result = projects.delete(project.getKey(), actor);
                        scheduler.sync(() -> feedback(player, result, "project.deleted"));
                    });
                })));
        player.showDialog(DialogFactory.multiAction(
                Component.text(project.getDisplayName()), buttons,
                backTo(() -> openProjectList(player, 0)), COLUMNS));
    }

    @Override
    public void worldList(Player player, Project project, Page<ProjectWorld> page) {
        List<ActionButton> buttons = new ArrayList<>(worldButtons(player, page.items()));
        buttons.addAll(DialogCallbacks.paginationButtons(page,
                target -> openWorldList(player, project.getKey(), target)));
        player.showDialog(DialogFactory.multiAction(
                Component.text(project.getDisplayName()), buttons,
                backTo(() -> openProjectDetail(player, project.getKey())), COLUMNS));
    }

    @Override
    public void unassignedWorlds(Player player, Page<ProjectWorld> page) {
        List<ActionButton> buttons = new ArrayList<>(worldButtons(player, page.items()));
        buttons.addAll(DialogCallbacks.paginationButtons(page, target -> openUnassigned(player, target)));
        player.showDialog(DialogFactory.multiAction(
                Messages.get("menu.unassigned"), buttons, backTo(() -> mainMenu(player)), COLUMNS));
    }

    @Override
    public void searchResults(Player player, String term, Page<Project> foundProjects,
                              Page<ProjectWorld> foundWorlds) {
        List<ActionButton> buttons = new ArrayList<>();
        foundProjects.items().forEach(project -> buttons.add(button(
                Component.text(project.getDisplayName()),
                Messages.get("project.entry",
                        placeholder("name", project.getKey().asString()),
                        placeholder("worlds", String.valueOf(project.getWorlds().size())),
                        Messages.component("tags", tagList(project))),
                () -> openProjectDetail(player, project.getKey()))));
        buttons.addAll(worldButtons(player, foundWorlds.items()));
        Component title = foundProjects.totalItems() == 0 && foundWorlds.totalItems() == 0
                ? Messages.get("search.empty", placeholder("term", term))
                : Messages.get("search.results", placeholder("term", term));
        player.showDialog(DialogFactory.multiAction(title, buttons, backTo(() -> mainMenu(player)), COLUMNS));
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
                pageNumber -> openAudit(player, target, pageNumber)));
        player.showDialog(DialogFactory.multiAction(Messages.get("audit.title"), body, buttons,
                backTo(() -> openProjectDetail(player, target)), COLUMNS));
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
                List.of(), backTo(() -> mainMenu(player))));
    }

    @Override
    public void confirm(Player player, String messageKey, Runnable onConfirm) {
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
    /// environments, seeds) are wired through `MultiverseAdapter`.
    @Override
    public void createWorldForm(Player player, Project project, String suggestedName,
                                List<String> generators) {
        throw new UnsupportedOperationException("Task 18");
    }

    // --- helpers: every one of these loads asynchronously and shows synchronously ---

    private List<ActionButton> worldButtons(Player player, List<ProjectWorld> items) {
        return items.stream().map(world -> button(
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
                })).toList();
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

    /// A submit button whose callback reads the entered values.
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

    private ActionButton exitButton() {
        return button(Messages.get("button.close"), null, () -> { });
    }

    private ActionButton backTo(Runnable target) {
        return button(Messages.get("page.back"), null, target);
    }

    private Component tagList(Project project) {
        List<String> tagValues = project.getTags().stream()
                .map(tag -> tag.getKey().value())
                .toList();
        return tagValues.isEmpty()
                ? Messages.get("project.noTags")
                : Component.text(String.join(", ", tagValues));
    }
}
