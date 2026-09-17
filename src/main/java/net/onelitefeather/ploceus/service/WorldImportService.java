package net.onelitefeather.ploceus.service;

import net.onelitefeather.ploceus.domain.*;
import net.onelitefeather.ploceus.persistence.WorldRepository;
import net.onelitefeather.ploceus.platform.MultiverseAdapter;
import net.onelitefeather.ploceus.platform.SchedulerAdapter;
import net.onelitefeather.ploceus.platform.Telemetry;
import net.onelitefeather.ploceus.platform.WorldSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static net.onelitefeather.ploceus.domain.AuditEntry.TargetType.WORLD;

/// Brings existing Multiverse worlds under Ploceus without guessing their grouping:
/// every unknown world becomes an unassigned entry.
///
/// Two entry points, for the two ways a world becomes "existing but unmanaged":
///
/// - {@link #importAll} — the startup sweep. Every world Multiverse *already* manages but
///   Ploceus has never seen gets a row; nothing here talks to Multiverse; there is nothing
///   to import there, only to catch up on.
/// - {@link #importOne} — the operator-driven path this class gained later, for the other
///   70-odd world folders on a real server that Multiverse itself has never loaded: those
///   are not in `worlds.yml` at all, so bringing one under Ploceus means importing it into
///   Multiverse first (via {@link MultiverseAdapter#importWorld}), which is the one Multiverse
///   call this class makes.
public class WorldImportService {

    private static final Logger log = LoggerFactory.getLogger(WorldImportService.class);

    private static final AttributeKey<String> OUTCOME = AttributeKey.stringKey("ploceus.import.outcome");

    /// Looked up fresh rather than cached - see {@link Telemetry}.
    private static LongCounter worldsImportedCounter() {
        return Telemetry.meter().counterBuilder("ploceus.worlds.imported")
                .setDescription("Worlds processed by the startup Multiverse import, by outcome")
                .setUnit("{world}")
                .build();
    }

    public record ImportSummary(int imported, int alreadyKnown, int keyCollisions) { }

    /// The imported world, plus any post-import step (auto-load) that failed without
    /// invalidating the import — mirrors `WorldCreationService.CreationOutcome`. By the time
    /// `setAutoLoad` runs, Multiverse has already imported and loaded the world, so a failed
    /// mirror is a warning to surface, not a reason to roll back.
    public record ImportOutcome(ProjectWorld world, List<OperationResult.Failure<Void>> warnings) { }

    private final WorldRepository worlds;
    private final AuditService audit;
    private final MultiverseAdapter multiverse;
    private final SchedulerAdapter scheduler;

    public WorldImportService(WorldRepository worlds, AuditService audit,
                              MultiverseAdapter multiverse, SchedulerAdapter scheduler) {
        this.worlds = worlds;
        this.audit = audit;
        this.multiverse = multiverse;
        this.scheduler = scheduler;
    }

    public ImportSummary importAll(List<WorldSnapshot> snapshots, Actor actor) {
        Span span = Telemetry.tracer().spanBuilder("ploceus.world.import")
                .setAttribute("ploceus.import.candidate_count", snapshots.size())
                .startSpan();
        try {
            ImportSummary summary = doImportAll(snapshots, actor);
            LongCounter imported = worldsImportedCounter();
            imported.add(summary.imported(), Attributes.of(OUTCOME, "imported"));
            imported.add(summary.alreadyKnown(), Attributes.of(OUTCOME, "already_known"));
            imported.add(summary.keyCollisions(), Attributes.of(OUTCOME, "key_collision"));
            span.setAttribute("ploceus.import.imported_count", summary.imported());
            return summary;
        } catch (RuntimeException exception) {
            span.recordException(exception);
            span.setStatus(StatusCode.ERROR);
            throw exception;
        } finally {
            span.end();
        }
    }

    private ImportSummary doImportAll(List<WorldSnapshot> snapshots, Actor actor) {
        int imported = 0;
        int alreadyKnown = 0;
        int collisions = 0;

        for (WorldSnapshot snapshot : snapshots) {
            if (worlds.findByWorldName(snapshot.worldName()).isPresent()) {
                alreadyKnown++;
                continue;
            }
            PloceusKey key = snapshot.derivedKey();
            if (worlds.findByKey(key).isPresent()) {
                key = nextFreeKey(key);
                collisions++;
            }
            ProjectWorld world = new ProjectWorld(key, snapshot.worldName());
            if (snapshot.alias() != null && !snapshot.alias().isBlank()) {
                world.setDisplayName(snapshot.alias());
            }
            world.setCreatedBy(actor.uuid());
            world.setUpdatedBy(actor.uuid());
            worlds.save(world);
            audit.recordEvent(WORLD, key, actor, AuditAction.WORLD_IMPORTED, snapshot.worldName());
            imported++;
        }
        return new ImportSummary(imported, alreadyKnown, collisions);
    }

    /// Imports exactly one Multiverse-visible-but-unmanaged world folder by name, the
    /// operator-driven counterpart of {@link #importAll}.
    ///
    /// Three phases, the same split {@link WorldCreationService#create} uses and for the same
    /// reason:
    ///
    /// 1. **async** — reject a world Ploceus already knows by name, derive the key (with the
    ///    same collision-suffix fallback {@link #importAll} uses), and reserve the database
    ///    row. Reserving the row *before* Multiverse is touched is deliberate, and for the
    ///    identical reason `WorldCreationService.create`'s Javadoc gives: a database row is
    ///    cheap and trivially reversible, but neither `MultiverseAdapter` nor Multiverse's own
    ///    `WorldManager` is asked here to undo a completed import, so "reserve, then import,
    ///    then delete the reservation on failure" is the only direction in which
    ///    all-or-nothing is achievable. The alternative — import first, then decide whether to
    ///    keep a database row — would leave a real, loaded, Ploceus-invisible world behind on
    ///    the one failure mode that matters least (a database write failing), which is a worse
    ///    place to strand an operator than an orphaned row that a retry with the same name
    ///    would immediately overwrite the collision-suffix for.
    /// 2. **sync** — `multiverse.importWorld`, then (only if that succeeded) `setAutoLoad`.
    ///    Multiverse loads the world as a side effect of a successful import; auto-load is
    ///    turned off immediately after for the same reason `WorldCreationService` does it for
    ///    a freshly created world — so it does not load again on the next boot — but the
    ///    world itself is deliberately left loaded here rather than force-unloaded: it is now
    ///    a `ProjectWorld` like any other, has never been visited, and the reaper
    ///    (`WorldLifecycleService.runReaperAsync`, via `UnloadPolicy.shouldUnload`, which
    ///    treats a `null` `lastVisitedAt` as immediately idle) unloads it on its very next
    ///    pass if nobody is in it by then — the same "not loaded unless someone is in it"
    ///    policy every other world on the server is already held to, rather than a special
    ///    case invented for import.
    /// 3. **async** — on failure (Multiverse refused, or the sync phase threw), delete the
    ///    reserved row and complete with the failure; nothing under `Multiverse` was ever
    ///    reached, so there is nothing there to roll back. On success, write the
    ///    `WORLD_IMPORTED` audit entry (and one more per failed auto-load mirror) and complete
    ///    with the outcome.
    public CompletableFuture<OperationResult<ImportOutcome>> importOne(String worldName, Actor actor) {
        CompletableFuture<OperationResult<ImportOutcome>> future = new CompletableFuture<>();
        scheduler.async(() -> {
            if (worlds.findByWorldName(worldName).isPresent()) {
                future.complete(OperationResult.failure("world.exists", worldName));
                return;
            }
            PloceusKey key = PloceusKey.fromWorldName(worldName);
            if (worlds.findByKey(key).isPresent()) {
                key = nextFreeKey(key);
            }
            PloceusKey reservedKey = key;

            ProjectWorld world = new ProjectWorld(reservedKey, worldName);
            world.setCreatedBy(actor.uuid());
            world.setUpdatedBy(actor.uuid());
            worlds.save(world);

            scheduler.sync(() -> {
                try {
                    OperationResult<String> imported = multiverse.importWorld(worldName);
                    OperationResult<Void> autoLoad = imported.isSuccess()
                            ? multiverse.setAutoLoad(worldName, false)
                            : null;

                    scheduler.async(() -> {
                        if (imported instanceof OperationResult.Failure<String> failure) {
                            rollbackAndFail(world, future, failure.messageKey(), failure.detail());
                            return;
                        }
                        completeSuccess(actor, reservedKey, world, autoLoad, future);
                    });
                } catch (Throwable throwable) {
                    log.error("World import threw while importing '{}'", worldName, throwable);
                    dispatchRollback(world, future);
                }
            });
        });
        return future;
    }

    /// Schedules the rollback off the main thread, mirroring
    /// `WorldCreationService.dispatchRollback`: if the async executor itself refuses the
    /// work (e.g. the plugin is shutting down), the future must still not hang forever, so it
    /// is completed right here instead.
    private void dispatchRollback(ProjectWorld world, CompletableFuture<OperationResult<ImportOutcome>> future) {
        try {
            scheduler.async(() -> rollbackAndFail(world, future, "world.import.failed", world.getWorldName()));
        } catch (Throwable dispatchFailure) {
            log.error("Could not schedule rollback for '{}'; the reserved row may be orphaned",
                    world.getWorldName(), dispatchFailure);
            future.complete(OperationResult.failure("world.import.failed", world.getWorldName()));
        }
    }

    /// Deletes the reserved row and completes `future` with a failure — the one rollback path
    /// shared by a `multiverse.importWorld` that returned a `Failure` and one that threw
    /// instead.
    private void rollbackAndFail(ProjectWorld world, CompletableFuture<OperationResult<ImportOutcome>> future,
                                 String messageKey, String detail) {
        try {
            worlds.delete(world);
        } catch (Throwable throwable) {
            log.error("Failed to roll back the reserved row for '{}' after a failed import",
                    world.getWorldName(), throwable);
        }
        future.complete(OperationResult.failure(messageKey, detail));
    }

    /// Writes the audit trail and completes `future` with the imported world plus any
    /// non-fatal auto-load warning. Only reached once `multiverse.importWorld` is known to
    /// have succeeded.
    private void completeSuccess(Actor actor, PloceusKey key, ProjectWorld world, OperationResult<Void> autoLoad,
                                 CompletableFuture<OperationResult<ImportOutcome>> future) {
        List<OperationResult.Failure<Void>> warnings = new ArrayList<>();
        if (autoLoad instanceof OperationResult.Failure<Void> failure) {
            warnings.add(failure);
            audit.recordEvent(WORLD, key, actor, AuditAction.WORLD_UPDATED,
                    "autoLoad setup failed: " + failure.detail());
        }
        audit.recordEvent(WORLD, key, actor, AuditAction.WORLD_IMPORTED, world.getWorldName());
        future.complete(OperationResult.success(new ImportOutcome(world, List.copyOf(warnings))));
    }

    private PloceusKey nextFreeKey(PloceusKey base) {
        for (int suffix = 2; suffix < 1000; suffix++) {
            PloceusKey candidate = PloceusKey.of(base.value() + "-" + suffix);
            if (worlds.findByKey(candidate).isEmpty()) {
                return candidate;
            }
        }
        throw new IllegalStateException("No free key for " + base.asString() + " after 1000 attempts");
    }
}
