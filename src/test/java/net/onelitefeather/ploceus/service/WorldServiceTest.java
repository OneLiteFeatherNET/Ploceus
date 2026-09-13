package net.onelitefeather.ploceus.service;

import net.onelitefeather.ploceus.domain.*;
import net.onelitefeather.ploceus.platform.MultiverseAdapter;
import net.onelitefeather.ploceus.platform.WorldSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WorldServiceTest {

    private final Actor actor = new Actor(UUID.randomUUID(), "TheMeinerLP");

    private WorldSnapshot snapshot(String name) {
        return new WorldSnapshot(name, PloceusKey.fromWorldName(name), false, true, "", 0);
    }

    /// `rename` must be all-or-nothing from the caller's point of view: when mirroring the
    /// new name onto the Multiverse alias fails, neither the stored display name nor the
    /// audit trail may change, and the failure must be reported rather than swallowed.
    @Test
    void aFailedAliasMirrorLeavesTheStoredNameAndAuditTrailUnchanged() {
        MultiverseAdapter failingAlias = new MultiverseAdapter(null, null) {
            @Override
            public OperationResult<Void> setAlias(String worldName, String alias) {
                return OperationResult.failure("world.alias.failed", worldName);
            }
        };
        WorldFixtures fixtures = new WorldFixtures(failingAlias);
        fixtures.importService().importAll(List.of(snapshot("elytrarace-alien")), actor);
        PloceusKey key = fixtures.worldService().findByWorldName("elytrarace-alien").orElseThrow().getKey();
        String originalDisplayName = fixtures.worldService().find(key).orElseThrow().getDisplayName();
        int auditCountBefore = fixtures.auditCount();

        OperationResult<ProjectWorld> result = fixtures.worldService().rename(key, "New Name", actor);

        assertThat(result).isInstanceOf(OperationResult.Failure.class);
        assertThat(((OperationResult.Failure<ProjectWorld>) result).messageKey())
                .isEqualTo("world.alias.failed");
        assertThat(fixtures.worldService().find(key).orElseThrow().getDisplayName())
                .isEqualTo(originalDisplayName);
        assertThat(fixtures.auditCount()).isEqualTo(auditCountBefore);
    }

    @Test
    void aSuccessfulRenameMirrorsTheAliasAndWritesOneAuditEntry() {
        WorldFixtures fixtures = new WorldFixtures();
        fixtures.importService().importAll(List.of(snapshot("elytrarace-alien")), actor);
        PloceusKey key = fixtures.worldService().findByWorldName("elytrarace-alien").orElseThrow().getKey();
        int auditCountBefore = fixtures.auditCount();

        OperationResult<ProjectWorld> result = fixtures.worldService().rename(key, "New Name", actor);

        assertThat(result.isSuccess()).isTrue();
        assertThat(fixtures.worldService().find(key).orElseThrow().getDisplayName()).isEqualTo("New Name");
        assertThat(fixtures.auditCount()).isEqualTo(auditCountBefore + 1);
        assertThat(fixtures.lastAudit().getAction()).isEqualTo(AuditAction.WORLD_UPDATED);
    }
}
