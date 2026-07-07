package org.sat_tool.domain.common;

import java.io.File;

import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.orekit.data.DataContext;
import org.orekit.data.DirectoryCrawler;
import org.orekit.time.AbsoluteDate;
import org.orekit.time.TimeScalesFactory;
import org.sat_tool.domain.common.helper.HermiteEventUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HermiteEventUtilsTest {

    @BeforeAll
    static void initOrekitData() {
        var manager = DataContext.getDefault().getDataProvidersManager();
        if (manager.getProviders().isEmpty()) {
            manager.addProvider(new DirectoryCrawler(new File("src/main/resources/orekit-data")));
        }
    }

    private static AbsoluteDate t0() {
        return new AbsoluteDate(2025, 11, 4, 0, 0, 0, TimeScalesFactory.getUTC());
    }

    @Test
    void hermiteInterpolationIsExactForLinearMotion() {
        // 등속 직선 운동: r(t) = (t, 0, 0), v = (1, 0, 0)
        Vector3D r0 = Vector3D.ZERO;
        Vector3D v = Vector3D.PLUS_I;
        Vector3D r1 = new Vector3D(10, 0, 0);

        HermiteEventUtils.PV mid = HermiteEventUtils.hermitePV(r0, v, r1, v, 10.0, 0.5);

        assertEquals(5.0, mid.pos().getX(), 1e-12, "linear motion midpoint position");
        assertEquals(0.0, mid.pos().getY(), 1e-12);
        assertEquals(1.0, mid.vel().getX(), 1e-12, "linear motion velocity is constant");
    }

    @Test
    void bisectionFindsRootOfLinearCrossing() {
        // f(t) = x(t), x는 0→10으로 등속 증가, target 5 → 근은 t0+5s
        AbsoluteDate start = t0();
        AbsoluteDate end = start.shiftedBy(10.0);
        Vector3D v = Vector3D.PLUS_I;

        AbsoluteDate root = HermiteEventUtils.refineRootTimeHermiteBisection(
                start, Vector3D.ZERO, v,
                end, new Vector3D(10, 0, 0), v,
                (t, pos, vel) -> pos.getX(),
                5.0,
                1e-6, 80);

        assertEquals(5.0, root.durationFrom(start), 1e-5, "root of linear crossing");
    }

    @Test
    void bisectionFallsBackToLinearEstimateWithoutBracket(){
        // 구간 [0,10]에서 f: 1→11 (target 0을 끼지 않음) → 문서화된 대로 선형 근사값을 반환
        AbsoluteDate start = t0();
        AbsoluteDate end = start.shiftedBy(10.0);
        Vector3D v = Vector3D.PLUS_I;

        AbsoluteDate estimated = HermiteEventUtils.refineRootTimeHermiteBisection(
                start, new Vector3D(1, 0, 0), v,
                end, new Vector3D(11, 0, 0), v,
                (t, pos, vel) -> pos.getX(),
                0.0,
                1e-6, 80);

        // 선형 근사 tau = (0-1)/(11-1) = -0.1 → clamp 0 → t0 반환
        assertEquals(0.0, estimated.durationFrom(start), 1e-9,
                "bracketing 실패 시 clamp된 선형 근사(구간 시작)를 반환해야 한다");
    }

    @Test
    void lowerAndUpperBoundBinarySearch() {
        AbsoluteDate base = t0();
        AbsoluteDate[] arr = {
                base, base.shiftedBy(10), base.shiftedBy(20), base.shiftedBy(30)
        };

        assertEquals(1, HermiteEventUtils.lowerBound(arr, base.shiftedBy(5)), "first idx with t >= x");
        assertEquals(1, HermiteEventUtils.lowerBound(arr, base.shiftedBy(10)));
        assertEquals(2, HermiteEventUtils.upperBound(arr, base.shiftedBy(10)), "first idx with t > x");
        assertEquals(0, HermiteEventUtils.lowerBound(arr, base.shiftedBy(-1)));
        assertEquals(4, HermiteEventUtils.upperBound(arr, base.shiftedBy(99)));
    }
}
