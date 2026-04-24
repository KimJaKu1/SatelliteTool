package org.example.sattool;

import org.junit.jupiter.api.Test;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.time.AbsoluteDate;
import org.orekit.time.TimeScalesFactory;
import org.sat_tool.SatToolApplication;
import org.sat_tool.domain.event.capture.service.CaptureService;
import org.sat_tool.domain.visuallizse.model.FovParams;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(classes = SatToolApplication.class)
class Temp {

    @Autowired
    private CaptureService captureService;

    // 2) 사각 FOV 정의 (카메라 스펙→반시야각)
    double f = 2.500;       // m
    double pitch = 3.45e-6; // m
    int Wpx = 11664, Hpx = 8750;

    String line1;

    String line2;

    @Test
    void test202251103()
    {
        line1 = "1 40536U 15014A   25307.89866822  .00020269  00000-0  47720-3 0  9997";
        line2 = "2 40536  97.6740 280.1700 0001499 346.9781  13.1426 15.41987886586960";

        TLE tle = new TLE(line1,line2);

        AbsoluteDate t0 = new AbsoluteDate(2025,11,4,0,0,0, TimeScalesFactory.getUTC());
//        AbsoluteDate t1 = t0.shiftedBy(0.0); // 순간 촬영만
        AbsoluteDate t1 = new AbsoluteDate(2025,11,11,0,0,0, TimeScalesFactory.getUTC());

        FovParams fov = new FovParams();
        fov.setFocalLength_m(2.500);   // focal length [m]
        fov.setPixelPitch_m(3.45e-6);  // pixel pitch [m]
        fov.setWpx(11664);             // W px
        fov.setHpx(8750);              // H px

        double rollLimitDeg = 20.0;

        var temp = captureService.computeScheduleWithFootprints(
                line1, line2,
                36.350389, 127.386260, 0,
                t0,t1,
                1,
                fov,
                rollLimitDeg
        );

        var end = 1;
    }
}
