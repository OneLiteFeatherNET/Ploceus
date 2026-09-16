package net.onelitefeather.ploceus.service;

import net.onelitefeather.ploceus.domain.Actor;
import net.onelitefeather.ploceus.domain.AuditAction;
import net.onelitefeather.ploceus.domain.AuditEntry;
import net.onelitefeather.ploceus.domain.OperationResult;
import net.onelitefeather.ploceus.domain.PloceusKey;
import net.onelitefeather.ploceus.domain.Project;
import net.onelitefeather.ploceus.domain.ProjectWorld;
import net.onelitefeather.ploceus.domain.WorldDefaults;
import net.onelitefeather.ploceus.persistence.AuditRepository;
import net.onelitefeather.ploceus.persistence.WorldRepository;
import net.onelitefeather.ploceus.platform.MultiverseAdapter;
import net.onelitefeather.ploceus.platform.SchedulerAdapter;
import net.onelitefeather.ploceus.platform.WorldCreationRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class WorldCreationServiceTest {

    private final WorldDefaults configDefault =
            new WorldDefaults("", "", "NORMAL", false, "");

    @Test
    void aProjectPresetWins() {
        Project project = new Project(PloceusKey.of("p"), "P");
        project.setWorldDefaults(new WorldDefaults("VoidGen", "", "NORMAL", false, ""));

        assertThat(WorldCreationService.resolveDefaults(project, configDefault).getGenerator())
                .isEqualTo("VoidGen");
    }

    @Test
    void withoutAPresetTheConfigDefaultApplies() {
        Project project = new Project(PloceusKey.of("p"), "P");
        WorldDefaults fromConfig = new WorldDefaults("VoidGen", "flat", "NORMAL", false, "");

        assertThat(WorldCreationService.resolveDefaults(project, fromConfig).getGenerator())
                .isEqualTo("VoidGen");
    }

    @Test
    void withNeitherPresetNorConfigTheResultIsVanilla() {
        Project project = new Project(PloceusKey.of("p"), "P");

        WorldDefaults resolved = WorldCreationService.resolveDefaults(project, configDefault);
        assertThat(resolved.getGenerator()).isEmpty();
        assertThat(resolved.getEnvironment()).isEqualTo("NORMAL");
    }

    // --- create(...) ---

    @Test
    void aDuplicateWorldNameIsRefusedAndMultiverseIsNeverCalled() {
        Fixture fixture = new Fixture();
        ProjectWorld existing = new ProjectWorld(PloceusKey.of("taken"), "taken");
        fixture.storage.put(existing.getKey(), existing);
        Project project = new Project(PloceusKey.of("p"), "P");
        var form = new WorldCreationService.WorldCreationForm(
                "taken", "Taken", "", "", "NORMAL", false, null);

        OperationResult<WorldCreationService.CreationOutcome> result =
                fixture.service().create(project, form, Actor.CONSOLE).join();

        assertThat(result.isSuccess()).isFalse();
        assertThat(((OperationResult.Failure<?>) result).messageKey()).isEqualTo("world.exists");
        assertThat(fixture.multiverseCreateCalls).isZero();
    }

    @Test
    void aSuccessfulCreationWritesTheRowAndAWorldCreatedAuditEntry() {
        Fixture fixture = new Fixture();
        Project project = new Project(PloceusKey.of("p"), "P");
        var form = new WorldCreationService.WorldCreationForm(
                "new-world", "New World", "", "", "NORMAL", false, null);

        OperationResult<WorldCreationService.CreationOutcome> result =
                fixture.service().create(project, form, Actor.CONSOLE).join();

        assertThat(result.isSuccess()).isTrue();
        assertThat(fixture.storage).containsKey(PloceusKey.of("new-world"));
        assertThat(fixture.audit).anyMatch(entry -> entry.getAction() == AuditAction.WORLD_CREATED
                && entry.getField() == null);
    }

    @Test
    void overridingThePresetGeneratorWritesTheDeviationAuditEntry() {
        Fixture fixture = new Fixture();
        Project project = new Project(PloceusKey.of("p"), "P");
        project.setWorldDefaults(new WorldDefaults("VoidGen", "", "NORMAL", false, ""));
        var form = new WorldCreationService.WorldCreationForm(
                "override-world", "Override", "Vanilla", "", "NORMAL", false, null);

        OperationResult<WorldCreationService.CreationOutcome> result =
                fixture.service().create(project, form, Actor.CONSOLE).join();

        assertThat(result.isSuccess()).isTrue();
        assertThat(fixture.audit).anyMatch(entry -> entry.getAction() == AuditAction.WORLD_CREATED
                && "generator".equals(entry.getField())
                && "VoidGen".equals(entry.getOldValue())
                && "Vanilla".equals(entry.getNewValue()));
    }

    @Test
    void matchingThePresetGeneratorWritesNoDeviationAuditEntry() {
        Fixture fixture = new Fixture();
        Project project = new Project(PloceusKey.of("p"), "P");
        project.setWorldDefaults(new WorldDefaults("VoidGen", "", "NORMAL", false, ""));
        var form = new WorldCreationService.WorldCreationForm(
                "matching-world", "Matching", "VoidGen", "", "NORMAL", false, null);

        fixture.service().create(project, form, Actor.CONSOLE).join();

        assertThat(fixture.audit).noneMatch(entry -> "generator".equals(entry.getField()));
    }

    @Test
    void aFailingMultiverseCreateWorldLeavesNoDatabaseRowBehind() {
        Fixture fixture = new Fixture();
        fixture.multiverse = new MultiverseAdapter(null, null) {
            @Override public OperationResult<String> createWorld(WorldCreationRequest request) {
                fixture.multiverseCreateCalls++;
                return OperationResult.failure("world.create.failed", "boom");
            }
        };
        Project project = new Project(PloceusKey.of("p"), "P");
        var form = new WorldCreationService.WorldCreationForm(
                "doomed-world", "Doomed", "", "", "NORMAL", false, null);

        OperationResult<WorldCreationService.CreationOutcome> result =
                fixture.service().create(project, form, Actor.CONSOLE).join();

        assertThat(result.isSuccess()).isFalse();
        assertThat(((OperationResult.Failure<?>) result).messageKey()).isEqualTo("world.create.failed");
        assertThat(fixture.multiverseCreateCalls).isEqualTo(1);
        assertThat(fixture.storage).doesNotContainKey(PloceusKey.of("doomed-world"));
    }

    @Test
    void aFailedAliasMirrorDoesNotFailTheCreationButIsAuditedAndSurfacedAsAWarning() {
        Fixture fixture = new Fixture();
        fixture.multiverse = new MultiverseAdapter(null, null) {
            @Override public OperationResult<String> createWorld(WorldCreationRequest request) {
                fixture.multiverseCreateCalls++;
                return OperationResult.success(request.worldName());
            }
            @Override public OperationResult<Void> setAutoLoad(String worldName, boolean autoLoad) {
                return OperationResult.success(null);
            }
            @Override public OperationResult<Void> setAlias(String worldName, String alias) {
                return OperationResult.failure("world.alias.failed", "no such world");
            }
        };
        Project project = new Project(PloceusKey.of("p"), "P");
        var form = new WorldCreationService.WorldCreationForm(
                "warned-world", "Warned", "", "", "NORMAL", false, null);

        OperationResult<WorldCreationService.CreationOutcome> result =
                fixture.service().create(project, form, Actor.CONSOLE).join();

        assertThat(result.isSuccess()).isTrue();
        var outcome = ((OperationResult.Success<WorldCreationService.CreationOutcome>) result).value();
        assertThat(outcome.warnings()).extracting(OperationResult.Failure::messageKey)
                .containsExactly("world.alias.failed");
        assertThat(fixture.storage).containsKey(PloceusKey.of("warned-world"));
        assertThat(fixture.audit).anyMatch(entry -> entry.getAction() == AuditAction.WORLD_UPDATED);
    }

    /// Reproduces the exact regression fix round 2 closed: `multiverse.createWorld` throwing
    /// instead of returning a `Failure`. Uses a *real* background-thread executor for
    /// `async` (unlike `Fixture`'s inline double) so `create(...)` returns the still-pending
    /// future immediately, the same as production — with the inline double, a swallowed
    /// exception would abort `create(...)` itself synchronously instead of reproducing the
    /// actual bug, which is a future that never completes because the exception is thrown on
    /// a thread the caller isn't waiting on. `future.get(2, TimeUnit.SECONDS)` is a bounded
    /// wait for exactly that reason: without the sync-phase `try/catch`, this future never
    /// completes, and this test must time out and fail rather than hang the whole suite.
    @Test
    void aThrownExceptionDuringCreateWorldStillCompletesTheFutureAndRollsBackTheRow() throws Exception {
        Map<PloceusKey, ProjectWorld> storage = new LinkedHashMap<>();
        List<AuditEntry> auditEntries = new ArrayList<>();

        MultiverseAdapter multiverse = new MultiverseAdapter(null, null) {
            @Override public OperationResult<String> createWorld(WorldCreationRequest request) {
                throw new IllegalStateException("boom");
            }
        };
        WorldRepository worlds = new WorldRepository(null) {
            @Override public Optional<ProjectWorld> findByKey(PloceusKey key) {
                return Optional.ofNullable(storage.get(key));
            }
            @Override public Optional<ProjectWorld> findByWorldName(String worldName) {
                return storage.values().stream()
                        .filter(world -> world.getWorldName().equals(worldName))
                        .findFirst();
            }
            @Override public void save(ProjectWorld world) { storage.put(world.getKey(), world); }
            @Override public void delete(ProjectWorld world) { storage.remove(world.getKey()); }
        };
        AuditRepository auditRepository = new AuditRepository(null) {
            @Override public void append(AuditEntry entry) { auditEntries.add(entry); }
            @Override public List<AuditEntry> findByTarget(PloceusKey t, int limit, int offset) {
                return List.copyOf(auditEntries);
            }
        };

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            SchedulerAdapter scheduler = new SchedulerAdapter(null) {
                @Override public void async(Runnable work) { executor.execute(work); }
                @Override public void sync(Runnable work) { work.run(); }
            };
            WorldCreationService service = new WorldCreationService(multiverse, worlds,
                    new AuditService(auditRepository), new WorldDefaults("", "", "NORMAL", false, ""), scheduler);
            Project project = new Project(PloceusKey.of("p"), "P");
            var form = new WorldCreationService.WorldCreationForm(
                    "throws-world", "Throws", "", "", "NORMAL", false, null);

            CompletableFuture<OperationResult<WorldCreationService.CreationOutcome>> future =
                    service.create(project, form, Actor.CONSOLE);

            OperationResult<WorldCreationService.CreationOutcome> result = future.get(2, TimeUnit.SECONDS);

            assertThat(result.isSuccess()).isFalse();
            assertThat(((OperationResult.Failure<?>) result).messageKey()).isEqualTo("world.create.failed");
            assertThat(storage).doesNotContainKey(PloceusKey.of("throws-world"));
        } finally {
            executor.shutdownNow();
        }
    }

    /// Minimal in-memory wiring for `WorldCreationService`, in the same style as
    /// `WorldFixtures`/`DialogPermissionsTest`'s own fixtures. Runs every scheduler hop
    /// in-line so `create(...).join()` returns immediately without a real thread hop,
    /// matching the deterministic `SchedulerAdapter` double `WorldLifecycleServiceTest` uses.
    private static final class Fixture {
        final Map<PloceusKey, ProjectWorld> storage = new LinkedHashMap<>();
        final List<AuditEntry> audit = new ArrayList<>();
        int multiverseCreateCalls;

        MultiverseAdapter multiverse = new MultiverseAdapter(null, null) {
            @Override public OperationResult<String> createWorld(WorldCreationRequest request) {
                multiverseCreateCalls++;
                return OperationResult.success(request.worldName());
            }
            @Override public OperationResult<Void> setAutoLoad(String worldName, boolean autoLoad) {
                return OperationResult.success(null);
            }
            @Override public OperationResult<Void> setAlias(String worldName, String alias) {
                return OperationResult.success(null);
            }
        };

        final WorldRepository worlds = new WorldRepository(null) {
            @Override public Optional<ProjectWorld> findByKey(PloceusKey key) {
                return Optional.ofNullable(storage.get(key));
            }
            @Override public Optional<ProjectWorld> findByWorldName(String worldName) {
                return storage.values().stream()
                        .filter(world -> world.getWorldName().equals(worldName))
                        .findFirst();
            }
            @Override public void save(ProjectWorld world) { storage.put(world.getKey(), world); }
            @Override public void delete(ProjectWorld world) { storage.remove(world.getKey()); }
        };

        final AuditRepository auditRepository = new AuditRepository(null) {
            @Override public void append(AuditEntry entry) { audit.add(entry); }
            @Override public List<AuditEntry> findByTarget(PloceusKey t, int limit, int offset) {
                return audit.reversed().stream()
                        .filter(e -> e.getTargetId().equals(t))
                        .skip(offset).limit(limit).toList();
            }
        };

        final SchedulerAdapter scheduler = new SchedulerAdapter(null) {
            @Override public void async(Runnable work) { work.run(); }
            @Override public void sync(Runnable work) { work.run(); }
        };

        WorldCreationService service() {
            return new WorldCreationService(multiverse, worlds, new AuditService(auditRepository),
                    new WorldDefaults("", "", "NORMAL", false, ""), scheduler);
        }
    }
}
