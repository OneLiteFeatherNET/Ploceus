package net.onelitefeather.ploceus.service;

import net.onelitefeather.ploceus.domain.PloceusKey;
import net.onelitefeather.ploceus.domain.ProjectWorld;
import net.onelitefeather.ploceus.platform.WorldSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class UnloadPolicyTest {

    private static final Instant NOW = Instant.parse("2026-09-13T12:00:00Z");

    private final UnloadPolicy policy = new UnloadPolicy(30, Set.of("spawn"), "world");

    private WorldSnapshot snapshot(String name, boolean loaded, int players) {
        return new WorldSnapshot(name, PloceusKey.fromWorldName(name), loaded, false, "", players);
    }

    private ProjectWorld world(String name, Instant lastVisited) {
        ProjectWorld world = new ProjectWorld(PloceusKey.fromWorldName(name), name);
        world.setLastVisitedAt(lastVisited);
        return world;
    }

    @Test
    void unloadsALoadedEmptyWorldPastItsIdleTime() {
        assertThat(policy.shouldUnload(
                snapshot("elytrarace-desert", true, 0),
                world("elytrarace-desert", NOW.minus(31, ChronoUnit.MINUTES)),
                NOW)).isTrue();
    }

    @Test
    void keepsAWorldStillInsideItsIdleTime() {
        assertThat(policy.shouldUnload(
                snapshot("elytrarace-desert", true, 0),
                world("elytrarace-desert", NOW.minus(29, ChronoUnit.MINUTES)),
                NOW)).isFalse();
    }

    @Test
    void keepsAnOccupiedWorld() {
        assertThat(policy.shouldUnload(
                snapshot("elytrarace-desert", true, 1),
                world("elytrarace-desert", NOW.minus(5, ChronoUnit.HOURS)),
                NOW)).isFalse();
    }

    @Test
    void keepsAnAlreadyUnloadedWorld() {
        assertThat(policy.shouldUnload(
                snapshot("elytrarace-desert", false, 0),
                world("elytrarace-desert", NOW.minus(5, ChronoUnit.HOURS)),
                NOW)).isFalse();
    }

    @Test
    void keepsAPinnedWorld() {
        ProjectWorld pinned = world("elytrarace-desert", NOW.minus(5, ChronoUnit.HOURS));
        pinned.setPinned(true);
        assertThat(policy.shouldUnload(snapshot("elytrarace-desert", true, 0), pinned, NOW)).isFalse();
    }

    @Test
    void keepsAWorldOnTheNeverUnloadList() {
        assertThat(policy.shouldUnload(
                snapshot("spawn", true, 0),
                world("spawn", NOW.minus(5, ChronoUnit.HOURS)),
                NOW)).isFalse();
    }

    @Test
    void keepsTheServerDefaultWorld() {
        assertThat(policy.shouldUnload(
                snapshot("world", true, 0),
                world("world", NOW.minus(5, ChronoUnit.HOURS)),
                NOW)).isFalse();
    }

    @Test
    void honoursAPerWorldOverride() {
        ProjectWorld impatient = world("elytrarace-desert", NOW.minus(6, ChronoUnit.MINUTES));
        impatient.setIdleUnloadMinutes(5);
        assertThat(policy.shouldUnload(snapshot("elytrarace-desert", true, 0), impatient, NOW)).isTrue();
    }

    @Test
    void treatsAWorldNeverVisitedAsEligible() {
        assertThat(policy.shouldUnload(
                snapshot("elytrarace-desert", true, 0),
                world("elytrarace-desert", null),
                NOW)).isTrue();
    }
}
