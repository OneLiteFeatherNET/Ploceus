package net.onelitefeather.ploceus.ui;

import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.onelitefeather.ploceus.domain.*;
import org.bukkit.entity.Player;

import java.util.List;

/// Every screen Ploceus can show. All three channels implement this identically, so the
/// same contract test suite runs against each of them.
public interface ProjectUi {

    void mainMenu(Player player);

    void projectList(Player player, Page<Project> page);

    void projectDetail(Player player, Project project, List<ProjectWorld> worlds);

    void worldList(Player player, Project project, Page<ProjectWorld> page);

    void unassignedWorlds(Player player, Page<ProjectWorld> page);

    void searchPrompt(Player player);

    void searchResults(Player player, String term, Page<Project> projects, Page<ProjectWorld> worlds);

    void createProjectForm(Player player);

    void editProjectForm(Player player, Project project);

    void createWorldForm(Player player, Project project, String suggestedName, List<String> generators);

    void auditTrail(Player player, PloceusKey target, Page<AuditEntry> entries);

    void confirm(Player player, String messageKey, Runnable onConfirm);

    void feedback(Player player, OperationResult<?> result, String successKey);

    /// The placeholders a success message is allowed to resolve, derived from the value the
    /// operation actually produced.
    ///
    /// `feedback` used to render its success key with no placeholders at all, while
    /// `project.created`, `project.updated`, `project.deleted`, `world.assigned`,
    /// `world.renamed` and `world.created` all carry `<name>` — and MiniMessage leaves an
    /// unresolved tag as literal text, so every successful operation told the player
    /// "Project &lt;name&gt; created." Rather than thread an operand through all eleven call
    /// sites, it is taken from the result itself: every one of those operations returns the
    /// entity it just changed, which is exactly the thing the message wants to name.
    ///
    /// A value that is not a named entity contributes no placeholders, which is correct for
    /// the messages that have none (`world.settingsUpdated`).
    static TagResolver[] successOperands(OperationResult.Success<?> success) {
        return switch (success.value()) {
            case ProjectWorld world when !world.isUnassigned() -> new TagResolver[] {
                    Messages.placeholder("name", world.getDisplayName()),
                    Messages.placeholder("project", world.getProject().getDisplayName())};
            case Describable describable -> new TagResolver[] {
                    Messages.placeholder("name", describable.getDisplayName())};
            case null, default -> new TagResolver[0];
        };
    }
}
