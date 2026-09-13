package net.onelitefeather.ploceus;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.zaxxer.hikari.HikariDataSource;
import net.onelitefeather.ploceus.di.PloceusModule;
import net.onelitefeather.ploceus.domain.Actor;
import net.onelitefeather.ploceus.persistence.HibernateBootstrap;
import net.onelitefeather.ploceus.platform.MultiverseAdapter;
import net.onelitefeather.ploceus.platform.SchedulerAdapter;
import net.onelitefeather.ploceus.platform.WorldSnapshot;
import net.onelitefeather.ploceus.service.WorldImportService;
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
            getLogger().info("Ploceus enabled");
        });
    }

    /// Snapshot on the main thread, import off it. Auto-load is disabled afterwards, which
    /// only takes effect from the next server start — Multiverse loads its worlds before
    /// this plugin is enabled.
    private void importExistingWorlds() {
        MultiverseAdapter multiverse = injector.getInstance(MultiverseAdapter.class);
        WorldImportService importService = injector.getInstance(WorldImportService.class);

        List<WorldSnapshot> snapshots = multiverse.snapshotAll();
        scheduler.async(() -> {
            var summary = importService.importAll(snapshots, Actor.CONSOLE);
            getLogger().info("Imported %d worlds, %d already known, %d key collisions"
                    .formatted(summary.imported(), summary.alreadyKnown(), summary.keyCollisions()));

            scheduler.sync(() -> {
                int suppressed = 0;
                for (WorldSnapshot snapshot : snapshots) {
                    if (snapshot.autoLoad() && multiverse.setAutoLoad(snapshot.worldName(), false).isSuccess()) {
                        suppressed++;
                    }
                }
                if (suppressed > 0) {
                    getLogger().info(("Disabled auto-load for %d worlds. "
                            + "This takes effect from the next server start.").formatted(suppressed));
                }
            });
        });
    }

    @Override
    public void onDisable() {
        if (scheduler != null) {
            scheduler.close();
        }
        if (sessionFactory != null) {
            sessionFactory.close();
        }
        if (dataSource != null) {
            dataSource.close();
        }
        getLogger().info("Ploceus disabled");
    }

    public Injector injector() {
        return injector;
    }
}
