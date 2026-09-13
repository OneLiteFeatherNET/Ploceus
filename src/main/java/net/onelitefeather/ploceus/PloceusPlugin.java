package net.onelitefeather.ploceus;

import org.bukkit.plugin.java.JavaPlugin;

public final class PloceusPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getLogger().info("Ploceus enabled");
    }

    @Override
    public void onDisable() {
        getLogger().info("Ploceus disabled");
    }
}
