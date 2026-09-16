package net.onelitefeather.ploceus.service;

import net.onelitefeather.ploceus.domain.PloceusKey;
import net.onelitefeather.ploceus.domain.Project;
import net.onelitefeather.ploceus.domain.WorldDefaults;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorldCreationServiceTest {

    private final WorldDefaults configDefault =
            new WorldDefaults("", "", "NORMAL", false, "");

    @Test
    void aProjectPresetWins() {
        Project project = new Project(PloceusKey.of("p"), "P");
        project.setWorldDefaults(new WorldDefaults("VoidGen", "", "NORMAL", false, ""));

        assertThat(WorldCreationService.resolveDefaults(project, configDefault).getGenerator())
                .isEqualTo("VoidGen");
    }

    @Test
    void withoutAPresetTheConfigDefaultApplies() {
        Project project = new Project(PloceusKey.of("p"), "P");
        WorldDefaults fromConfig = new WorldDefaults("VoidGen", "flat", "NORMAL", false, "");

        assertThat(WorldCreationService.resolveDefaults(project, fromConfig).getGenerator())
                .isEqualTo("VoidGen");
    }

    @Test
    void withNeitherPresetNorConfigTheResultIsVanilla() {
        Project project = new Project(PloceusKey.of("p"), "P");

        WorldDefaults resolved = WorldCreationService.resolveDefaults(project, configDefault);
        assertThat(resolved.getGenerator()).isEmpty();
        assertThat(resolved.getEnvironment()).isEqualTo("NORMAL");
    }
}
