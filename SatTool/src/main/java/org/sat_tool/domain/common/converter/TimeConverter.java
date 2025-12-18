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

    // 1) yyyyMMddHHmmssSSS : 컴팩트 UTC 타임스탬프(밀리초 포함)
    public final DateTimeFormatter UTC_COMPACT_TIMESTAMP_MILLIS =
        DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS").withZone(ZoneOffset.UTC);

    // dd MMM uuuu HH:mm:ss : 헤더/표시용 UTC 날짜시간(월=약어, 초 단위)
    public final DateTimeFormatter UTC_HEADER_DATETIME_ABBR_MONTH =
        DateTimeFormatter.ofPattern("dd MMM uuuu HH:mm:ss").withLocale(Locale.ENGLISH).withZone(ZoneOffset.UTC);

    // dd MMM uuuu HH:mm:ss.SSS : 표시/로그용 UTC 날짜시간(월=약어, 밀리초 포함)
    public final DateTimeFormatter UTC_DATETIME_ABBR_MONTH_MILLIS =
        DateTimeFormatter.ofPattern("dd MMM uuuu HH:mm:ss.SSS").withLocale(Locale.ENGLISH).withZone(ZoneOffset.UTC);

    //LocalDateTime(UTC로 해석) → AbsoluteDate(UTC)
    public AbsoluteDate localDateTimeUtcToAbsoluteDate(LocalDateTime ldtUtc) {
        var utc = TimeScalesFactory.getUTC();
        var instant = ldtUtc.toInstant(ZoneOffset.UTC);      // LocalDateTime(UTC로 간주) -> Instant
        return new AbsoluteDate(Date.from(instant), utc);     // Instant -> Date -> AbsoluteDate
    }

    //AbsoluteDate(UTC) → LocalDateTime(UTC)
    public LocalDateTime absoluteDateToLocalDateTimeUtc(AbsoluteDate date) {
        var utc = TimeScalesFactory.getUTC();
        var instant = date.toDate(utc).toInstant();          // AbsoluteDate -> Date(UTC) -> Instant
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public String toCompactUtcString(AbsoluteDate date) {
        // Orekit -> java.util.Date(UTC) -> Instant
        Instant instant = date.toDate(TimeScalesFactory.getUTC()).toInstant();
        return UTC_COMPACT_TIMESTAMP_MILLIS.format(instant);
    }
}