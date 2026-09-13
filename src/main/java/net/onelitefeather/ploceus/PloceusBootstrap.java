package net.onelitefeather.ploceus;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;
import org.jetbrains.annotations.NotNull;

public final class PloceusBootstrap implements PluginBootstrap, PluginLoader {

    private static final String CENTRAL = "https://repo.maven.apache.org/maven2/";

    private static final String[] RUNTIME_LIBRARIES = {
            "org.hibernate.orm:hibernate-core:7.4.8.Final",
            "com.zaxxer:HikariCP:7.1.0",
            "org.liquibase:liquibase-core:5.0.4",
            "com.h2database:h2:2.5.250",
            "org.postgresql:postgresql:42.7.13"
    };

    @Override
    public void bootstrap(@NotNull BootstrapContext context) {
        // Nothing to do yet; kept so later tasks have a hook before the server starts.
    }

    @Override
    public void classloader(@NotNull PluginClasspathBuilder classpathBuilder) {
        MavenLibraryResolver resolver = new MavenLibraryResolver();
        resolver.addRepository(new RemoteRepository.Builder("central", "default", CENTRAL).build());
        for (String coordinate : RUNTIME_LIBRARIES) {
            resolver.addDependency(new Dependency(new DefaultArtifact(coordinate), null));
        }
        classpathBuilder.addLibrary(resolver);
    }
}
