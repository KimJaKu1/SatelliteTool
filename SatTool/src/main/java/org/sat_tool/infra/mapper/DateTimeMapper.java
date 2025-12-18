package org.sat_tool.infra.mapper;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class DateTimeMapper {

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

    public static String localDateTimeToStr(LocalDateTime value) {
        if (value == null) return null;
        return value.format(FORMATTER);
    }

    public static LocalDateTime strToLocalDateTime(String text) {
        if (text == null || text.isBlank()) return null;
        return LocalDateTime.parse(text, FORMATTER);
    }
}
