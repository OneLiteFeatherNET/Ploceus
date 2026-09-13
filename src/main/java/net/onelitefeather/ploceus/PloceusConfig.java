package net.onelitefeather.ploceus;

import net.onelitefeather.ploceus.domain.WorldDefaults;
import net.onelitefeather.ploceus.persistence.DatabaseConfig;
import org.bukkit.configuration.file.FileConfiguration;

import java.nio.file.Path;
import java.util.List;

public record PloceusConfig(DatabaseConfig database, int idleUnloadMinutes, int reaperIntervalSeconds,
                            int visitFlushSeconds, List<String> neverUnload, WorldDefaults worldDefaults,
                            int minDialogProtocol, int entriesPerPage) {

    public static PloceusConfig load(FileConfiguration configuration, Path dataFolder) {
        var worlds = configuration.getConfigurationSection("worlds");
        var defaults = worlds.getConfigurationSection("defaults");
        var ui = configuration.getConfigurationSection("ui");
        return new PloceusConfig(
                DatabaseConfig.fromBukkit(configuration.getConfigurationSection("database"), dataFolder),
                worlds.getInt("idle-unload-minutes", 30),
                worlds.getInt("reaper-interval-seconds", 60),
                worlds.getInt("visit-flush-seconds", 30),
                List.copyOf(worlds.getStringList("never-unload")),
                new WorldDefaults(
                        defaults.getString("generator", ""),
                        defaults.getString("generator-settings", ""),
                        defaults.getString("environment", "NORMAL"),
                        defaults.getBoolean("generate-structures", false),
                        defaults.getString("biome", "")),
                ui.getInt("min-dialog-protocol", 771),
                ui.getInt("entries-per-page", 10));
    }
}
