package net.onelitefeather.ploceus.ui.chat;

import jakarta.inject.Inject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.onelitefeather.ploceus.domain.*;
import net.onelitefeather.ploceus.ui.Messages;
import net.onelitefeather.ploceus.ui.Page;
import net.onelitefeather.ploceus.ui.ProjectUi;
import org.bukkit.entity.Player;

import java.time.format.DateTimeFormatter;
import java.time.ZoneId;
import java.util.List;

import static net.onelitefeather.ploceus.ui.Messages.placeholder;

public class ChatUi implements ProjectUi {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    @Inject
    public ChatUi() { }

    @Override
    public void mainMenu(Player player) {
        player.sendMessage(Messages.chat("menu.title"));
        player.sendMessage(Messages.get("menu.projects")
                .clickEvent(ClickEvent.runCommand("/ploceus project list")));
        player.sendMessage(Messages.get("menu.unassigned")
                .clickEvent(ClickEvent.runCommand("/ploceus world list-unassigned")));
        player.sendMessage(Messages.get("menu.search")
                .clickEvent(ClickEvent.suggestCommand("/ploceus search ")));
    }

    @Override
    public void projectList(Player player, Page<Project> page) {
        player.sendMessage(Messages.chat("project.list.title",
                placeholder("page", String.valueOf(page.pageNumber() + 1)),
                placeholder("pages", String.valueOf(page.totalPages()))));
        for (Project project : page.items()) {
            player.sendMessage(Messages.get("project.entry",
                            placeholder("name", project.getDisplayName()),
                            placeholder("worlds", String.valueOf(project.getWorldCount())),
                            Messages.component("tags", tagList(project)))
                    .clickEvent(ClickEvent.runCommand(
                            "/ploceus project info " + project.getKey().value())));
        }
        sendPagination(player, page.pageNumber(), page.hasPrevious(), page.hasNext(),
                "/ploceus project list ");
    }

    @Override
    public void projectDetail(Player player, Project project, List<ProjectWorld> worlds) {
        player.sendMessage(Messages.chat("project.entry",
                placeholder("name", project.getDisplayName()),
                placeholder("worlds", String.valueOf(worlds.size())),
                Messages.component("tags", tagList(project))));
        player.sendMessage(Component.text(project.getDescription()));
        worlds.forEach(world -> player.sendMessage(worldLine(world)));
    }

    @Override
    public void worldList(Player player, Project project, Page<ProjectWorld> page) {
        page.items().forEach(world -> player.sendMessage(worldLine(world)));
        sendPagination(player, page.pageNumber(), page.hasPrevious(), page.hasNext(),
                "/ploceus world list " + project.getKey().value() + " ");
    }

    @Override
    public void unassignedWorlds(Player player, Page<ProjectWorld> page) {
        page.items().forEach(world -> player.sendMessage(worldLine(world)));
        sendPagination(player, page.pageNumber(), page.hasPrevious(), page.hasNext(),
                "/ploceus world list-unassigned ");
    }

    @Override
    public void searchPrompt(Player player) {
        player.sendMessage(Messages.chat("search.prompt")
                .clickEvent(ClickEvent.suggestCommand("/ploceus search ")));
    }

    @Override
    public void searchResults(Player player, String term, Page<Project> projects, Page<ProjectWorld> worlds) {
        if (projects.totalItems() == 0 && worlds.totalItems() == 0) {
            player.sendMessage(Messages.chat("search.empty", placeholder("term", term)));
            return;
        }
        player.sendMessage(Messages.chat("search.results", placeholder("term", term)));
        projects.items().forEach(project -> player.sendMessage(Messages.get("project.entry",
                        placeholder("name", project.getDisplayName()),
                        placeholder("worlds", String.valueOf(project.getWorldCount())),
                        Messages.component("tags", tagList(project)))
                .clickEvent(ClickEvent.runCommand("/ploceus project info " + project.getKey().value()))));
        worlds.items().forEach(world -> player.sendMessage(worldLine(world)));
    }

    /// Chat cannot render a form. It suggests the equivalent command instead, which is
    /// exactly what this channel exists for.
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

    @Override
    public void createWorldForm(Player player, Project project, String suggestedName, List<String> generators) {
        player.sendMessage(Messages.chat("form.displayName")
                .clickEvent(ClickEvent.suggestCommand("/ploceus world create "
                        + project.getKey().value() + " " + suggestedName)));
    }

    @Override
    public void auditTrail(Player player, PloceusKey target, Page<AuditEntry> entries) {
        for (AuditEntry entry : entries.items()) {
            player.sendMessage(Messages.get("audit.entry",
                    placeholder("at", TIMESTAMP.format(entry.getOccurredAt())),
                    placeholder("actor", entry.getActorName()),
                    placeholder("action", entry.getAction().name()),
                    placeholder("field", entry.getField() == null ? "" : entry.getField()),
                    placeholder("old", entry.getOldValue()),
                    placeholder("new", entry.getNewValue())));
        }
        sendPagination(player, entries.pageNumber(), entries.hasPrevious(), entries.hasNext(),
                "/ploceus audit " + target.value() + " ");
    }

    /// Chat has no modal. It runs the action immediately; the command layer is expected to
    /// have required an explicit `--confirm` flag before reaching here.
    @Override
    public void confirm(Player player, String messageKey, Runnable onConfirm) {
        player.sendMessage(Messages.chat(messageKey));
        onConfirm.run();
    }

    @Override
    public void feedback(Player player, OperationResult<?> result, String successKey) {
        switch (result) {
            case OperationResult.Success<?> ignored -> player.sendMessage(Messages.chat(successKey));
            case OperationResult.Failure<?> failure -> player.sendMessage(Messages.chat(
                    failure.messageKey(), placeholder("detail", failure.detail())));
        }
    }

    private Component worldLine(ProjectWorld world) {
        String stateKey = world.getLastVisitedAt() == null
                ? "world.state.neverVisited"
                : "world.state.visited";
        return Messages.get("world.entry",
                        placeholder("name", world.getDisplayName()),
                        Messages.component("state", Messages.get(stateKey)))
                .clickEvent(ClickEvent.runCommand("/ploceus world tp " + world.getKey().value()));
    }

    private Component tagList(Project project) {
        List<String> tagValues = project.getTags().stream()
                .map(tag -> tag.getKey().value())
                .toList();
        return tagValues.isEmpty()
                ? Messages.get("project.noTags")
                : Component.text(String.join(", ", tagValues));
    }

    private void sendPagination(Player player, int pageNumber, boolean hasPrevious, boolean hasNext,
                                String commandPrefix) {
        Component line = Component.empty();
        if (hasPrevious) {
            line = line.append(Messages.get("page.previous")
                    .clickEvent(ClickEvent.runCommand(commandPrefix + (pageNumber - 1))));
        }
        if (hasNext) {
            line = line.append(Component.space()).append(Messages.get("page.next")
                    .clickEvent(ClickEvent.runCommand(commandPrefix + (pageNumber + 1))));
        }
        if (!line.equals(Component.empty())) {
            player.sendMessage(line);
        }
    }
}
