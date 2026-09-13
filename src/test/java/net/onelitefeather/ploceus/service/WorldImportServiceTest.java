package net.onelitefeather.ploceus.service;

import net.onelitefeather.ploceus.domain.*;
import net.onelitefeather.ploceus.platform.WorldSnapshot;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
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
}
