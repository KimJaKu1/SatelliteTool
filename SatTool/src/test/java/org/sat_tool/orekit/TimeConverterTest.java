package org.sat_tool.orekit;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.orekit.data.DataContext;
import org.orekit.data.DirectoryCrawler;
import org.orekit.time.AbsoluteDate;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TimeConverterTest {

    /** Spring 없이 Orekit 데이터만 직접 로드 (UTC 타임스케일 필요) */
    @BeforeAll
    static void initOrekitData() {
        var manager = DataContext.getDefault().getDataProvidersManager();
        if (manager.getProviders().isEmpty()) {
            manager.addProvider(new DirectoryCrawler(new File("src/main/resources/orekit-data")));
        }
    }

    @Test
    void localDateTimeAbsoluteDateRoundTripPreservesMillis() {
        LocalDateTime original = LocalDateTime.of(2025, 11, 4, 6, 9, 44, 123_000_000);

        AbsoluteDate absolute = TimeConverter.localDateTimeUtcToAbsoluteDate(original);
        LocalDateTime roundTrip = TimeConverter.absoluteDateToLocalDateTimeUtc(absolute);

        assertEquals(original, roundTrip, "ms-precision round trip must be lossless");
    }

    @Test
    void stringRoundTripWithStandardFormat() {
        String text = "2025-11-04 06:09:44.123";

        LocalDateTime parsed = TimeConverter.stringToLocalDateTime(text);
        assertEquals(text, TimeConverter.localDateTimeToString(parsed));
    }

    @Test
    void compactUtcStringRoundTrip() {
        AbsoluteDate date = TimeConverter.localDateTimeUtcToAbsoluteDate(
                LocalDateTime.of(2025, 11, 4, 6, 9, 44, 123_000_000));

        String compact = TimeConverter.toUtcCompactMs(date);
        assertEquals("20251104060944123", compact);
        assertEquals(0.0, TimeConverter.fromUtcCompactMs(compact).durationFrom(date), 1e-9);
    }

    @Test
    void nullInputsReturnNull() {
        assertNull(TimeConverter.localDateTimeUtcToAbsoluteDate(null));
        assertNull(TimeConverter.absoluteDateToLocalDateTimeUtc(null));
        assertNull(TimeConverter.stringToLocalDateTime(null));
        assertNull(TimeConverter.stringToLocalDateTime("   "));
        assertNull(TimeConverter.toUtcCompactMs(null));
        assertNull(TimeConverter.fromUtcCompactMs(null));
    }

    @Test
    void malformedStringsThrowWithContext() {
        assertThrows(IllegalArgumentException.class,
                () -> TimeConverter.stringToLocalDateTime("2025/11/04 06:09"));
        assertThrows(IllegalArgumentException.class,
                () -> TimeConverter.fromUtcCompactMs("not-a-timestamp"));
    }
}
