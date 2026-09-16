package net.onelitefeather.ploceus.ui.inventory;

import net.onelitefeather.ploceus.domain.ProjectWorld;
import org.bukkit.Material;

public final class WorldIcons {

    private WorldIcons() { }

    /// Icons are the reason this channel exists alongside dialogs: load state and role are
    /// readable at a glance.
    public static Material iconFor(ProjectWorld world, boolean loaded) {
        if (world.isPinned()) {
            return Material.BEACON;
        }
        return loaded ? Material.GRASS_BLOCK : Material.GRAY_STAINED_GLASS_PANE;
    }

    public static Material projectIcon() {
        return Material.BOOKSHELF;
    }
}
