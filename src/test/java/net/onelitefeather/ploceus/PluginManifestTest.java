package net.onelitefeather.ploceus;

import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.ClassPathLibrary;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// The safety net for the one defect the rest of a green suite structurally cannot see: a
/// plugin that compiles, tests and builds, and then cannot start.
///
/// The whole persistence stack (Hibernate, HikariCP, Liquibase, H2, the PostgreSQL driver)
/// is `compileOnly` in the build and is expected to arrive at runtime through Paper's
/// `MavenLibraryResolver`. Paper only calls `PluginLoader.classloader(...)` on the class
/// named by `paper-plugin.yml`'s `loader:` key — and that key was missing, so nothing ever
/// resolved those libraries and `onEnable` died with `NoClassDefFoundError`. Every test
/// passed anyway, because the *test* classpath carries the bundle.
///
/// These assertions therefore read the real manifest rather than trusting the source.
class PluginManifestTest {

    private static final Map<String, Object> MANIFEST = loadManifest();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadManifest() {
        try (InputStream in = PluginManifestTest.class.getClassLoader()
                .getResourceAsStream("paper-plugin.yml")) {
            assertThat(in).as("paper-plugin.yml must be on the runtime classpath").isNotNull();
            return (Map<String, Object>) new Yaml().load(in);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not read paper-plugin.yml", exception);
        }
    }

    private static String entry(String key) {
        Object value = MANIFEST.get(key);
        assertThat(value).as("paper-plugin.yml must declare '%s'", key).isNotNull();
        return value.toString();
    }

    private static Class<?> declaredClass(String key) throws ClassNotFoundException {
        return Class.forName(entry(key), false, PluginManifestTest.class.getClassLoader());
    }

    @Test
    void mainNamesAJavaPlugin() throws Exception {
        assertThat(JavaPlugin.class).isAssignableFrom(declaredClass("main"));
    }

    @Test
    void bootstrapperNamesAPluginBootstrap() throws Exception {
        assertThat(PluginBootstrap.class).isAssignableFrom(declaredClass("bootstrapper"));
    }

    /// The regression this class exists for. Paper reads `loader:` and, only if it is
    /// present, instantiates that class and calls `classloader(...)` on it — see
    /// `PaperPluginProviderFactory.build`. Paper does *not* require the loader and the
    /// bootstrapper to be different classes, so naming `PloceusBootstrap` for both is
    /// legitimate; what is not legitimate is omitting the key.
    @Test
    void loaderNamesAPluginLoaderWithAUsableConstructor() throws Exception {
        Class<?> loader = declaredClass("loader");
        assertThat(PluginLoader.class)
                .as("the class named by 'loader:' must implement Paper's PluginLoader, or Paper "
                        + "cannot call classloader(...) and the runtime libraries never resolve")
                .isAssignableFrom(loader);
        assertThat(loader.getDeclaredConstructor().newInstance()).isInstanceOf(PluginLoader.class);
    }

    /// Proves the declared loader really registers a classpath library, rather than merely
    /// implementing the interface. `MavenLibraryResolver` performs no I/O until Paper calls
    /// `register(...)`, which this never does.
    @Test
    void theDeclaredLoaderRegistersAtLeastOneClasspathLibrary() throws Exception {
        PluginLoader loader = (PluginLoader) declaredClass("loader")
                .getDeclaredConstructor().newInstance();
        List<ClassPathLibrary> registered = new ArrayList<>();

        loader.classloader(new PluginClasspathBuilder() {
            @Override
            public PluginClasspathBuilder addLibrary(ClassPathLibrary classPathLibrary) {
                registered.add(classPathLibrary);
                return this;
            }

            @Override
            public io.papermc.paper.plugin.bootstrap.PluginProviderContext getContext() {
                throw new UnsupportedOperationException("not needed to collect libraries");
            }
        });

        assertThat(registered).isNotEmpty();
    }

    /// "Resolvable" without a network round trip: the build declares the very same
    /// coordinates as `testImplementation(libs.bundles.persistence)`, so Gradle has already
    /// resolved each one from a real repository and put its jar on this test's classpath. A
    /// coordinate whose artifact or version is wrong would simply not be there.
    @TestFactory
    Stream<DynamicTest> everyDeclaredCoordinateIsResolvable() {
        List<String> classpath = Arrays.stream(System.getProperty("java.class.path").split(":"))
                .map(entry -> Path.of(entry).getFileName().toString())
                .toList();
        return PloceusBootstrap.RUNTIME_LIBRARIES.stream().map(coordinate -> DynamicTest.dynamicTest(
                coordinate + " resolves", () -> {
                    String[] parts = coordinate.split(":");
                    assertThat(parts).as("a maven coordinate is group:artifact:version").hasSize(3);
                    String jarName = parts[1] + "-" + parts[2] + ".jar";
                    assertThat(classpath)
                            .as("%s is declared for runtime resolution but the build resolves no "
                                    + "such artifact; the coordinate and the version catalog have drifted",
                                    coordinate)
                            .contains(jarName);
                }));
    }

    /// Every `ploceus.*` node the code checks must be declared, and every declared node
    /// must be one the code checks — a leaf nobody enforces is a promise to an operator that
    /// nothing keeps. Aggregate nodes (the ones with `children`) are exempt from the second
    /// half, since they exist to be granted rather than checked.
    ///
    /// Reads the sources rather than the bytecode: the nodes are plain string literals
    /// spread across the command layer and two UI channels, and this is the cheapest way to
    /// see all of them at once.
    @Test
    void everyPermissionNodeInTheCodeIsDeclaredAndViceVersa() throws Exception {
        Set<String> used = new TreeSet<>();
        Pattern node = Pattern.compile("\"(ploceus(?:\\.[a-z]+)+)\"");
        try (Stream<Path> sources = Files.walk(Path.of("src/main/java"))) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher matcher = node.matcher(Files.readString(source));
                while (matcher.find()) {
                    used.add(matcher.group(1));
                }
            }
        }
        assertThat(used).as("no permission nodes found; the source scan is broken").isNotEmpty();

        Map<String, Object> permissions = declaredPermissions();
        assertThat(permissions.keySet())
                .as("paper-plugin.yml must declare every node the code checks, so LuckPerms "
                        + "and /help can see them")
                .containsAll(used);

        Set<String> leaves = permissions.entrySet().stream()
                .filter(entry -> !asMap(entry.getValue()).containsKey("children"))
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        assertThat(leaves)
                .as("a declared node that nothing in the code checks is a promise to the "
                        + "operator that nobody keeps")
                .containsExactlyInAnyOrderElementsOf(used);
    }

    @Test
    void everyDeclaredPermissionHasADescriptionAndADefault() {
        declaredPermissions().forEach((name, body) -> {
            Map<String, Object> fields = asMap(body);
            assertThat(fields).as("%s needs a description an operator can read", name)
                    .containsKey("description");
            assertThat(fields).as("%s needs an explicit default", name).containsKey("default");
        });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> declaredPermissions() {
        Object permissions = MANIFEST.get("permissions");
        assertThat(permissions).as("paper-plugin.yml must declare a permissions block").isNotNull();
        return (Map<String, Object>) permissions;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @Test
    void theDeclaredVersionIsSubstituted() {
        assertThat(entry("version"))
                .as("the '${version}' placeholder must be replaced when resources are processed")
                .doesNotContain("$");
    }
}
