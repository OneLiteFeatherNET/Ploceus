package net.onelitefeather.ploceus;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// `saveDefaultConfig()` writes the bundled `config.yml` only when the file is absent — it
/// never merges new keys into one an operator has already edited. A missing section is
/// therefore an ordinary operational situation, not a programming error, and it used to
/// abort `onEnable` with a bare `NullPointerException` that named nothing.
class PloceusConfigTest {

    private static final Path DATA_FOLDER = Path.of("plugins", "Ploceus");

    private static YamlConfiguration bundledConfig() {
        try (InputStream in = PloceusConfigTest.class.getClassLoader().getResourceAsStream("config.yml")) {
            assertThat(in).as("the bundled config.yml must be on the classpath").isNotNull();
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("could not read the bundled config.yml", exception);
        }
    }

    @Test
    void theBundledConfigLoads() {
        PloceusConfig config = PloceusConfig.load(bundledConfig(), DATA_FOLDER);

        assertThat(config.database().type()).isEqualTo("h2");
        assertThat(config.idleUnloadMinutes()).isEqualTo(30);
        assertThat(config.entriesPerPage()).isEqualTo(10);
        assertThat(config.worldDefaults().getEnvironment()).isEqualTo("NORMAL");
    }

    @TestFactory
    Stream<DynamicTest> aMissingSectionIsReportedByName() {
        return Stream.of("database", "worlds", "worlds.defaults", "ui")
                .map(section -> DynamicTest.dynamicTest("missing '" + section + "'", () -> {
                    YamlConfiguration configuration = bundledConfig();
                    configuration.set(section, null);

                    assertThatThrownBy(() -> PloceusConfig.load(configuration, DATA_FOLDER))
                            .as("an operator must be told which section is missing")
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining(section)
                            .hasMessageContaining("config.yml");
                }));
    }

    /// The bundled file has to contain every section the loader insists on, or a fresh
    /// install would fail on its first start.
    @Test
    void theBundledConfigDeclaresEverySectionTheLoaderRequires() {
        YamlConfiguration configuration = bundledConfig();
        for (String section : List.of("database", "worlds", "worlds.defaults", "ui")) {
            assertThat(configuration.getConfigurationSection(section))
                    .as("config.yml must ship the '%s' section", section)
                    .isNotNull();
        }
    }
}
