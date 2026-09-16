package net.onelitefeather.ploceus.service;

import net.onelitefeather.ploceus.domain.Actor;
import net.onelitefeather.ploceus.domain.AuditAction;
import net.onelitefeather.ploceus.domain.OperationResult;
import net.onelitefeather.ploceus.domain.PloceusKey;
import net.onelitefeather.ploceus.domain.Project;
import net.onelitefeather.ploceus.domain.ProjectWorld;
import net.onelitefeather.ploceus.domain.WorldDefaults;
import net.onelitefeather.ploceus.persistence.WorldRepository;
import net.onelitefeather.ploceus.platform.MultiverseAdapter;
import net.onelitefeather.ploceus.platform.SchedulerAdapter;
import net.onelitefeather.ploceus.platform.WorldCreationRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import static net.onelitefeather.ploceus.domain.AuditEntry.TargetType.WORLD;

/// Creates a world through Multiverse, resolving its generator/environment defaults from the
/// owning project's preset before falling back to the config default and then vanilla.
///
/// {@link #create} crosses threads internally, the same way {@link MultiverseAdapter#teleport}
/// does: only `multiverse.createWorld`/`setAutoLoad`/`setAlias` need the main thread, so the
/// duplicate check, the database row reservation, the audit writes and any rollback all run
/// off it. Callers get a `CompletableFuture` that completes exactly once, on whichever thread
/// the last phase happens to run on — same contract as `MultiverseAdapter.teleport`, so a
/// caller showing UI off the result still needs its own `scheduler.sync(...)` hop.
public class WorldCreationService {

    public record WorldCreationForm(String worldName, String displayName, String generator,
                                    String generatorSettings, String environment,
                                    boolean generateStructures, Long seed) { }

    /// The created world, plus any post-creation step (auto-load, alias) that failed without
    /// invalidating the creation. Neither failure rolls back the world: by the time either
    /// runs, Multiverse has already created it and it is already usable, just with Multiverse's
    /// own defaults for auto-load/alias instead of Ploceus'. Both failures are still written
    /// to the audit trail (see `create`) and handed back here so the caller can tell the
    /// player, instead of the silent swallow this record replaced.
    public record CreationOutcome(ProjectWorld world, List<OperationResult.Failure<Void>> warnings) { }

    private final MultiverseAdapter multiverse;
    private final WorldRepository worlds;
    private final AuditService audit;
    private final WorldDefaults configDefaults;
    private final SchedulerAdapter scheduler;

    public WorldCreationService(MultiverseAdapter multiverse, WorldRepository worlds,
                                AuditService audit, WorldDefaults configDefaults,
                                SchedulerAdapter scheduler) {
        this.multiverse = multiverse;
        this.worlds = worlds;
        this.audit = audit;
        this.configDefaults = configDefaults;
        this.scheduler = scheduler;
    }

    /// project preset -> config default -> vanilla. Pure and static, so the resolution order
    /// is testable on its own without constructing a service.
    public static WorldDefaults resolveDefaults(Project project, WorldDefaults configDefaults) {
        WorldDefaults preset = project.getWorldDefaults();
        if (preset != null && preset.getGenerator() != null && !preset.getGenerator().isBlank()) {
            return preset;
        }
        return configDefaults;
    }

    public WorldDefaults defaultsFor(Project project) {
        return resolveDefaults(project, configDefaults);
    }

    /// Three phases, matching the thread each one actually needs:
    ///
    /// 1. **async** — validate the name, reject a duplicate, and reserve the database row
    ///    (`worlds.save`). Reserving the row before Multiverse is touched is deliberate: a
    ///    database row is cheap and trivially reversible, but `MultiverseAdapter` exposes no
    ///    `removeWorld` to undo a created world, so "reserve, then create, then delete the
    ///    reservation on failure" is the only direction in which all-or-nothing is actually
    ///    achievable here — the same reasoning `WorldService.rename` applies to mirroring the
    ///    Multiverse alias before persisting.
    /// 2. **sync** — `multiverse.createWorld`, then (only if that succeeded)
    ///    `setAutoLoad`/`setAlias`. The only work that has to run on the main thread.
    /// 3. **async** — on failure, delete the reserved row and complete with the failure. On
    ///    success, write the audit trail (creation event, generator-deviation entry, and one
    ///    entry per failed auto-load/alias mirror) and complete with the outcome.
    public CompletableFuture<OperationResult<CreationOutcome>> create(Project project, WorldCreationForm form,
                                                                       Actor actor) {
        CompletableFuture<OperationResult<CreationOutcome>> future = new CompletableFuture<>();
        scheduler.async(() -> {
            if (!PloceusKey.isValidValue(form.worldName())) {
                future.complete(OperationResult.failure("form.invalidKey", form.worldName()));
                return;
            }
            PloceusKey key = PloceusKey.of(form.worldName());
            if (worlds.findByKey(key).isPresent() || worlds.findByWorldName(form.worldName()).isPresent()) {
                future.complete(OperationResult.failure("world.exists", form.worldName()));
                return;
            }

            ProjectWorld world = new ProjectWorld(key, form.worldName());
            world.setDisplayName(form.displayName() == null || form.displayName().isBlank()
                    ? form.worldName() : form.displayName());
            world.setProject(project);
            world.setCreatedBy(actor.uuid());
            world.setUpdatedBy(actor.uuid());
            worlds.save(world);

            scheduler.sync(() -> {
                OperationResult<String> created = multiverse.createWorld(new WorldCreationRequest(
                        key, form.worldName(), form.generator(), form.generatorSettings(),
                        form.environment(), null, form.generateStructures(), form.seed()));

                OperationResult<Void> autoLoad;
                OperationResult<Void> alias;
                if (created.isSuccess()) {
                    autoLoad = multiverse.setAutoLoad(form.worldName(), false);
                    alias = multiverse.setAlias(form.worldName(), world.getDisplayName());
                } else {
                    autoLoad = null;
                    alias = null;
                }

                scheduler.async(() -> {
                    if (created instanceof OperationResult.Failure<String> failure) {
                        worlds.delete(world);
                        future.complete(OperationResult.failure(failure.messageKey(), failure.detail()));
                        return;
                    }

                    List<OperationResult.Failure<Void>> warnings = new ArrayList<>();
                    if (autoLoad instanceof OperationResult.Failure<Void> failure) {
                        warnings.add(failure);
                        audit.recordEvent(WORLD, key, actor, AuditAction.WORLD_UPDATED,
                                "autoLoad setup failed: " + failure.detail());
                    }
                    if (alias instanceof OperationResult.Failure<Void> failure) {
                        warnings.add(failure);
                        audit.recordEvent(WORLD, key, actor, AuditAction.WORLD_UPDATED,
                                "alias setup failed: " + failure.detail());
                    }

                    audit.recordEvent(WORLD, key, actor, AuditAction.WORLD_CREATED,
                            "generator=" + form.generator() + " environment=" + form.environment());

                    // A deviation from the project preset is worth recording explicitly.
                    WorldDefaults expected = defaultsFor(project);
                    if (!Objects.equals(expected.getGenerator(), form.generator())) {
                        audit.recordChange(WORLD, key, actor, AuditAction.WORLD_CREATED,
                                "generator", expected.getGenerator(), form.generator());
                    }

                    future.complete(OperationResult.success(new CreationOutcome(world, List.copyOf(warnings))));
                });
            });
        });
        return future;
    }
}
