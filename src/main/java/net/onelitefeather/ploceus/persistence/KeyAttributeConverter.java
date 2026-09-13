package net.onelitefeather.ploceus.persistence;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import net.onelitefeather.ploceus.domain.PloceusKey;

@Converter(autoApply = true)
public class KeyAttributeConverter implements AttributeConverter<PloceusKey, String> {

    @Override
    public String convertToDatabaseColumn(PloceusKey key) {
        return key == null ? null : key.asString();
    }

    @Override
    public PloceusKey convertToEntityAttribute(String stored) {
        return stored == null ? null : PloceusKey.parse(stored);
    }
}
