package org.sat_tool.domain.common;

import org.junit.jupiter.api.Test;
import org.sat_tool.domain.common.model.ReportKey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ReportKeyTest {

    @Test
    void formatAndParseRoundTrip() {
        ReportKey key = new ReportKey("SAT-1", "Daejeon", 5);

        assertEquals("SAT-1|Daejeon|5", key.format());
        assertEquals(key, ReportKey.parse(key.format()));
    }

    @Test
    void parseReturnsNullOnMalformedInput() {
        assertNull(ReportKey.parse(null));
        assertNull(ReportKey.parse(""));
        assertNull(ReportKey.parse("no-separator"));
        assertNull(ReportKey.parse("only|one"));
        assertNull(ReportKey.parse("sat|station|not-a-number"));
    }

    @Test
    void stationNameMayContainSeparator() {
        // 첫 구분자와 마지막 구분자로 자르므로 지상국명 내부의 '|'도 허용된다
        ReportKey parsed = ReportKey.parse("SAT|ST|A|10");

        assertEquals("SAT", parsed.sat());
        assertEquals("ST|A", parsed.station());
        assertEquals(10, parsed.mask());
    }
}
