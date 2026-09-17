package net.onelitefeather.ploceus.service;

import net.onelitefeather.ploceus.domain.*;
import net.onelitefeather.ploceus.persistence.WorldRepository;
import net.onelitefeather.ploceus.platform.Telemetry;
import net.onelitefeather.ploceus.platform.WorldSnapshot;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;

import java.util.List;

import static net.onelitefeather.ploceus.domain.AuditEntry.TargetType.WORLD;

/// Brings existing Multiverse worlds under Ploceus without guessing their grouping:
/// every unknown world becomes an unassigned entry.
public class WorldImportService {

    private static final AttributeKey<String> OUTCOME = AttributeKey.stringKey("ploceus.import.outcome");

    /// Looked up fresh rather than cached - see {@link Telemetry}.
    private static LongCounter worldsImportedCounter() {
        return Telemetry.meter().counterBuilder("ploceus.worlds.imported")
                .setDescription("Worlds processed by the startup Multiverse import, by outcome")
                .setUnit("{world}")
                .build();
    }

    public record ImportSummary(int imported, int alreadyKnown, int keyCollisions) { }

    private final WorldRepository worlds;
    private final AuditService audit;

    public WorldImportService(WorldRepository worlds, AuditService audit) {
        this.worlds = worlds;
        this.audit = audit;
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
