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

import java.util.List;

/// Both entry points Paper calls before the plugin itself exists.
///
/// ## Why one class serves as both `bootstrapper:` and `loader:`
///
/// Paper reads the two keys independently (`PaperPluginProviderFactory.build`, verified
/// against `paper-26.2.jar` build 123): the `loader:` class is instantiated through a
/// throwaway `PaperSimplePluginClassLoader` and its `classloader(...)` is called *before*
/// the real plugin classloader is built; the `bootstrapper:` class is instantiated later,
/// on the plugin's own classloader. Neither key is validated against the other, so naming
/// the same class in both is supported — it simply gets loaded twice, once per classloader.
/// That is exactly what `PluginLoader`'s own Javadoc warns about ("this class will be
/// called from a different classloader, this will cause any static values set in this
/// class ... not to persist"), which is why nothing in here keeps state: the constants
/// below are re-read from scratch in each classloader, and {@link #bootstrap} is empty.
///
/// **Without the `loader:` key this class's {@link #classloader} is never called at all**,
/// and since every persistence dependency is `compileOnly` in the build, the plugin would
/// die in `onEnable` with `NoClassDefFoundError: com/zaxxer/hikari/HikariConfig`.
/// `PluginManifestTest` exists to keep that key, and the coordinates below, honest.
public final class PloceusBootstrap implements PluginBootstrap, PluginLoader {

    /// Everything the build declares `compileOnly` and therefore expects Paper to supply at
    /// runtime — the `persistence` bundle in `settings.gradle.kts`, coordinate for
    /// coordinate. `PluginManifestTest.everyDeclaredCoordinateIsResolvable` fails if the two
    /// ever drift apart.
    public static final List<String> RUNTIME_LIBRARIES = List.of(
            "org.hibernate.orm:hibernate-core:7.4.8.Final",
            "com.zaxxer:HikariCP:7.1.0",
            "org.liquibase:liquibase-core:5.0.4",
            "com.h2database:h2:2.5.250",
            "org.postgresql:postgresql:42.7.13");

    @Override
    public void bootstrap(@NotNull BootstrapContext context) {
        // Nothing to do yet; kept so later tasks have a hook before the server starts.
    }

    /// Called by Paper only because `paper-plugin.yml` names this class under `loader:`.
    ///
    /// Resolves through {@link MavenLibraryResolver#MAVEN_CENTRAL_DEFAULT_MIRROR} rather
    /// than Maven Central itself: `MavenLibraryResolver.addRepository` logs a warning with a
    /// stack trace for any Central URL, because using Central as a CDN is against its terms
    /// of service and gets users rate-limited.
    @Override
    public void classloader(@NotNull PluginClasspathBuilder classpathBuilder) {
        MavenLibraryResolver resolver = new MavenLibraryResolver();
        resolver.addRepository(new RemoteRepository.Builder(
                "central", "default", MavenLibraryResolver.MAVEN_CENTRAL_DEFAULT_MIRROR).build());
        for (String coordinate : RUNTIME_LIBRARIES) {
            resolver.addDependency(new Dependency(new DefaultArtifact(coordinate), null));
        }
        classpathBuilder.addLibrary(resolver);
    }
}
