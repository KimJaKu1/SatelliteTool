package org.sat_tool.infra.converters;

import jakarta.persistence.AttributeConverter;

public class ByteToBooleanConverter implements AttributeConverter<Boolean, Byte> {

    @Override
    public Byte convertToDatabaseColumn(Boolean attribute) {
        // Boolean -> Byte
        if (attribute == null) {
            return null;
        }
        return (byte) (attribute ? 1 : 0); // true -> 1, false -> 0
    }

    @Override
    public Boolean convertToEntityAttribute(Byte dbData) {
        // Byte -> Boolean
        if (dbData == null) {
            return null;
        }
        // DB에서 읽어온 값이 1이면 true, 그 외(0, etc.)는 false로 처리
        return dbData == 1;
    }
}
