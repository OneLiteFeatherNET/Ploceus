package net.onelitefeather.ploceus.ui.dialog;

import net.kyori.adventure.text.Component;
import net.onelitefeather.ploceus.domain.Actor;
import net.onelitefeather.ploceus.domain.AuditAction;
import net.onelitefeather.ploceus.domain.AuditEntry;
import net.onelitefeather.ploceus.domain.OperationResult;
import net.onelitefeather.ploceus.domain.PloceusKey;
import net.onelitefeather.ploceus.domain.Project;
import net.onelitefeather.ploceus.domain.ProjectWorld;
import net.onelitefeather.ploceus.ui.Page;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/// A player who holds some Ploceus permission but not `ploceus.use` used to get a
/// `NullPointerException` from every dialog screen — including from `denyPermission` itself.
///
/// `DialogCallbacks.button` returns `null` for such a player, which is correct: no button, no
/// registered callback. `DialogUi` then fed those nulls straight into
/// `DialogFactory.confirmation(yes, no)` and into `exitAction`/`notice(submit)` via
/// `backTo`/`exitButton`, and Paper's `DialogType` factories reject null.
///
/// Every screen must therefore step aside instead of throwing. This drives all thirteen of
/// them with a `Player` double that denies everything and records what it was sent — none of
/// them reaches Paper's dialog registry, so none of them needs a running server either.
class DialogDegradationTest {

    /// All the services are `null` on purpose: a screen that touches one before checking the
    /// permission would fail this test with an NPE of its own, which is exactly the property
    /// being asserted — the check comes first.
    private static final DialogUi UI = new DialogUi(null, null, null, null, null, null, null, null, null, null);

    private static Map<String, Consumer<Player>> screens() {
        Project project = new Project(PloceusKey.of("elytrarace"), "Elytra Race");
        ProjectWorld world = new ProjectWorld(PloceusKey.of("elytrarace-alien"), "elytrarace-alien");
        Page<Project> projects = new Page<>(List.of(project), 0, 10, 1);
        Page<ProjectWorld> worlds = new Page<>(List.of(world), 0, 10, 1);
        Page<AuditEntry> entries = new Page<>(List.of(new AuditEntry(
                AuditEntry.TargetType.PROJECT, PloceusKey.of("elytrarace"), UUID.randomUUID(),
                "TheMeinerLP", AuditAction.PROJECT_CREATED, null, null, "Elytra Race")), 0, 10, 1);

        Map<String, Consumer<Player>> screens = new LinkedHashMap<>();
        screens.put("mainMenu", UI::mainMenu);
        screens.put("projectList", player -> UI.projectList(player, projects));
        screens.put("projectDetail", player -> UI.projectDetail(player, project, List.of(world)));
        screens.put("worldList", player -> UI.worldList(player, project, worlds));
        screens.put("unassignedWorlds", player -> UI.unassignedWorlds(player, worlds));
        screens.put("importableWorlds", player -> UI.importableWorlds(player,
                new Page<>(List.of("elytrarace-desert"), 0, 10, 1)));
        screens.put("searchPrompt", UI::searchPrompt);
        screens.put("searchResults", player -> UI.searchResults(player, "winter", projects, worlds));
        screens.put("createProjectForm", UI::createProjectForm);
        screens.put("editProjectForm", player -> UI.editProjectForm(player, project));
        screens.put("createWorldForm",
                player -> UI.createWorldForm(player, project, "elytrarace-desert", List.of()));
        screens.put("auditTrail", player -> UI.auditTrail(player, PloceusKey.of("elytrarace"), entries));
        screens.put("confirm", player -> UI.confirm(player, "project.delete.prompt", () -> { }));
        screens.put("feedbackOnSuccess", player -> UI.feedback(player,
                OperationResult.success(project), "project.created"));
        screens.put("feedbackOnFailure", player -> UI.feedback(player,
                OperationResult.failure("project.unknown", "ploceus:missing"), ""));
        return screens;
    }

    @TestFactory
    Stream<DynamicTest> noScreenThrowsForAPlayerWithoutTheBasePermission() {
        return screens().entrySet().stream().map(screen -> DynamicTest.dynamicTest(
                screen.getKey() + " degrades instead of throwing", () -> {
                    List<Component> sent = new ArrayList<>();
                    Player player = playerWithoutAnyPermission(sent);

                    assertThatCode(() -> screen.getValue().accept(player))
                            .as("%s threw for a player without ploceus.use", screen.getKey())
                            .doesNotThrowAnyException();
                    assertThat(sent)
                            .as("%s must still tell the player something, in a channel that needs "
                                    + "no buttons", screen.getKey())
                            .isNotEmpty();
                }));
    }

    /// Denies every permission and records every `Component` sent to chat. `showDialog` would
    /// need a live registry, so a screen that reached it would fail the no-throw assertion
    /// above rather than silently pass.
    private static Player playerWithoutAnyPermission(List<Component> sent) {
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getName().equals("hasPermission")) {
                return false;
            }
            if (method.getName().equals("sendMessage") && args != null && args.length > 0
                    && args[0] instanceof Component component) {
                sent.add(component);
                return null;
            }
            if (method.getName().equals("getUniqueId")) {
                return UUID.randomUUID();
            }
            return switch (method.getReturnType().getName()) {
                case "boolean" -> false;
                case "int", "short", "byte" -> 0;
                case "long" -> 0L;
                case "float" -> 0f;
                case "double" -> 0d;
                default -> null;
            };
        };
        return (Player) Proxy.newProxyInstance(
                DialogDegradationTest.class.getClassLoader(), new Class<?>[] {Player.class}, handler);
    }

    /// `Actor` is built from the player inside a couple of the screens' callbacks; the
    /// constructor is exercised here so an unrelated NPE cannot masquerade as a pass.
    @org.junit.jupiter.api.Test
    void theDoubleAnswersTheCallsTheScreensMake() {
        List<Component> sent = new ArrayList<>();
        Player player = playerWithoutAnyPermission(sent);
        assertThat(player.hasPermission("ploceus.use")).isFalse();
        assertThat(new Actor(player.getUniqueId(), "TheMeinerLP")).isNotNull();
    }
}
