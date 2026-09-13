package net.onelitefeather.ploceus.command;

import net.onelitefeather.ploceus.domain.PloceusKey;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PloceusKeyParserTest {

    @Test
    void acceptsABareValueAndPrependsTheNamespace() {
        assertThat(PloceusKeyParser.parseInput("elytrarace"))
                .contains(PloceusKey.of("elytrarace"));
    }

    @Test
    void acceptsAFullyQualifiedKey() {
        assertThat(PloceusKeyParser.parseInput("ploceus:elytrarace"))
                .contains(PloceusKey.of("elytrarace"));
    }

    @Test
    void rejectsAForeignNamespace() {
        assertThat(PloceusKeyParser.parseInput("minecraft:overworld")).isEmpty();
    }

    @Test
    void rejectsAnInvalidValue() {
        assertThat(PloceusKeyParser.parseInput("Elytra Race")).isEmpty();
    }
}
