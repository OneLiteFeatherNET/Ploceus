package net.onelitefeather.ploceus.platform;

import net.onelitefeather.ploceus.domain.PloceusKey;

/// A read-only view of one Multiverse world, taken on the main thread and then safe to
/// pass to the async executor.
public record WorldSnapshot(String worldName, PloceusKey derivedKey, boolean loaded,
                            boolean autoLoad, String alias, int playerCount) {

    /// Loaded but empty — a candidate for the reaper, subject to the idle timer.
    public boolean isIdle() {
        return loaded && playerCount == 0;
    }
}
