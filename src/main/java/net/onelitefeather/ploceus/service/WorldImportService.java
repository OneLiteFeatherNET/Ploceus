package net.onelitefeather.ploceus.service;

import net.onelitefeather.ploceus.domain.*;
import net.onelitefeather.ploceus.persistence.WorldRepository;
import net.onelitefeather.ploceus.platform.WorldSnapshot;

import java.util.List;

import static net.onelitefeather.ploceus.domain.AuditEntry.TargetType.WORLD;

/// Brings existing Multiverse worlds under Ploceus without guessing their grouping:
/// every unknown world becomes an unassigned entry.
public class WorldImportService {

    public record ImportSummary(int imported, int alreadyKnown, int keyCollisions) { }

    private final WorldRepository worlds;
    private final AuditService audit;

    public WorldImportService(WorldRepository worlds, AuditService audit) {
        this.worlds = worlds;
        this.audit = audit;
    }

    public ImportSummary importAll(List<WorldSnapshot> snapshots, Actor actor) {
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
