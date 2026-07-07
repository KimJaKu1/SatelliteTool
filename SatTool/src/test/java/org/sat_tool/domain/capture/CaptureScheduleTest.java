package org.sat_tool.domain.capture;

import org.junit.jupiter.api.Test;
import org.orekit.time.AbsoluteDate;
import org.orekit.time.TimeScalesFactory;
import org.sat_tool.SatToolApplication;
import org.sat_tool.domain.capture.model.ImagingOpportunity;
import org.sat_tool.domain.capture.service.CaptureService;
import org.sat_tool.domain.capture.model.FovParams;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = SatToolApplication.class)
class CaptureScheduleTest {

    @Autowired
    private CaptureService captureService;

    String line1 = "1 40536U 15014A   25307.89866822  .00020269  00000-0  47720-3 0  9997";
    String line2 = "2 40536  97.6740 280.1700 0001499 346.9781  13.1426 15.41987886586960";

    double rollLimitDeg = 20.0;

    private static FovParams testFov() {
        FovParams fov = new FovParams();
        fov.setFocalLengthM(2.500);   // focal length [m]
        fov.setPixelPitchM(3.45e-6);  // pixel pitch [m]
        fov.setWpx(11664);            // W px
        fov.setHpx(8750);             // H px
        return fov;
    }

    @Test
    void computesCaptureScheduleOverThreeWeeks() {
        AbsoluteDate t0 = new AbsoluteDate(2025, 11, 4, 0, 0, 0, TimeScalesFactory.getUTC());
        AbsoluteDate t1 = new AbsoluteDate(2025, 11, 25, 0, 0, 0, TimeScalesFactory.getUTC());

        List<ImagingOpportunity> opportunities = captureService.computeScheduleWithFootprints(
                line1, line2,
                36.350389, 127.386260, 0,   // 대전 인근 타깃
                t0, t1,
                1,
                testFov(),
                rollLimitDeg
        );

        // 태양동기 LEO + roll ±20°: 21일이면 재방문 보장 수준
        assertFalse(opportunities.isEmpty(), "no imaging opportunity found in 21-day window");

        for (ImagingOpportunity op : opportunities) {
            assertTrue(op.captureUtc().compareTo(t0) >= 0 && op.captureUtc().compareTo(t1) <= 0,
                    "capture time outside requested window: " + op.captureUtc());
            assertTrue(Math.abs(op.usedRollDegAtCapture()) <= rollLimitDeg + 1e-6,
                    "roll limit exceeded: " + op.usedRollDegAtCapture());
            assertFalse(op.footprintAtCapture().isEmpty(), "footprint polygon is empty");
        }
    }
}
