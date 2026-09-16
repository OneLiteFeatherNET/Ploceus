package net.onelitefeather.ploceus.ui.dialog;

import io.papermc.paper.registry.data.dialog.ActionButton;
import net.kyori.adventure.text.Component;
import net.onelitefeather.ploceus.domain.*;
import net.onelitefeather.ploceus.persistence.AuditRepository;
import net.onelitefeather.ploceus.persistence.ProjectRepository;
import net.onelitefeather.ploceus.persistence.TagRepository;
import net.onelitefeather.ploceus.service.AuditService;
import net.onelitefeather.ploceus.service.ProjectService;
import net.onelitefeather.ploceus.ui.UiPermissions;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/// Proves both halves of the dialog permission gate described on `DialogUi` and
/// `DialogCallbacks`:
///
/// - `UiPermissions.guarded` (tests 1-2) is the execute-time re-check that stops a
///   forged/replayed callback from ever reaching the service layer, even for a button that
///   was legitimately rendered.
/// - `DialogCallbacks.button` (tests 3-5) is the render-time half: since `permission` and
///   `player` are mandatory constructor parameters, a player who lacks the permission gets
///   `null` back instead of a button — there is no other way through this class to obtain a
///   button that reaches a service, so the denied case never even constructs a live
///   `ActionButton` (which would require Paper's registry) — it returns before touching it.
class DialogPermissionsTest {

    @Test
    void deniedPermissionRunsTheDenialCallbackInsteadOfTheAction() {
        AtomicBoolean actionRan = new AtomicBoolean(false);
        AtomicBoolean deniedRan = new AtomicBoolean(false);

        Runnable guarded = UiPermissions.guarded(
                permission -> false, "ploceus.project.delete",
                () -> deniedRan.set(true), () -> actionRan.set(true));
        guarded.run();

        assertThat(actionRan).isFalse();
        assertThat(deniedRan).isTrue();
    }

    @Test
    void grantedPermissionRunsTheActionAndNotTheDenialCallback() {
        AtomicBoolean actionRan = new AtomicBoolean(false);
        AtomicBoolean deniedRan = new AtomicBoolean(false);

        Runnable guarded = UiPermissions.guarded(
                permission -> true, "ploceus.project.delete",
                () -> deniedRan.set(true), () -> actionRan.set(true));
        guarded.run();

        assertThat(actionRan).isTrue();
        assertThat(deniedRan).isFalse();
    }

    /// A `Player` test double whose `hasPermission` always answers `false` — reused across
    /// the service-level tests below to show the wiring works with the actual Bukkit type
    /// `DialogUi` and `DialogCallbacks` guard, not just a bare `Predicate<String>`.
    private static Player playerDeniedEverything() {
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getName().equals("hasPermission")) {
                return false;
            }
            return switch (method.getReturnType().getName()) {
                case "boolean" -> false;
                case "int", "short", "byte" -> 0;
                case "long" -> 0L;
                case "float" -> 0f;
                case "double" -> 0d;
                case "void" -> null;
                default -> null;
            };
        };
        return (Player) Proxy.newProxyInstance(
                DialogPermissionsTest.class.getClassLoader(), new Class<?>[] {Player.class}, handler);
    }

    /// Same delete flow `DialogUi.projectDetail`'s delete button builds via
    /// `DialogCallbacks.button(player, "ploceus.project.delete", ..., () -> projects.delete(...))`.
    /// With a `Player` that denies every permission, the factory must return `null` (no
    /// button, no registered callback at all), and the project must survive untouched —
    /// asserted both as "the service's `delete` was never entered" (a recording subclass)
    /// and as "the project is still there" (the in-memory fixture's own state).
    @Test
    void deleteButtonDoesNotExistWhenThePlayerLacksTheDeletePermission() {
        RecordingProjectService projects = recordingProjectService();
        PloceusKey key = PloceusKey.of("keep-me");
        projects.create(key, "Keep Me", Actor.CONSOLE);

        Player player = playerDeniedEverything();

        ActionButton button = DialogCallbacks.button(player, "ploceus.project.delete",
                Component.text("Delete"), null, () -> { },
                () -> projects.delete(key, Actor.CONSOLE));

        assertThat(button).isNull();
        assertThat(projects.deleteCalled).isFalse();
        assertThat(projects.find(key)).isPresent();
    }

    /// Same wiring as `DialogUi.createProjectForm`'s submit button, which gates
    /// `ploceus.project.create` before ever calling `projects.create`.
    @Test
    void createButtonDoesNotExistWhenThePlayerLacksTheCreatePermission() {
        RecordingProjectService projects = recordingProjectService();
        PloceusKey key = PloceusKey.of("never-created");
        Player player = playerDeniedEverything();

        ActionButton button = DialogCallbacks.button(player, "ploceus.project.create",
                Component.text("Create"), null, () -> { },
                () -> projects.create(key, "Should not exist", Actor.CONSOLE));

        assertThat(button).isNull();
        assertThat(projects.createCalled).isFalse();
        assertThat(projects.find(key)).isEmpty();
    }

    /// Same wiring as `DialogUi.editProjectForm`'s submit button, which gates
    /// `ploceus.project.edit` before ever calling `projects.rename`.
    @Test
    void renameButtonDoesNotExistWhenThePlayerLacksTheEditPermission() {
        RecordingProjectService projects = recordingProjectService();
        PloceusKey key = PloceusKey.of("stays-named");
        projects.create(key, "Original Name", Actor.CONSOLE);
        Player player = playerDeniedEverything();

        ActionButton button = DialogCallbacks.button(player, "ploceus.project.edit",
                Component.text("Rename"), null, () -> { },
                () -> projects.rename(key, "Renamed", Actor.CONSOLE));

        assertThat(button).isNull();
        assertThat(projects.renameCalled).isFalse();
        assertThat(projects.find(key).orElseThrow().getDisplayName()).isEqualTo("Original Name");
    }

    // --- a small in-memory ProjectService, in the same style as service.InMemoryFixtures,
    // wrapped in a subclass that records whether each guarded method was actually entered ---

    private static RecordingProjectService recordingProjectService() {
        Map<PloceusKey, Project> storage = new LinkedHashMap<>();

        ProjectRepository projectRepository = new ProjectRepository(null) {
            @Override public Optional<Project> findByKey(PloceusKey key) {
                return Optional.ofNullable(storage.get(key));
            }
            @Override public List<Project> findAll() { return List.copyOf(storage.values()); }
            @Override public List<Project> search(String term) { return findAll(); }
            @Override public void save(Project project) { storage.put(project.getKey(), project); }
            @Override public void delete(Project project) { storage.remove(project.getKey()); }
        };

        TagRepository tagRepository = new TagRepository(null) {
            private final Map<PloceusKey, Tag> known = new LinkedHashMap<>();
            @Override public Tag findOrCreate(PloceusKey key) {
                return known.computeIfAbsent(key, Tag::new);
            }
            @Override public List<Tag> findAll() { return List.copyOf(known.values()); }
        };

        AuditRepository auditRepository = new AuditRepository(null) {
            private final List<AuditEntry> entries = new ArrayList<>();
            @Override public void append(AuditEntry entry) { entries.add(entry); }
            @Override public List<AuditEntry> findByTarget(PloceusKey t, int limit, int offset) {
                return entries.reversed().stream()
                        .filter(e -> e.getTargetId().equals(t))
                        .skip(offset).limit(limit).toList();
            }
        };

        return new RecordingProjectService(projectRepository, tagRepository, new AuditService(auditRepository));
    }

    /// Overrides only the methods the three tests above care about; `delete` and `rename`
    /// (and `create`) still fully delegate to the real `ProjectService` logic via `super`,
    /// so "was blocked" is proven both by the flag and by the fixture's resulting state.
    private static final class RecordingProjectService extends ProjectService {

        boolean deleteCalled;
        boolean createCalled;
        boolean renameCalled;

        RecordingProjectService(ProjectRepository projects, TagRepository tags, AuditService audit) {
            super(projects, tags, audit);
        }

        @Override
        public OperationResult<Project> delete(PloceusKey key, Actor actor) {
            deleteCalled = true;
            return super.delete(key, actor);
        }

        @Override
        public OperationResult<Project> create(PloceusKey key, String displayName, Actor actor) {
            createCalled = true;
            return super.create(key, displayName, actor);
        }

        @Override
        public OperationResult<Project> rename(PloceusKey key, String displayName, Actor actor) {
            renameCalled = true;
            return super.rename(key, displayName, actor);
        }
    }
}
