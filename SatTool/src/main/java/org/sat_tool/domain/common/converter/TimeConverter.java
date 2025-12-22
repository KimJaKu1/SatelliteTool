package org.sat_tool.domain.common.converter;

import org.orekit.time.AbsoluteDate;
import org.orekit.time.TimeScalesFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Locale;

@Component
public final class TimeConverter {

    // yyyyMMddHHmmssSSS : UTC compact timestamp (with millis)
    public static final DateTimeFormatter UTC_TS_COMPACT_MS = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS")
            .withZone(ZoneOffset.UTC);

    // uuuu-MM-dd HH:mm:ss.SSS : (local/DB/log style) timestamp with millis
    public static final DateTimeFormatter TS_STD_MS = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS");

    // dd MMM uuuu HH:mm:ss : UTC header/display datetime (abbr month, seconds)
    public static final DateTimeFormatter UTC_DT_HDR_ABBR = DateTimeFormatter.ofPattern("dd MMM uuuu HH:mm:ss")
            .withLocale(Locale.ENGLISH).withZone(ZoneOffset.UTC);

    // dd MMM uuuu HH:mm:ss.SSS : UTC log/display datetime (abbr month, millis)
    public static final DateTimeFormatter UTC_DT_ABBR_MS = DateTimeFormatter.ofPattern("dd MMM uuuu HH:mm:ss.SSS")
            .withLocale(Locale.ENGLISH).withZone(ZoneOffset.UTC);

    // LocalDateTime(UTC로 해석) → AbsoluteDate(UTC)
    public AbsoluteDate localDateTimeUtcToAbsoluteDate(LocalDateTime ldtUtc) {
        var utc = TimeScalesFactory.getUTC();
        var instant = ldtUtc.toInstant(ZoneOffset.UTC); // LocalDateTime(UTC로 간주) -> Instant
        return new AbsoluteDate(Date.from(instant), utc); // Instant -> Date -> AbsoluteDate
    }

    // AbsoluteDate(UTC) → LocalDateTime(UTC)
    public LocalDateTime absoluteDateToLocalDateTimeUtc(AbsoluteDate date) {
        var utc = TimeScalesFactory.getUTC();
        var instant = date.toDate(utc).toInstant(); // AbsoluteDate -> Date(UTC) -> Instant
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public String toUtcCompactMs(AbsoluteDate date) {
        // Orekit -> java.util.Date(UTC) -> Instant
        Instant instant = date.toDate(TimeScalesFactory.getUTC()).toInstant();
        return UTC_TS_COMPACT_MS.format(instant);
    }

    public String toStdMs(AbsoluteDate date) {
        // Orekit -> java.util.Date(UTC) -> Instant
        Instant instant = date.toDate(TimeScalesFactory.getUTC()).toInstant();
        return TS_STD_MS.format(instant);
    }

    public String toUtcAbbrSec(AbsoluteDate date) {
        // Orekit -> java.util.Date(UTC) -> Instant
        Instant instant = date.toDate(TimeScalesFactory.getUTC()).toInstant();
        return UTC_DT_HDR_ABBR.format(instant);
    }

    public String toUtcAbbrMSec(AbsoluteDate date) {
        // Orekit -> java.util.Date(UTC) -> Instant
        Instant instant = date.toDate(TimeScalesFactory.getUTC()).toInstant();
        return UTC_DT_ABBR_MS.format(instant);
    }

    public String toCompactUtcString(AbsoluteDate date) {
        Instant instant = date.toDate(TimeScalesFactory.getUTC()).toInstant();
        return UTC_TS_COMPACT_MS.format(instant);
    }

    public AbsoluteDate fromCompactUtcString(String s) {
        Instant instant = Instant.from(UTC_TS_COMPACT_MS.parse(s));
        return new AbsoluteDate(Date.from(instant), TimeScalesFactory.getUTC());
    }
}