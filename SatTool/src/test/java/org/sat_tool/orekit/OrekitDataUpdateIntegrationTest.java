package org.sat_tool.orekit;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.sat_tool.SatToolApplication;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * update.sh를 실제로 실행해 USNO/IERS 등에서 데이터를 내려받는 네트워크 의존 테스트.
 * 오프라인/CI 환경에서 실패하므로 기본 스위트에서 제외 — 수동 실행 시 @Disabled 제거.
 */
@Disabled("network + local bash required — run manually")
@Tag("integration")
@SpringBootTest(classes = SatToolApplication.class)
public class OrekitDataUpdateIntegrationTest {

    @Autowired private OrekitDataUpdateService dataUpdateService;

    @Test
    void runOrekitUpdate() throws Exception {
        String out = dataUpdateService.runUpdateSh(Duration.ofMinutes(2));

        assertNotNull(out, "update.sh output");
        assertFalse(out.isBlank(), "update.sh produced no output");
    }
}
