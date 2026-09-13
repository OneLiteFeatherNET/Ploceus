package net.onelitefeather.ploceus.ui.dialog;

import net.onelitefeather.ploceus.domain.*;
import net.onelitefeather.ploceus.persistence.AuditRepository;
import net.onelitefeather.ploceus.persistence.ProjectRepository;
import net.onelitefeather.ploceus.persistence.TagRepository;
import net.onelitefeather.ploceus.service.AuditService;
import net.onelitefeather.ploceus.service.ProjectService;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/// Proves the execute-time half of the dialog permission gate: `DialogPermissions.guarded`
/// must stop a forged/replayed callback from ever reaching the service layer, not merely
/// from being rendered. This is the check that matters, per the class Javadoc on
/// `DialogUi` and `DialogPermissions` — a modified client can fire a registered
/// custom-click callback without ever having been shown the button that renders it, so a
/// render-time `hasPermission` check alone (proven absent here) would not be enough.
class DialogPermissionsTest {

    @Test
    void deniedPermissionRunsTheDenialCallbackInsteadOfTheAction() {
        AtomicBoolean actionRan = new AtomicBoolean(false);
        AtomicBoolean deniedRan = new AtomicBoolean(false);

        Runnable guarded = DialogPermissions.guarded(
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

        Runnable guarded = DialogPermissions.guarded(
                permission -> true, "ploceus.project.delete",
                () -> deniedRan.set(true), () -> actionRan.set(true));
        guarded.run();

        assertThat(actionRan).isTrue();
        assertThat(deniedRan).isFalse();
    }

    /// A `Player` test double whose `hasPermission` always answers `false` — reused across
    /// the service-level tests below to show the wiring works with the actual Bukkit type
    /// `DialogUi` guards, not just a bare `Predicate<String>`.
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

    /// Same delete flow `DialogUi.projectDetail`'s delete button builds: `guarded(player,
    /// "ploceus.project.delete", () -> projects.delete(...))`. With a `Player` that denies
    /// every permission, the project must survive untouched — asserted both as "the
    /// service's `delete` was never entered" (a recording subclass) and as "the project is
    /// still there" (the in-memory fixture's own state), so the test does not depend on
    /// `delete` happening to be a no-op for some other reason.
    @Test
    void deleteIsBlockedWhenThePlayerLacksTheDeletePermission() {
        RecordingProjectService projects = recordingProjectService();
        PloceusKey key = PloceusKey.of("keep-me");
        projects.create(key, "Keep Me", Actor.CONSOLE);

        Player player = playerDeniedEverything();

        Runnable deleteAction = DialogPermissions.guarded(
                player::hasPermission, "ploceus.project.delete", () -> { },
                () -> projects.delete(key, Actor.CONSOLE));
        deleteAction.run();

        assertThat(projects.deleteCalled).isFalse();
        assertThat(projects.find(key)).isPresent();
    }

    /// Same wiring as `DialogUi.createProjectForm`'s submit button:
    /// `permitted(player, "ploceus.project.create")` gates the call to `projects.create`.
    @Test
    void createIsBlockedWhenThePlayerLacksTheCreatePermission() {
        RecordingProjectService projects = recordingProjectService();
        PloceusKey key = PloceusKey.of("never-created");
        Player player = playerDeniedEverything();

        boolean permitted = DialogPermissions.permitted(player::hasPermission, "ploceus.project.create", () -> { });
        if (permitted) {
            projects.create(key, "Should not exist", Actor.CONSOLE);
        }

        assertThat(permitted).isFalse();
        assertThat(projects.createCalled).isFalse();
        assertThat(projects.find(key)).isEmpty();
    }

    /// Same wiring as `DialogUi.editProjectForm`'s submit button:
    /// `permitted(player, "ploceus.project.edit")` gates `projects.rename`.
    @Test
    void renameIsBlockedWhenThePlayerLacksTheEditPermission() {
        RecordingProjectService projects = recordingProjectService();
        PloceusKey key = PloceusKey.of("stays-named");
        projects.create(key, "Original Name", Actor.CONSOLE);
        Player player = playerDeniedEverything();

        boolean permitted = DialogPermissions.permitted(player::hasPermission, "ploceus.project.edit", () -> { });
        if (permitted) {
            projects.rename(key, "Renamed", Actor.CONSOLE);
        }

        assertThat(permitted).isFalse();
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
