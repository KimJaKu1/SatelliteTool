package org.sat_tool.domain.capture;

import org.junit.jupiter.api.Test;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.propagation.analytical.tle.TLEPropagator;
import org.orekit.time.AbsoluteDate;
import org.sat_tool.SatToolApplication;
import org.sat_tool.orekit.TimeConverter;
import org.sat_tool.domain.coordinate.model.EphemerisVector;
import org.sat_tool.domain.capture.model.ImagingOpportunity;
import org.sat_tool.domain.capture.service.CaptureService;
import org.sat_tool.domain.propagation.service.EphemerisService;
import org.sat_tool.domain.capture.model.FovParams;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = SatToolApplication.class)
public class CaptureFootprintTest {

    @Autowired private CaptureService captureService;
    @Autowired private EphemerisService ephemerisService;

    String line1 = "1 40536U 15014A   25307.89866822  .00020269  00000-0  47720-3 0  9997";
    String line2 = "2 40536  97.6740 280.1700 0001499 346.9781  13.1426 15.41987886586960";

    // 기존 24일 × 1초(약 207만 샘플, -Xmx12g 요구 원인)에서, 실제 촬영 기회(11/14)를 포함하는 2일로 축소
    LocalDateTime startTime = LocalDateTime.of(2025, 11, 13, 0, 0, 0);
    LocalDateTime endTime = LocalDateTime.of(2025, 11, 15, 0, 0, 0);

    double rollLimitDeg = 20.0;

    private static FovParams testFov() {
        FovParams fov = new FovParams();
        fov.setFocalLengthM(2.500);   // focal length [m]
        fov.setPixelPitchM(3.45e-6);  // pixel pitch [m]
        fov.setWpx(11664);            // W px
        fov.setHpx(8750);             // H px
        return fov;
    }

    /**
     * 전파기 기반 계산과 사전 생성 ECEF ephemeris 기반 계산이
     * 같은 입력·같은 구간에서 동일한 촬영 기회를 산출하는지 회귀 검증.
     */
    @Test
    void propagatorAndEcefPathsAgree() {
        AbsoluteDate t0 = TimeConverter.localDateTimeUtcToAbsoluteDate(startTime);
        AbsoluteDate t1 = TimeConverter.localDateTimeUtcToAbsoluteDate(endTime);
        TLE tle = new TLE(line1, line2);
        FovParams fov = testFov();

        List<ImagingOpportunity> fromPropagator = captureService.computeScheduleWithFootprints(
                line1, line2,
                36.350389, 127.386260, 0,
                t0, t1,
                1,
                fov,
                rollLimitDeg
        );

        TLEPropagator propagator = TLEPropagator.selectExtrapolator(tle);
        List<EphemerisVector> ephemerisVectors =
                ephemerisService.computeEphemerisECEF(propagator, t0, t1, 1);

        List<ImagingOpportunity> fromEcef = captureService.computeScheduleWithFootprintsFromEcef_NoPropagator(
                ephemerisVectors,
                36.350389, 127.386260, 0,
                t0, t1,
                1,
                fov,
                rollLimitDeg
        );

        assertEquals(fromPropagator.size(), fromEcef.size(),
                "propagator-based and ECEF-based paths disagree on opportunity count");
        assertFalse(fromPropagator.isEmpty(),
                "window is known to contain an opportunity (2025-11-14) — empty result is a regression");

        for (ImagingOpportunity op : fromEcef) {
            assertTrue(op.captureUtc().compareTo(t0) >= 0 && op.captureUtc().compareTo(t1) <= 0,
                    "capture time outside window: " + op.captureUtc());
            assertTrue(Math.abs(op.usedRollDegAtCapture()) <= rollLimitDeg + 1e-6,
                    "roll limit exceeded: " + op.usedRollDegAtCapture());
            assertFalse(op.footprintAtCapture().isEmpty(), "footprint polygon is empty");
        }
    }
}
