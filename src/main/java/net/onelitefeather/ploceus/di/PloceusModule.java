package net.onelitefeather.ploceus.di;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.inject.Singleton;
import net.onelitefeather.ploceus.PloceusConfig;
import net.onelitefeather.ploceus.persistence.*;
import net.onelitefeather.ploceus.platform.MultiverseAdapter;
import net.onelitefeather.ploceus.platform.SchedulerAdapter;
import net.onelitefeather.ploceus.service.*;
import net.onelitefeather.ploceus.ui.ProjectUi;
import net.onelitefeather.ploceus.ui.UiChannel;
import net.onelitefeather.ploceus.ui.UiPreferences;
import net.onelitefeather.ploceus.ui.chat.ChatUi;
import net.onelitefeather.ploceus.ui.dialog.DialogUi;
import org.bukkit.plugin.Plugin;
import org.hibernate.SessionFactory;
import org.mvplugins.multiverse.core.MultiverseCoreApi;

public class PloceusModule extends AbstractModule {

    private final Plugin plugin;
    private final PloceusConfig config;
    private final SessionFactory sessionFactory;
    private final HikariDataSource dataSource;
    private final MultiverseCoreApi multiverseApi;

    public PloceusModule(Plugin plugin, PloceusConfig config, SessionFactory sessionFactory,
                         HikariDataSource dataSource, MultiverseCoreApi multiverseApi) {
        this.plugin = plugin;
        this.config = config;
        this.sessionFactory = sessionFactory;
        this.dataSource = dataSource;
        this.multiverseApi = multiverseApi;
    }

    @Override
    protected void configure() {
        bind(Plugin.class).toInstance(plugin);
        bind(PloceusConfig.class).toInstance(config);
        bind(SessionFactory.class).toInstance(sessionFactory);
        bind(HikariDataSource.class).toInstance(dataSource);
        bind(MultiverseCoreApi.class).toInstance(multiverseApi);
    }

    /// UI channels: dialog now has a real implementation (Tasks 15-16); inventory is added
    /// by Task 17 and still falls back to chat in the meantime, so `UiRouter.forPlayer`
    /// never resolves to a missing channel and returns `null` — a real risk now that Task
    /// 14 wires actual command handlers through `UiRouter`: `UiRouter.choose` defaults
    /// unpreferenced players to `DIALOG` once their protocol version reaches
    /// `min-dialog-protocol`, which most current clients do.
    @Provides @Singleton
    java.util.Map<UiChannel, ProjectUi> channels(ChatUi chat, DialogUi dialog) {
        return java.util.Map.of(
                UiChannel.CHAT, chat,
                UiChannel.INVENTORY, chat,
                UiChannel.DIALOG, dialog);
    }

    @Provides @Singleton SchedulerAdapter scheduler(Plugin plugin) {
        return new SchedulerAdapter(plugin);
    }

    @Provides @Singleton MultiverseAdapter multiverse(MultiverseCoreApi api, SchedulerAdapter scheduler) {
        return new MultiverseAdapter(api, scheduler);
    }

    @Provides @Singleton ProjectRepository projectRepository(SessionFactory factory) {
        return new ProjectRepository(factory);
    }

    @Provides @Singleton WorldRepository worldRepository(SessionFactory factory) {
        return new WorldRepository(factory);
    }

    @Provides @Singleton TagRepository tagRepository(SessionFactory factory) {
        return new TagRepository(factory);
    }

    @Provides @Singleton AuditRepository auditRepository(SessionFactory factory) {
        return new AuditRepository(factory);
    }

    @Provides @Singleton PlayerPreferenceRepository playerPreferenceRepository(SessionFactory factory) {
        return new PlayerPreferenceRepository(factory);
    }

    @Provides @Singleton UiPreferences uiPreferences(PlayerPreferenceRepository repository) {
        return repository;
    }

    @Provides @Singleton AuditService auditService(AuditRepository repository) {
        return new AuditService(repository);
    }

    @Provides @Singleton ProjectService projectService(ProjectRepository projects, TagRepository tags,
                                                       AuditService audit) {
        return new ProjectService(projects, tags, audit);
    }

    @Provides @Singleton WorldService worldService(WorldRepository worlds, ProjectRepository projects,
                                                   TagRepository tags, AuditService audit,
                                                   MultiverseAdapter multiverse) {
        return new WorldService(worlds, projects, tags, audit, multiverse);
    }

    @Provides @Singleton WorldImportService importService(WorldRepository worlds, AuditService audit) {
        return new WorldImportService(worlds, audit);
    }

    @Provides @Singleton
    UnloadPolicy unloadPolicy(PloceusConfig config, Plugin plugin) {
        String defaultWorld = plugin.getServer().getWorlds().getFirst().getName();
        return new UnloadPolicy(config.idleUnloadMinutes(),
                java.util.Set.copyOf(config.neverUnload()), defaultWorld);
    }

    @Provides @Singleton
    VisitTracker visitTracker(WorldRepository worlds, SchedulerAdapter scheduler) {
        return new VisitTracker(worlds, scheduler);
    }

    @Provides @Singleton
    WorldLifecycleService lifecycle(MultiverseAdapter multiverse, WorldRepository worlds,
                                    SchedulerAdapter scheduler, VisitTracker visits,
                                    UnloadPolicy policy, PloceusConfig config) {
        return new WorldLifecycleService(multiverse, worlds, scheduler, visits, policy, config);
    }
}
