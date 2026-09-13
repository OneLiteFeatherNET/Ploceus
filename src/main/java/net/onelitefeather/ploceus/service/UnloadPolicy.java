package net.onelitefeather.ploceus.service;

import net.onelitefeather.ploceus.domain.ProjectWorld;
import net.onelitefeather.ploceus.platform.WorldSnapshot;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/// Pure decision function: no Bukkit, no database, no clock of its own.
public record UnloadPolicy(int defaultIdleMinutes, Set<String> neverUnload, String serverDefaultWorld) {

    public boolean shouldUnload(WorldSnapshot snapshot, ProjectWorld world, Instant now) {
        if (!snapshot.isIdle()) {
            return false;
        }
        if (world.isPinned()) {
            return false;
        }
        if (neverUnload.contains(snapshot.worldName())) {
            return false;
        }
        if (snapshot.worldName().equals(serverDefaultWorld)) {
            return false;
        }
        Instant lastVisited = world.getLastVisitedAt();
        if (lastVisited == null) {
            return true;
        }
        int minutes = world.getIdleUnloadMinutes() == null
                ? defaultIdleMinutes
                : world.getIdleUnloadMinutes();
        return Duration.between(lastVisited, now).toMinutes() >= minutes;
    }
}
