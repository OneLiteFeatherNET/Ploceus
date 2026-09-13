package net.onelitefeather.ploceus.ui;

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
}
