package org.sat_tool.infra.converters;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;

public class DateTimeConverter {

    /**
     * C#의 DateTimeConverter.toDate(date, true) 식으로
     * - shortFormat = true 이면 "yyyyMMdd"
     * - shortFormat = false 이면 "yyyyMMddHHmmss"
     */

    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter LONG  = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    public static String toDate(Date date) {
        if (date == null) return "";
        ZoneId zone = ZoneId.systemDefault();
        LocalDateTime ldt = Instant.ofEpochMilli(date.getTime()).atZone(zone).toLocalDateTime();
        return (SHORT).format(ldt);
    }

    public static String toDateTime(LocalDateTime date) {
        return (date == null) ? "" : date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}