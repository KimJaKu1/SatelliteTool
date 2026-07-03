package org.sat_tool.domain.common.converter;

import org.orekit.time.AbsoluteDate;
import org.orekit.time.TimeScalesFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.Locale;

@Component
public class TimeConverter {

    //region Formatters

    /** yyyyMMddHHmmssSSS : UTC compact timestamp (with millis) */
    public static final DateTimeFormatter UTC_TS_COMPACT_MS =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS").withZone(ZoneOffset.UTC);

    /** uuuu-MM-dd HH:mm:ss.SSS : (local/DB/log style) timestamp with millis */
    public static final DateTimeFormatter TS_STD_MS =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS");

    /** dd MMM uuuu HH:mm:ss : UTC header/display datetime (abbr month, seconds) */
    public static final DateTimeFormatter UTC_DT_HDR_ABBR =
            DateTimeFormatter.ofPattern("dd MMM uuuu HH:mm:ss")
                    .withLocale(Locale.ENGLISH)
                    .withZone(ZoneOffset.UTC);

    /** dd MMM uuuu HH:mm:ss.SSS : UTC log/display datetime (abbr month, millis) */
    public static final DateTimeFormatter UTC_DT_ABBR_MS =
            DateTimeFormatter.ofPattern("dd MMM uuuu HH:mm:ss.SSS")
                    .withLocale(Locale.ENGLISH)
                    .withZone(ZoneOffset.UTC);

    //endregion Formatters

    //region LocalDateTime <-> String (TS_STD_MS)
    public static String localDateTimeToString(LocalDateTime ldt) {
        return (ldt == null) ? null : TS_STD_MS.format(ldt);
    }

    /**
     * 문자열을 TS_STD_MS 포맷으로 파싱하여 LocalDateTime으로 반환.
     * (LocalDateTime은 zone 정보가 없으므로, "UTC로 간주"는 호출자 규약으로 유지)
     */
    public static LocalDateTime stringToLocalDateTime(String text) {
        if (text == null) return null;
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return null;

        try {
            return LocalDateTime.parse(trimmed, TS_STD_MS);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "Invalid datetime format. Expected pattern: " + TS_STD_MS + ", value: '" + text + "'", e);
        }
    }
    //endregion

    //region LocalDateTime(UTC로 해석) <-> AbsoluteDate(UTC)
    public static AbsoluteDate localDateTimeUtcToAbsoluteDate(LocalDateTime ldtUtc) {
        if (ldtUtc == null) return null;

        Instant instant = ldtUtc.toInstant(ZoneOffset.UTC); // LocalDateTime(UTC로 간주) -> Instant
        return new AbsoluteDate(Date.from(instant), TimeScalesFactory.getUTC());
    }

    public static LocalDateTime absoluteDateToLocalDateTimeUtc(AbsoluteDate date) {
        if (date == null) return null;

        Instant instant = date.toDate(TimeScalesFactory.getUTC()).toInstant();
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
    //endregion

    //region AbsoluteDate -> String
    /** AbsoluteDate(UTC) -> yyyyMMddHHmmssSSS (UTC) */
    public static String toUtcCompactMs(AbsoluteDate date) {
        if (date == null) return null;

        Instant instant = date.toDate(TimeScalesFactory.getUTC()).toInstant();
        return UTC_TS_COMPACT_MS.format(instant);
    }

    /**
     * AbsoluteDate(UTC) -> uuuu-MM-dd HH:mm:ss.SSS
     * TS_STD_MS는 zone이 없으므로, UTC LocalDateTime으로 변환 후 포맷
     */
    public static String toStdMs(AbsoluteDate date) {
        LocalDateTime ldtUtc = absoluteDateToLocalDateTimeUtc(date);
        return localDateTimeToString(ldtUtc);
    }

    /** AbsoluteDate(UTC) -> dd MMM uuuu HH:mm:ss (UTC) */
    public static String toUtcAbbrSec(AbsoluteDate date) {
        if (date == null) return null;

        Instant instant = date.toDate(TimeScalesFactory.getUTC()).toInstant();
        return UTC_DT_HDR_ABBR.format(instant);
    }

    /** AbsoluteDate(UTC) -> dd MMM uuuu HH:mm:ss.SSS (UTC) */
    public static String toUtcAbbrMSec(AbsoluteDate date) {
        if (date == null) return null;

        Instant instant = date.toDate(TimeScalesFactory.getUTC()).toInstant();
        return UTC_DT_ABBR_MS.format(instant);
    }
    //endregion

    //region String(UTC compact) -> AbsoluteDate
    /** yyyyMMddHHmmssSSS(UTC) -> AbsoluteDate(UTC) */
    public static AbsoluteDate fromUtcCompactMs(String s) {
        if (s == null) return null;
        String trimmed = s.trim();
        if (trimmed.isEmpty()) return null;

        try {
            Instant instant = Instant.from(UTC_TS_COMPACT_MS.parse(trimmed));
            return new AbsoluteDate(Date.from(instant), TimeScalesFactory.getUTC());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "Invalid UTC compact timestamp. Expected pattern: " + UTC_TS_COMPACT_MS + ", value: '" + s + "'", e);
        }
    }
    //endregion
}