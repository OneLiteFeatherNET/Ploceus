package net.onelitefeather.ploceus.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PloceusKeyTest {

    @Test
    void of_prependsTheFixedNamespace() {
        assertThat(PloceusKey.of("elytrarace").asString()).isEqualTo("ploceus:elytrarace");
        assertThat(PloceusKey.of("elytrarace").value()).isEqualTo("elytrarace");
    }

    @Test
    void of_rejectsAnInvalidValue() {
        assertThatThrownBy(() -> PloceusKey.of("Bikini Russka"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Bikini Russka");
    }

    @Test
    void fromWorldName_lowercasesAndReplacesIllegalCharacters() {
        assertThat(PloceusKey.fromWorldName("elytrarace-alien").value()).isEqualTo("elytrarace-alien");
        assertThat(PloceusKey.fromWorldName("BikiniRusska").value()).isEqualTo("bikinirusska");
        assertThat(PloceusKey.fromWorldName("bak_Survival").value()).isEqualTo("bak_survival");
        assertThat(PloceusKey.fromWorldName("Hammersbuild-1").value()).isEqualTo("hammersbuild-1");
        assertThat(PloceusKey.fromWorldName("Team Build 1").value()).isEqualTo("team_build_1");
    }

    @Test
    void fromWorldName_neverProducesAnEmptyValue() {
        assertThat(PloceusKey.fromWorldName("***").value()).isEqualTo("___");
    }

    @Test
    void parse_readsBackAStoredKey() {
        assertThat(PloceusKey.parse("ploceus:elytrarace")).isEqualTo(PloceusKey.of("elytrarace"));
    }

    @Test
    void parse_rejectsAForeignNamespace() {
        assertThatThrownBy(() -> PloceusKey.parse("minecraft:overworld"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minecraft");
    }

    @Test
    void isValidValue_matchesAdventureRules() {
        assertThat(PloceusKey.isValidValue("winter_2026")).isTrue();
        assertThat(PloceusKey.isValidValue("Winter")).isFalse();
        assertThat(PloceusKey.isValidValue("")).isFalse();
    }

    @Test
    void equalityIsValueBased() {
        assertThat(PloceusKey.of("a")).isEqualTo(PloceusKey.of("a"));
        assertThat(PloceusKey.of("a")).isNotEqualTo(PloceusKey.of("b"));
    }
}
