package net.onelitefeather.ploceus.ui.inventory;

import net.onelitefeather.ploceus.domain.PloceusKey;
import net.onelitefeather.ploceus.domain.ProjectWorld;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorldIconsTest {

    private ProjectWorld world(String name) {
        return new ProjectWorld(PloceusKey.fromWorldName(name), name);
    }

    @Test
    void aLoadedWorldGetsTheGrassBlock() {
        assertThat(WorldIcons.iconFor(world("elytrarace-desert"), true)).isEqualTo(Material.GRASS_BLOCK);
    }

    @Test
    void anUnloadedWorldGetsTheGrayShape() {
        assertThat(WorldIcons.iconFor(world("elytrarace-desert"), false))
                .isEqualTo(Material.GRAY_STAINED_GLASS_PANE);
    }

    @Test
    void aPinnedWorldIsMarkedDifferently() {
        ProjectWorld pinned = world("spawn");
        pinned.setPinned(true);
        assertThat(WorldIcons.iconFor(pinned, true)).isEqualTo(Material.BEACON);
    }
}
