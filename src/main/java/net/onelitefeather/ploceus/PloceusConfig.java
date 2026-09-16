package net.onelitefeather.ploceus;

import net.onelitefeather.ploceus.domain.WorldDefaults;
import net.onelitefeather.ploceus.persistence.DatabaseConfig;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.nio.file.Path;
import java.util.List;

public record PloceusConfig(DatabaseConfig database, int idleUnloadMinutes, int reaperIntervalSeconds,
                            int visitFlushSeconds, List<String> neverUnload, WorldDefaults worldDefaults,
                            int minDialogProtocol, int entriesPerPage) {

    public static PloceusConfig load(FileConfiguration configuration, Path dataFolder) {
        var database = require(configuration, "database");
        var worlds = require(configuration, "worlds");
        var defaults = require(worlds, "worlds.defaults", "defaults");
        var ui = require(configuration, "ui");
        return new PloceusConfig(
                DatabaseConfig.fromBukkit(database, dataFolder),
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

    private static ConfigurationSection require(ConfigurationSection parent, String path) {
        return require(parent, path, path);
    }

    /// Fails with something an operator can act on instead of a bare
    /// `NullPointerException`.
    ///
    /// `saveDefaultConfig()` writes the bundled `config.yml` only when the file does not
    /// exist yet; it never merges new keys into one an operator has already edited. So a
    /// missing section is not a programming error but the ordinary outcome of a hand-edited
    /// config, or of an upgrade that added a section — and it used to abort `onEnable` with
    /// an NPE that named nothing at all.
    private static ConfigurationSection require(ConfigurationSection parent, String displayPath, String path) {
        ConfigurationSection section = parent.getConfigurationSection(path);
        if (section == null) {
            throw new IllegalStateException("config.yml is missing the '" + displayPath + "' section. "
                    + "saveDefaultConfig() does not merge new keys into an existing config, so add it "
                    + "by hand from the config.yml bundled in the Ploceus jar, or delete yours and let "
                    + "it be regenerated.");
        }
        return section;
    }
}
