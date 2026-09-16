package net.onelitefeather.ploceus.ui.inventory;

import com.github.stefvanschie.inventoryframework.adventuresupport.ComponentHolder;
import com.github.stefvanschie.inventoryframework.adventuresupport.TextHolder;
import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui;
import com.github.stefvanschie.inventoryframework.pane.OutlinePane;
import com.github.stefvanschie.inventoryframework.pane.StaticPane;
import com.github.stefvanschie.inventoryframework.pane.util.Slot;
import jakarta.inject.Inject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.onelitefeather.ploceus.PloceusConfig;
import net.onelitefeather.ploceus.domain.*;
import net.onelitefeather.ploceus.platform.SchedulerAdapter;
import net.onelitefeather.ploceus.service.*;
import net.onelitefeather.ploceus.ui.Messages;
import net.onelitefeather.ploceus.ui.Page;
import net.onelitefeather.ploceus.ui.ProjectUi;
import net.onelitefeather.ploceus.ui.UiPermissions;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import static net.onelitefeather.ploceus.ui.Messages.placeholder;

/// The fallback channel: a chest GUI built with InventoryFramework, for clients too old for
/// Paper's Dialog API (the server runs ViaVersion, so such clients do connect), and the
/// visual world browser — item icons ({@link WorldIcons}) say load state and role at a
/// glance, which is the reason this channel exists alongside dialogs at all.
///
/// Every navigation opens a fresh `ChestGui`, mirroring `DialogUi`'s "no in-place refresh"
/// approach for consistency, even though inventories could technically be updated in place.
/// Pagination is `Ploceus`'s own: every list method already receives a pre-sliced
/// `Page<T>` (the same contract all three channels share), so InventoryFramework's own
/// multi-page `PaginatedPane` machinery would be redundant here — it would only ever hold
/// one internal page, since a `Page`'s size is bounded by `config.entriesPerPage()`. Lists
/// use a plain `OutlinePane` for the item grid instead, with a hand-built previous/back/next
/// row that re-requests a new `Page` from the service, exactly like `DialogUi`'s pagination.
///
/// ## Permissions
///
/// An `InventoryClickEvent` is exactly as client-driven as a dialog's custom-click
/// callback: nothing stops a modified client from sending a click for a slot it was never
/// shown. Every item that reaches a service in this class is therefore built through
/// {@link #item}, which requires a permission node and mirrors `DialogCallbacks.button`
/// exactly: it returns no item (and registers no click handler) when the player lacks the
/// node, and wraps the action in an execute-time re-check when they have it.
public class InventoryUi implements ProjectUi {

    private static final int ROWS = 6;
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final ProjectService projects;
    private final WorldService worlds;
    private final WorldLifecycleService lifecycle;
    private final AuditService audit;
    private final SchedulerAdapter scheduler;
    private final PloceusConfig config;
    private final Plugin plugin;

    @Inject
    public InventoryUi(ProjectService projects, WorldService worlds, WorldLifecycleService lifecycle,
                       AuditService audit, SchedulerAdapter scheduler, PloceusConfig config, Plugin plugin) {
        this.projects = projects;
        this.worlds = worlds;
        this.lifecycle = lifecycle;
        this.audit = audit;
        this.scheduler = scheduler;
        this.config = config;
        this.plugin = plugin;
    }

    @Override
    public void mainMenu(Player player) {
        ChestGui gui = new ChestGui(3, title(Messages.get("menu.title")), plugin);
        StaticPane pane = new StaticPane(9, 3);
        addIfPermitted(pane, player, "ploceus.project.list", Material.BOOKSHELF,
                Messages.get("menu.projects"), 2, 1, () -> openProjectList(player, 0));
        addIfPermitted(pane, player, "ploceus.world.list", Material.CHEST,
                Messages.get("menu.unassigned"), 4, 1, () -> openUnassigned(player, 0));
        addIfPermitted(pane, player, "ploceus.search", Material.COMPASS,
                Messages.get("menu.search"), 6, 1, () -> searchPrompt(player));
        gui.addPane(Slot.fromXY(0, 0), pane);
        gui.show(player);
    }

    @Override
    public void projectList(Player player, Page<Project> page) {
        ChestGui gui = new ChestGui(ROWS, title(Messages.get("project.list.title",
                placeholder("page", String.valueOf(page.pageNumber() + 1)),
                placeholder("pages", String.valueOf(page.totalPages())))), plugin);
        OutlinePane items = new OutlinePane(9, ROWS - 1);
        page.items().stream()
                .map(project -> projectItem(player, project))
                .filter(Objects::nonNull)
                .forEach(items::addItem);
        gui.addPane(Slot.fromXY(0, 0), items);
        gui.addPane(Slot.fromXY(0, ROWS - 1), navigationRow(player, page,
                target -> openProjectList(player, target), () -> mainMenu(player)));
        gui.show(player);
    }

    @Override
    public void projectDetail(Player player, Project project, List<ProjectWorld> projectWorlds) {
        ChestGui gui = new ChestGui(3, title(Component.text(project.getDisplayName())), plugin);
        StaticPane pane = new StaticPane(9, 3);
        addIfPermitted(pane, player, "ploceus.world.list", Material.CHEST,
                Messages.get("menu.worlds", placeholder("count", String.valueOf(projectWorlds.size()))),
                1, 1, () -> openWorldList(player, project.getKey(), 0));
        addIfPermitted(pane, player, "ploceus.project.edit", Material.WRITABLE_BOOK,
                Messages.get("menu.edit"), 3, 1, () -> editProjectForm(player, project));
        addIfPermitted(pane, player, "ploceus.audit", Material.PAPER,
                Messages.get("menu.audit"), 5, 1, () -> openAudit(player, project.getKey(), 0));
        // Deliberate second check on the most destructive action, mirroring DialogUi: the
        // delete item's own click already re-checks `ploceus.project.delete`, and the
        // confirmation's "Yes" is wrapped in its own explicit `guarded(...)` for the same
        // permission, so deletion is checked once when the delete item fires and again when
        // the confirmation is actually accepted.
        addIfPermitted(pane, player, "ploceus.project.delete", Material.BARRIER,
                Messages.get("menu.delete"), 7, 1, () -> confirm(player, "project.delete.prompt",
                        guarded(player, "ploceus.project.delete", () -> {
                            Actor actor = new Actor(player.getUniqueId(), player.getName());
                            scheduler.async(() -> {
                                var result = projects.delete(project.getKey(), actor);
                                scheduler.sync(() -> feedback(player, result, "project.deleted"));
                            });
                        })));
        addIfPermitted(pane, player, "ploceus.use", Material.ARROW,
                Messages.get("page.back"), 4, 2, () -> openProjectList(player, 0));
        gui.addPane(Slot.fromXY(0, 0), pane);
        gui.show(player);
    }

    @Override
    public void worldList(Player player, Project project, Page<ProjectWorld> page) {
        ChestGui gui = new ChestGui(ROWS, title(Component.text(project.getDisplayName())), plugin);
        OutlinePane items = new OutlinePane(9, ROWS - 1);
        page.items().stream()
                .map(world -> worldItem(player, world))
                .filter(Objects::nonNull)
                .forEach(items::addItem);
        gui.addPane(Slot.fromXY(0, 0), items);
        gui.addPane(Slot.fromXY(0, ROWS - 1), navigationRow(player, page,
                target -> openWorldList(player, project.getKey(), target),
                () -> openProjectDetail(player, project.getKey())));
        gui.show(player);
    }

    @Override
    public void unassignedWorlds(Player player, Page<ProjectWorld> page) {
        ChestGui gui = new ChestGui(ROWS, title(Messages.get("menu.unassigned")), plugin);
        OutlinePane items = new OutlinePane(9, ROWS - 1);
        page.items().stream()
                .map(world -> worldItem(player, world))
                .filter(Objects::nonNull)
                .forEach(items::addItem);
        gui.addPane(Slot.fromXY(0, 0), items);
        gui.addPane(Slot.fromXY(0, ROWS - 1), navigationRow(player, page,
                target -> openUnassigned(player, target), () -> mainMenu(player)));
        gui.show(player);
    }

    @Override
    public void searchResults(Player player, String term, Page<Project> foundProjects,
                              Page<ProjectWorld> foundWorlds) {
        Component titleText = foundProjects.totalItems() == 0 && foundWorlds.totalItems() == 0
                ? Messages.get("search.empty", placeholder("term", term))
                : Messages.get("search.results", placeholder("term", term));
        ChestGui gui = new ChestGui(ROWS, title(titleText), plugin);
        OutlinePane items = new OutlinePane(9, ROWS - 1);
        foundProjects.items().stream()
                .map(project -> projectItem(player, project))
                .filter(Objects::nonNull)
                .forEach(items::addItem);
        foundWorlds.items().stream()
                .map(world -> worldItem(player, world))
                .filter(Objects::nonNull)
                .forEach(items::addItem);
        gui.addPane(Slot.fromXY(0, 0), items);
        StaticPane nav = new StaticPane(9, 1);
        addIfPermitted(nav, player, "ploceus.use", Material.ARROW,
                Messages.get("page.back"), 4, 0, () -> mainMenu(player));
        gui.addPane(Slot.fromXY(0, ROWS - 1), nav);
        gui.show(player);
    }

    /// The inventory channel cannot take text input. It hands the player back to chat for
    /// anything that needs typing — the same reason this channel exists as a fallback.
    @Override
    public void searchPrompt(Player player) {
        player.sendMessage(Messages.chat("search.prompt")
                .clickEvent(ClickEvent.suggestCommand("/ploceus search ")));
    }

    @Override
    public void createProjectForm(Player player) {
        player.sendMessage(Messages.chat("form.key")
                .clickEvent(ClickEvent.suggestCommand("/ploceus project create ")));
    }

    @Override
    public void editProjectForm(Player player, Project project) {
        player.sendMessage(Messages.chat("form.displayName")
                .clickEvent(ClickEvent.suggestCommand(
                        "/ploceus project rename " + project.getKey().value() + " ")));
    }

    /// World-creation form arrives with Task 18. Falls back to suggesting the command, like
    /// every other text-input form on this channel.
    @Override
    public void createWorldForm(Player player, Project project, String suggestedName, List<String> generators) {
        player.sendMessage(Messages.chat("form.displayName")
                .clickEvent(ClickEvent.suggestCommand("/ploceus world create "
                        + project.getKey().value() + " " + suggestedName)));
    }

    @Override
    public void auditTrail(Player player, PloceusKey target, Page<AuditEntry> entries) {
        ChestGui gui = new ChestGui(ROWS, title(Messages.get("audit.title")), plugin);
        OutlinePane items = new OutlinePane(9, ROWS - 1);
        entries.items().stream()
                .map(entry -> infoItem(Material.PAPER, Messages.get("audit.entry",
                        placeholder("at", TIMESTAMP.format(entry.getOccurredAt())),
                        placeholder("actor", entry.getActorName()),
                        placeholder("action", entry.getAction().name()),
                        placeholder("field", entry.getField() == null ? "" : entry.getField()),
                        placeholder("old", entry.getOldValue()),
                        placeholder("new", entry.getNewValue()))))
                .forEach(items::addItem);
        gui.addPane(Slot.fromXY(0, 0), items);
        gui.addPane(Slot.fromXY(0, ROWS - 1), navigationRow(player, entries,
                pageNumber -> openAudit(player, target, pageNumber), () -> openProjectDetail(player, target)));
        gui.show(player);
    }

    @Override
    public void confirm(Player player, String messageKey, Runnable onConfirm) {
        // Mirrors `DialogUi.confirm`: `onConfirm` is supplied by the caller and is expected
        // to already be wrapped in `guarded(...)` for whatever permission the confirmed
        // action needs, so the "Yes" item here only asserts `ploceus.use` (reaching a
        // confirm screen at all). "No" only closes the inventory, which is never privileged.
        ChestGui gui = new ChestGui(3, title(Messages.get("menu.title")), plugin);
        StaticPane pane = new StaticPane(9, 3);
        pane.addItem(infoItem(Material.PAPER, Messages.get(messageKey)), 4, 0);
        addIfPermitted(pane, player, "ploceus.use", Material.LIME_WOOL,
                Messages.get("button.confirm"), 2, 2, () -> {
                    player.closeInventory();
                    onConfirm.run();
                });
        addIfPermitted(pane, player, "ploceus.use", Material.RED_WOOL,
                Messages.get("button.cancel"), 6, 2, player::closeInventory);
        gui.addPane(Slot.fromXY(0, 0), pane);
        gui.show(player);
    }

    @Override
    public void feedback(Player player, OperationResult<?> result, String successKey) {
        switch (result) {
            case OperationResult.Success<?> ignored -> player.sendMessage(Messages.chat(successKey));
            case OperationResult.Failure<?> failure -> player.sendMessage(Messages.chat(
                    failure.messageKey(), placeholder("detail", failure.detail())));
        }
    }

    // --- helpers: every one of these loads asynchronously and shows synchronously ---

    private GuiItem projectItem(Player player, Project project) {
        return item(player, "ploceus.project.list", WorldIcons.projectIcon(),
                Component.text(project.getDisplayName()), () -> openProjectDetail(player, project.getKey()));
    }

    /// Bukkit's world registry is the source of truth for "loaded", read directly (matching
    /// `DialogUi.loadStateLabel`) rather than through `MultiverseAdapter`, since every caller
    /// reaches this from a `scheduler.sync` continuation and is already on the main thread.
    private GuiItem worldItem(Player player, ProjectWorld world) {
        boolean loaded = Bukkit.getWorld(world.getWorldName()) != null;
        return item(player, "ploceus.world.teleport", WorldIcons.iconFor(world, loaded),
                Component.text(world.getDisplayName()), () -> {
                    player.closeInventory();
                    player.sendMessage(Messages.chat("world.teleporting",
                            placeholder("name", world.getDisplayName())));
                    lifecycle.teleport(player, world).thenAccept(teleportResult -> {
                        if (!teleportResult.isSuccess()) {
                            scheduler.sync(() -> feedback(player, teleportResult, ""));
                        }
                    });
                });
    }

    private StaticPane navigationRow(Player player, Page<?> page, IntConsumer onPage, Runnable onBack) {
        StaticPane nav = new StaticPane(9, 1);
        if (page.hasPrevious()) {
            addIfPermitted(nav, player, "ploceus.use", Material.ARROW,
                    Messages.get("page.previous"), 0, 0, () -> onPage.accept(page.pageNumber() - 1));
        }
        addIfPermitted(nav, player, "ploceus.use", Material.BARRIER, Messages.get("page.back"), 4, 0, onBack);
        if (page.hasNext()) {
            addIfPermitted(nav, player, "ploceus.use", Material.ARROW,
                    Messages.get("page.next"), 8, 0, () -> onPage.accept(page.pageNumber() + 1));
        }
        return nav;
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

    /// Same "fetch one extra row" trick `DialogUi.openAudit` and
    /// `PloceusCommands.auditTrail` use: `AuditService` has no total-count query.
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

    private TextHolder title(Component component) {
        return ComponentHolder.of(component);
    }

    private GuiItem guiItem(Material material, Component name, Consumer<InventoryClickEvent> onClick) {
        ItemStack stack = new ItemStack(material);
        stack.editMeta(meta -> meta.displayName(name));
        return new GuiItem(stack, event -> {
            event.setCancelled(true);
            onClick.accept(event);
        });
    }

    /// A non-interactive item: clicks are still cancelled (so nothing is pulled out of the
    /// GUI) but no action runs. Used for read-only display, like audit entries and the
    /// confirm screen's message.
    private GuiItem infoItem(Material material, Component name) {
        return guiItem(material, name, event -> { });
    }

    // --- permission enforcement ---

    /// The only way to build an inventory item that reaches an action. Mirrors
    /// `DialogCallbacks.button` exactly: returns `null` when `player` lacks `permission`
    /// (render-time half — no item, no click handler registered for this render at all),
    /// and otherwise wraps `action` in an execute-time re-check via
    /// {@link UiPermissions#guarded}, since an `InventoryClickEvent` is exactly as
    /// client-driven as a dialog's custom-click callback.
    /// Package-private rather than private: {@code InventoryPermissionsTest}, in the same
    /// package, calls this directly to prove the denied-permission path never touches Bukkit
    /// (no `ItemStack`/`GuiItem` construction, which this project has no MockBukkit-style
    /// harness to run) and never invokes the action that would reach a service.
    GuiItem item(Player player, String permission, Material material, Component name, Runnable action) {
        if (!player.hasPermission(permission)) {
            return null;
        }
        Runnable guardedAction = UiPermissions.guarded(player::hasPermission, permission,
                () -> denyPermission(player), action);
        return guiItem(material, name, event -> guardedAction.run());
    }

    /// Render-time half of the control for a fixed-position pane: a player who may not
    /// perform `permission` never even sees the item, because {@link #item} already returns
    /// `null` for them.
    private void addIfPermitted(StaticPane pane, Player player, String permission, Material material,
                                Component name, int x, int y, Runnable action) {
        GuiItem built = item(player, permission, material, name, action);
        if (built != null) {
            pane.addItem(built, x, y);
        }
    }

    /// Wraps a `Runnable` action so the permission is re-checked the instant it fires, not
    /// just when the item that offers it was rendered. Used directly only where a `Runnable`
    /// needs a *second*, explicit guard beyond what {@link #item} already gives its own
    /// click — the delete confirmation's "Yes".
    private Runnable guarded(Player player, String permission, Runnable action) {
        return UiPermissions.guarded(player::hasPermission, permission, () -> denyPermission(player), action);
    }

    private void denyPermission(Player player) {
        ChestGui gui = new ChestGui(3, title(Messages.get("menu.title")), plugin);
        StaticPane pane = new StaticPane(9, 3);
        pane.addItem(infoItem(Material.BARRIER, Messages.get("error.noPermission")), 4, 1);
        addIfPermitted(pane, player, "ploceus.use", Material.ARROW,
                Messages.get("page.back"), 4, 2, () -> mainMenu(player));
        gui.addPane(Slot.fromXY(0, 0), pane);
        gui.show(player);
    }
}
