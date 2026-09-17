package net.onelitefeather.ploceus;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.zaxxer.hikari.HikariDataSource;
import net.onelitefeather.ploceus.command.CommandRegistrar;
import net.onelitefeather.ploceus.di.PloceusModule;
import net.onelitefeather.ploceus.domain.Actor;
import net.onelitefeather.ploceus.persistence.HibernateBootstrap;
import net.onelitefeather.ploceus.platform.MultiverseAdapter;
import net.onelitefeather.ploceus.platform.SchedulerAdapter;
import net.onelitefeather.ploceus.platform.WorldSnapshot;
import net.onelitefeather.ploceus.service.WorldImportService;
import net.onelitefeather.ploceus.service.WorldLifecycleService;
import org.bukkit.plugin.java.JavaPlugin;
import org.hibernate.SessionFactory;
import org.mvplugins.multiverse.core.MultiverseCoreApi;

import java.util.List;

public final class PloceusPlugin extends JavaPlugin {

    private HikariDataSource dataSource;
    private SessionFactory sessionFactory;
    private SchedulerAdapter scheduler;
    private Injector injector;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        PloceusConfig config = PloceusConfig.load(getConfig(), getDataFolder().toPath());

        ClassLoader pluginClassLoader = getClass().getClassLoader();
        dataSource = HibernateBootstrap.dataSource(config.database());
        HibernateBootstrap.migrate(dataSource, pluginClassLoader);
        sessionFactory = HibernateBootstrap.create(config.database(), dataSource, pluginClassLoader);

        MultiverseCoreApi.whenLoaded(api -> {
            injector = Guice.createInjector(
                    new PloceusModule(this, config, sessionFactory, dataSource, api));
            scheduler = injector.getInstance(SchedulerAdapter.class);
            importExistingWorlds();
            getServer().getPluginManager().registerEvents(
                    injector.getInstance(net.onelitefeather.ploceus.listener.WorldVisitListener.class), this);
            getServer().getPluginManager().registerEvents(
                    injector.getInstance(net.onelitefeather.ploceus.listener.UiRouterListener.class), this);
            injector.getInstance(WorldLifecycleService.class).start();
            injector.getInstance(CommandRegistrar.class).register();
            getLogger().info("Ploceus enabled");
        });
    }

    /// Snapshot on the main thread, import off it. Auto-load is disabled afterwards, which
    /// only takes effect from the next server start — Multiverse loads its worlds before
    /// this plugin is enabled.
    ///
    /// `MultiverseAdapter.setAutoLoad` is durable (it folds `WorldManager.saveWorldsConfig()`
    /// into its result), so `isSuccess()` here means the change actually reached
    /// `worlds.yml`, not merely that Multiverse accepted it in memory. The log therefore
    /// counts persisted changes, not attempts, and says so explicitly when some worlds
    /// could not be persisted instead of silently reporting a smaller, still-rosy number.
    private void importExistingWorlds() {
        MultiverseAdapter multiverse = injector.getInstance(MultiverseAdapter.class);
        WorldImportService importService = injector.getInstance(WorldImportService.class);

        List<WorldSnapshot> snapshots = multiverse.snapshotAll();
        scheduler.async(() -> {
            var summary = importService.importAll(snapshots, Actor.CONSOLE);
            getLogger().info("Imported %d worlds, %d already known, %d key collisions"
                    .formatted(summary.imported(), summary.alreadyKnown(), summary.keyCollisions()));

            scheduler.sync(() -> {
                int eligible = 0;
                int persisted = 0;
                for (WorldSnapshot snapshot : snapshots) {
                    if (!snapshot.autoLoad()) {
                        continue;
                    }
                    eligible++;
                    if (multiverse.setAutoLoad(snapshot.worldName(), false).isSuccess()) {
                        persisted++;
                    }
                }
                if (persisted > 0) {
                    getLogger().info(("Disabled auto-load for %d of %d worlds and saved it to "
                            + "worlds.yml. This takes effect from the next server start.")
                            .formatted(persisted, eligible));
                }
                if (persisted < eligible) {
                    getLogger().warning(("Could not persist the auto-load change for %d of %d "
                            + "world(s); see the warnings above for which ones. They will still "
                            + "auto-load at the next server start.").formatted(eligible - persisted, eligible));
                }
            });
        });
    }

    /// Closes everything this plugin opened, in reverse order of opening, and keeps going
    /// when one of them fails.
    ///
    /// The order matters — the lifecycle service's final visit flush needs the pool to still
    /// be open — but so does the independence: a failure in the first step used to propagate
    /// straight out of `onDisable`, skipping `sessionFactory.close()` and
    /// `dataSource.close()` entirely and leaking the connection pool for the rest of the
    /// server's life.
    @Override
    public void onDisable() {
        closeQuietly("the world lifecycle service", () -> {
            if (injector != null) {
                injector.getInstance(WorldLifecycleService.class).close();
            }
        });
        closeQuietly("the scheduler", () -> {
            if (scheduler != null) {
                scheduler.close();
            }
        });
        closeQuietly("the Hibernate session factory", () -> {
            if (sessionFactory != null) {
                sessionFactory.close();
            }
        });
        closeQuietly("the connection pool", () -> {
            if (dataSource != null) {
                dataSource.close();
            }
        });
        getLogger().info("Ploceus disabled");
    }

    private void closeQuietly(String what, Runnable close) {
        try {
            close.run();
        } catch (Throwable throwable) {
            getLogger().log(java.util.logging.Level.SEVERE,
                    "Failed to close " + what + "; continuing shutdown", throwable);
        }
    }

    public Injector injector() {
        return injector;
    }
}
