package net.onelitefeather.ploceus.persistence;

import net.onelitefeather.ploceus.domain.PloceusKey;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class KeyAttributeConverterTest {

    private final KeyAttributeConverter converter = new KeyAttributeConverter();

    @Test
    void roundTripsAKey() {
        PloceusKey key = PloceusKey.of("elytrarace");
        assertThat(converter.convertToDatabaseColumn(key)).isEqualTo("ploceus:elytrarace");
        assertThat(converter.convertToEntityAttribute("ploceus:elytrarace")).isEqualTo(key);
    }

    @Test
    void handlesNullInBothDirections() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }
}
