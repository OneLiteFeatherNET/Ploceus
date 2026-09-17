package net.onelitefeather.ploceus.service;

import net.onelitefeather.ploceus.domain.*;
import net.onelitefeather.ploceus.platform.MultiverseAdapter;
import net.onelitefeather.ploceus.platform.WorldSnapshot;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

class WorldImportServiceTest {

    private final Actor actor = new Actor(UUID.randomUUID(), "TheMeinerLP");

    private WorldSnapshot snapshot(String name) {
        return new WorldSnapshot(name, PloceusKey.fromWorldName(name), false, true, "", 0);
    }

    @Test
    void importsEveryUnknownWorldAsUnassigned() {
        WorldFixtures fixtures = new WorldFixtures();
        var summary = fixtures.importService().importAll(
                List.of(snapshot("elytrarace-alien"), snapshot("BikiniRusska")), actor);

        assertThat(summary.imported()).isEqualTo(2);
        assertThat(fixtures.worldService().unassigned())
                .extracting(ProjectWorld::getWorldName)
                .containsExactlyInAnyOrder("elytrarace-alien", "BikiniRusska");
    }

    @Test
    void derivesTheKeyFromTheWorldName() {
        WorldFixtures fixtures = new WorldFixtures();
        fixtures.importService().importAll(List.of(snapshot("BikiniRusska")), actor);

        assertThat(fixtures.worldService().findByWorldName("BikiniRusska").orElseThrow()
                .getKey().asString()).isEqualTo("ploceus:bikinirusska");
    }

    @Test
    void aSecondImportChangesNothing() {
        WorldFixtures fixtures = new WorldFixtures();
        fixtures.importService().importAll(List.of(snapshot("elytrarace-alien")), actor);
        var second = fixtures.importService().importAll(List.of(snapshot("elytrarace-alien")), actor);

        assertThat(second.imported()).isZero();
        assertThat(second.alreadyKnown()).isEqualTo(1);
        assertThat(fixtures.worldService().all()).hasSize(1);
    }

    @Test
    void aKeyCollisionGetsANumericSuffix() {
        WorldFixtures fixtures = new WorldFixtures();
        // both normalise to "ploceus:bikinirusska"
        var summary = fixtures.importService().importAll(
                List.of(snapshot("BikiniRusska"), snapshot("bikinirusska")), actor);

        assertThat(summary.imported()).isEqualTo(2);
        assertThat(summary.keyCollisions()).isEqualTo(1);
        assertThat(fixtures.worldService().all())
                .extracting(w -> w.getKey().value())
                .containsExactlyInAnyOrder("bikinirusska", "bikinirusska-2");
    }

    // --- importOne(...): the operator-driven path, /ploceus world import <folderName> ---

    @Test
    void importOneBringsAnUnmanagedFolderUnderPloceusAsUnassigned() {
        WorldFixtures fixtures = new WorldFixtures();

        var result = fixtures.importService().importOne("elytrarace-alien", actor).join();

        assertThat(result.isSuccess()).isTrue();
        ProjectWorld world = fixtures.worldService().findByWorldName("elytrarace-alien").orElseThrow();
        assertThat(world.isUnassigned()).isTrue();
        assertThat(fixtures.lastAudit().getAction()).isEqualTo(AuditAction.WORLD_IMPORTED);
    }

    @Test
    void importOneRefusesAWorldAlreadyKnownByNameAndNeverCallsMultiverseAgain() {
        AtomicInteger importCalls = new AtomicInteger();
        MultiverseAdapter counting = new MultiverseAdapter(null, null) {
            @Override public OperationResult<String> importWorld(String worldName) {
                importCalls.incrementAndGet();
                return OperationResult.success(worldName);
            }
            @Override public OperationResult<Void> setAutoLoad(String worldName, boolean autoLoad) {
                return OperationResult.success(null);
            }
        };
        WorldFixtures fixtures = new WorldFixtures(counting);
        fixtures.importService().importOne("elytrarace-alien", actor).join();
        importCalls.set(0);

        var result = fixtures.importService().importOne("elytrarace-alien", actor).join();

        assertThat(result.isSuccess()).isFalse();
        assertThat(((OperationResult.Failure<?>) result).messageKey()).isEqualTo("world.exists");
        assertThat(importCalls.get())
                .as("a world Ploceus already knows by name must never reach Multiverse a second time")
                .isZero();
        assertThat(fixtures.worldService().all()).hasSize(1);
    }

    /// Same suffix fallback `aKeyCollisionGetsANumericSuffix` proves for the bulk import —
    /// `importOne` shares the private `nextFreeKey` helper, but it is a separate code path
    /// (single-world reservation instead of a loop over snapshots) worth covering on its own.
    @Test
    void importOneAppliesTheSameCollisionSuffixLogicAsImportAll() {
        WorldFixtures fixtures = new WorldFixtures();
        fixtures.importService().importOne("BikiniRusska", actor).join();

        var result = fixtures.importService().importOne("bikinirusska", actor).join();

        assertThat(result.isSuccess()).isTrue();
        assertThat(fixtures.worldService().all())
                .extracting(w -> w.getKey().value())
                .containsExactlyInAnyOrder("bikinirusska", "bikinirusska-2");
    }

    /// The ordering guarantee `importOne`'s Javadoc argues for: the database row is reserved
    /// before Multiverse is ever touched, so a Multiverse refusal must roll that reservation
    /// back rather than leave an orphaned, permanently-unimportable row behind.
    @Test
    void aFailingMultiverseImportLeavesNoDatabaseRowBehind() {
        MultiverseAdapter refusing = new MultiverseAdapter(null, null) {
            @Override public OperationResult<String> importWorld(String worldName) {
                return OperationResult.failure("world.import.failed", "WORLD_FOLDER_INVALID");
            }
        };
        WorldFixtures fixtures = new WorldFixtures(refusing);

        var result = fixtures.importService().importOne("not-a-world", actor).join();

        assertThat(result.isSuccess()).isFalse();
        assertThat(((OperationResult.Failure<?>) result).messageKey()).isEqualTo("world.import.failed");
        assertThat(fixtures.worldService().findByWorldName("not-a-world")).isEmpty();
        assertThat(fixtures.worldService().all()).isEmpty();
    }

    @Test
    void aFailedAutoLoadMirrorDoesNotFailTheImportButIsAuditedAndSurfacedAsAWarning() {
        MultiverseAdapter partial = new MultiverseAdapter(null, null) {
            @Override public OperationResult<String> importWorld(String worldName) {
                return OperationResult.success(worldName);
            }
            @Override public OperationResult<Void> setAutoLoad(String worldName, boolean autoLoad) {
                return OperationResult.failure("world.autoload.failed", "no such world");
            }
        };
        WorldFixtures fixtures = new WorldFixtures(partial);

        var result = fixtures.importService().importOne("warned-world", actor).join();

        assertThat(result.isSuccess()).isTrue();
        var outcome = ((OperationResult.Success<WorldImportService.ImportOutcome>) result).value();
        assertThat(outcome.warnings()).extracting(OperationResult.Failure::messageKey)
                .containsExactly("world.autoload.failed");
        assertThat(fixtures.worldService().findByWorldName("warned-world")).isPresent();
        assertThat(fixtures.lastAudit().getAction()).isEqualTo(AuditAction.WORLD_IMPORTED);
    }
}
