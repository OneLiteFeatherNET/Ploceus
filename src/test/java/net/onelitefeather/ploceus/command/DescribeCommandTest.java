package net.onelitefeather.ploceus.command;

import net.onelitefeather.ploceus.domain.*;
import net.onelitefeather.ploceus.persistence.AuditRepository;
import net.onelitefeather.ploceus.persistence.ProjectRepository;
import net.onelitefeather.ploceus.persistence.TagRepository;
import net.onelitefeather.ploceus.persistence.WorldRepository;
import net.onelitefeather.ploceus.platform.SchedulerAdapter;
import net.onelitefeather.ploceus.service.AuditService;
import net.onelitefeather.ploceus.service.ProjectService;
import net.onelitefeather.ploceus.service.WorldService;
import net.onelitefeather.ploceus.ui.ProjectUi;
import net.onelitefeather.ploceus.ui.UiRouter;
import org.bukkit.entity.Player;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.Permission;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/// Covers the two gaps this task closes: `WorldService.describe` had no caller anywhere, and
/// `ProjectService.describe` was only reachable through the dialog channel. Both new
/// `PloceusCommands` methods (`projectDescribe`, `describeWorld`) are exercised the same way
/// `CommandSyntaxTest` exercises `projectDelete` — reflectively, against the real `@Command`
/// and `@Permission` annotations — because this project has no Bukkit/Paper server test
/// harness to spin up a real `CommandManager` and dispatch a string through it (see
/// `CommandSyntaxTest`'s class Javadoc for the same reasoning applied to `--confirm`).
///
/// Cloud enforces `@Permission` before a command method is ever invoked, so there is no
/// execute-time check inside `projectDescribe`/`describeWorld` themselves to call directly —
/// the annotation value is *the* gate. Asserting it here is what "gated by its permission at
/// execute time" means for a Cloud-annotated method: it is the value Cloud's execution
/// pipeline reads at dispatch time to decide whether the method body ever runs.
class DescribeCommandTest {

    // --- projectDescribe -----------------------------------------------------------------

    @Test
    void projectDescribeRequiresTheProjectEditPermission() throws NoSuchMethodException {
        Method method = PloceusCommands.class.getMethod(
                "projectDescribe", Player.class, PloceusKey.class, String.class);

        assertThat(method.getAnnotation(Permission.class).value()).containsExactly("ploceus.project.edit");
        assertThat(method.getAnnotation(Command.class).value())
                .isEqualTo("ploceus|projects|pj project describe <key> <description>");
    }

    @Test
    void projectDescribeReachesTheServiceWithTheKeyTextAndActor() {
        RecordingProjectService projects = recordingProjectService();
        PloceusKey key = PloceusKey.of("elytrarace");
        projects.create(key, "Elytra Race", Actor.CONSOLE);

        UUID playerId = UUID.randomUUID();
        Player player = playerNamed(playerId, "Steve");
        FeedbackCapturingUi ui = new FeedbackCapturingUi();

        PloceusCommands commands = new PloceusCommands(projects, null, null, null,
                routerAlwaysReturning(ui), inlineScheduler(), null, null, null, null, null);

        commands.projectDescribe(player, key, "A race across the sky.");

        assertThat(projects.describeCalledWith).isEqualTo(key);
        assertThat(projects.describeCalledWithText).isEqualTo("A race across the sky.");
        assertThat(projects.describeCalledWithActor).isEqualTo(new Actor(playerId, "Steve"));
        assertThat(projects.find(key).orElseThrow().getDescription()).isEqualTo("A race across the sky.");

        assertThat(ui.result).isInstanceOf(OperationResult.Success.class);
        assertThat(ui.successKey).isEqualTo("project.updated");
    }

    // --- describeWorld ---------------------------------------------------------------------

    @Test
    void describeWorldRequiresTheWorldEditPermission() throws NoSuchMethodException {
        Method method = PloceusCommands.class.getMethod(
                "describeWorld", Player.class, PloceusKey.class, String.class);

        assertThat(method.getAnnotation(Permission.class).value()).containsExactly("ploceus.world.edit");
        assertThat(method.getAnnotation(Command.class).value())
                .isEqualTo("ploceus|projects|pj world describe <worldKey> <description>");
    }

    @Test
    void describeWorldReachesTheServiceWithTheKeyTextAndActor() {
        RecordingWorldService worlds = recordingWorldService();
        PloceusKey key = PloceusKey.of("elytrarace-desert");
        worlds.seed(new ProjectWorld(key, "elytrarace-desert"));

        UUID playerId = UUID.randomUUID();
        Player player = playerNamed(playerId, "Alex");
        FeedbackCapturingUi ui = new FeedbackCapturingUi();

        PloceusCommands commands = new PloceusCommands(null, worlds, null, null,
                routerAlwaysReturning(ui), inlineScheduler(), null, null, null, null, null);

        commands.describeWorld(player, key, "A dune sea with a hidden oasis.");

        assertThat(worlds.describeCalledWith).isEqualTo(key);
        assertThat(worlds.describeCalledWithText).isEqualTo("A dune sea with a hidden oasis.");
        assertThat(worlds.describeCalledWithActor).isEqualTo(new Actor(playerId, "Alex"));
        assertThat(worlds.find(key).orElseThrow().getDescription()).isEqualTo("A dune sea with a hidden oasis.");

        assertThat(ui.result).isInstanceOf(OperationResult.Success.class);
        assertThat(ui.successKey).isEqualTo("world.updated");
    }

    // --- fixtures --------------------------------------------------------------------------

    /// Runs every hop inline, the same double used throughout this project's own tests
    /// (e.g. `DialogPermissionsTest.recordingWorldCreationService`) so a command's `async`/
    /// `sync` split is exercised without a real Bukkit scheduler.
    private static SchedulerAdapter inlineScheduler() {
        return new SchedulerAdapter(null) {
            @Override public void async(Runnable work) { work.run(); }
            @Override public void sync(Runnable work) { work.run(); }
        };
    }

    /// `UiRouter.forPlayer` is not overridden here; `resolve` is bypassed entirely by
    /// overriding `forPlayer` directly, so the constructor's `channels`/`preferences`/
    /// `config`/`scheduler` are never touched and can stay `null`.
    private static UiRouter routerAlwaysReturning(ProjectUi ui) {
        return new UiRouter(null, null, null, null) {
            @Override public ProjectUi forPlayer(Player player) { return ui; }
        };
    }

    private static Player playerNamed(UUID uuid, String name) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> uuid;
            case "getName" -> name;
            default -> switch (method.getReturnType().getName()) {
                case "boolean" -> false;
                case "int", "short", "byte" -> 0;
                case "long" -> 0L;
                case "float" -> 0f;
                case "double" -> 0d;
                default -> null;
            };
        };
        return (Player) Proxy.newProxyInstance(
                DescribeCommandTest.class.getClassLoader(), new Class<?>[] {Player.class}, handler);
    }

    /// Captures exactly what `router.forPlayer(player).feedback(...)` was called with,
    /// standing in for a real `ProjectUi` implementation.
    private static final class FeedbackCapturingUi implements ProjectUi {
        OperationResult<?> result;
        String successKey;

        @Override public void feedback(Player player, OperationResult<?> result, String successKey) {
            this.result = result;
            this.successKey = successKey;
        }

        @Override public void mainMenu(Player player) { }
        @Override public void projectList(Player player, net.onelitefeather.ploceus.ui.Page<Project> page) { }
        @Override public void projectDetail(Player player, Project project, List<ProjectWorld> worlds) { }
        @Override public void worldList(Player player, Project project,
                net.onelitefeather.ploceus.ui.Page<ProjectWorld> page) { }
        @Override public void unassignedWorlds(Player player, net.onelitefeather.ploceus.ui.Page<ProjectWorld> page) { }
        @Override public void importableWorlds(Player player, net.onelitefeather.ploceus.ui.Page<String> page) { }
        @Override public void searchPrompt(Player player) { }
        @Override public void searchResults(Player player, String term,
                net.onelitefeather.ploceus.ui.Page<Project> projects,
                net.onelitefeather.ploceus.ui.Page<ProjectWorld> worlds) { }
        @Override public void createProjectForm(Player player) { }
        @Override public void editProjectForm(Player player, Project project) { }
        @Override public void createWorldForm(Player player, Project project, String suggestedName,
                List<String> generators) { }
        @Override public void auditTrail(Player player, PloceusKey target,
                net.onelitefeather.ploceus.ui.Page<AuditEntry> entries) { }
        @Override public void confirm(Player player, String messageKey, Runnable onConfirm) { }
    }

    // --- ProjectService double, in the same style as DialogPermissionsTest's ---

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
            @Override public Tag findOrCreate(PloceusKey key) { return known.computeIfAbsent(key, Tag::new); }
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

    private static final class RecordingProjectService extends ProjectService {
        PloceusKey describeCalledWith;
        String describeCalledWithText;
        Actor describeCalledWithActor;

        RecordingProjectService(ProjectRepository projects, TagRepository tags, AuditService audit) {
            super(projects, tags, audit);
        }

        @Override
        public OperationResult<Project> describe(PloceusKey key, String description, Actor actor) {
            describeCalledWith = key;
            describeCalledWithText = description;
            describeCalledWithActor = actor;
            return super.describe(key, description, actor);
        }
    }

    // --- WorldService double, in the same style ---

    private static RecordingWorldService recordingWorldService() {
        Map<PloceusKey, ProjectWorld> storage = new LinkedHashMap<>();

        WorldRepository worldRepository = new WorldRepository(null) {
            @Override public Optional<ProjectWorld> findByKey(PloceusKey key) {
                return Optional.ofNullable(storage.get(key));
            }
            @Override public Optional<ProjectWorld> findByWorldName(String worldName) {
                return storage.values().stream()
                        .filter(world -> world.getWorldName().equals(worldName))
                        .findFirst();
            }
            @Override public List<ProjectWorld> findAll() { return List.copyOf(storage.values()); }
            @Override public void save(ProjectWorld world) { storage.put(world.getKey(), world); }
        };

        ProjectRepository projectRepository = new ProjectRepository(null) {
            @Override public Optional<Project> findByKey(PloceusKey key) { return Optional.empty(); }
        };

        TagRepository tagRepository = new TagRepository(null) {
            private final Map<PloceusKey, Tag> known = new LinkedHashMap<>();
            @Override public Tag findOrCreate(PloceusKey key) { return known.computeIfAbsent(key, Tag::new); }
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

        return new RecordingWorldService(worldRepository, projectRepository, tagRepository,
                new AuditService(auditRepository), storage);
    }

    private static final class RecordingWorldService extends WorldService {
        PloceusKey describeCalledWith;
        String describeCalledWithText;
        Actor describeCalledWithActor;
        private final Map<PloceusKey, ProjectWorld> storage;

        RecordingWorldService(WorldRepository worlds, ProjectRepository projects, TagRepository tags,
                              AuditService audit, Map<PloceusKey, ProjectWorld> storage) {
            super(worlds, projects, tags, audit, null);
            this.storage = storage;
        }

        void seed(ProjectWorld world) { storage.put(world.getKey(), world); }

        @Override
        public OperationResult<ProjectWorld> describe(PloceusKey key, String description, Actor actor) {
            describeCalledWith = key;
            describeCalledWithText = description;
            describeCalledWithActor = actor;
            return super.describe(key, description, actor);
        }
    }
}
