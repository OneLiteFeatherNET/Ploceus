package net.onelitefeather.ploceus.platform;

import net.onelitefeather.ploceus.domain.PloceusKey;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class WorldSnapshotTest {

    private WorldSnapshot snapshot(boolean loaded, int players) {
        return new WorldSnapshot("elytrarace-desert",
                PloceusKey.fromWorldName("elytrarace-desert"), loaded, false, "Desert", players);
    }

    @Test
    void anEmptyLoadedWorldIsIdle() {
        assertThat(snapshot(true, 0).isIdle()).isTrue();
    }

    @Test
    void anOccupiedWorldIsNotIdle() {
        assertThat(snapshot(true, 3).isIdle()).isFalse();
    }

    @Test
    void anUnloadedWorldIsNotIdle() {
        assertThat(snapshot(false, 0).isIdle()).isFalse();
    }
}
