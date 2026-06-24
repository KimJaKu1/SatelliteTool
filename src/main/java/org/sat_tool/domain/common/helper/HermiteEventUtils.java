package org.sat_tool.domain.common.helper;

import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.orekit.time.AbsoluteDate;

public final class HermiteEventUtils {

    private HermiteEventUtils() {}

    /** 임의 시각의 스칼라 값을 계산하는 함수 인터페이스 */
    @FunctionalInterface
    public interface ScalarFunction {
        /**
         * @param t   평가 시각
         * @param pos 보간된 위치
         * @param vel 보간된 속도
         * @return 스칼라 값(예: 위도, 고도, elevation, range, doppler ...)
         */
        double value(AbsoluteDate t, Vector3D pos, Vector3D vel);
    }

    /** 보간 결과(P,V) */
    public record PV(Vector3D pos, Vector3D vel) {}

    // =========================================================
    // 1) Hermite 보간 (pos/vel)
    // =========================================================

    /**
     * 3차 Hermite로 pos/vel 보간.
     * tau: [0,1] (0이면 (t0,r0,v0), 1이면 (t1,r1,v1))
     */
    public static PV hermitePV(
            Vector3D r0, Vector3D v0,
            Vector3D r1, Vector3D v1,
            double dtSeconds,
            double tau01
    ) {
        if (dtSeconds <= 0) {
            return new PV(r0, v0);
        }

        double s = clamp(tau01, 0.0, 1.0);
        double s2 = s * s;
        double s3 = s2 * s;

        // Hermite basis for position
        double h00 =  2*s3 - 3*s2 + 1;
        double h10 =      s3 - 2*s2 + s;
        double h01 = -2*s3 + 3*s2;
        double h11 =      s3 -   s2;

        Vector3D pos = r0.scalarMultiply(h00)
                .add(v0.scalarMultiply(h10 * dtSeconds))
                .add(r1.scalarMultiply(h01))
                .add(v1.scalarMultiply(h11 * dtSeconds));

        // Derivatives w.r.t s
        double dh00 = 6*s2 - 6*s;
        double dh10 = 3*s2 - 4*s + 1;
        double dh01 = -dh00;
        double dh11 = 3*s2 - 2*s;

        Vector3D drds = r0.scalarMultiply(dh00)
                .add(v0.scalarMultiply(dh10 * dtSeconds))
                .add(r1.scalarMultiply(dh01))
                .add(v1.scalarMultiply(dh11 * dtSeconds));

        // dr/dt = (dr/ds) / dt
        Vector3D vel = drds.scalarMultiply(1.0 / dtSeconds);

        return new PV(pos, vel);
    }

    // =========================================================
    // 2) Root finding: f(t) = target (bisection)
    // =========================================================

    /**
     * [t0, t1] 구간에서 f(t)=target의 해를 찾는다.
     * - endpoints가 target을 “끼고(부호 반대)” 있어야 bisection이 보장됨
     * - 아니면 fallback으로 선형 근사(타겟 비율) 후 반환
     *
     * @param tolSeconds 결과 시간 오차 허용(초) (예: 1e-3 ~ 1e-2)
     * @param maxIter    반복 횟수 (예: 50~80)
     */
    public static AbsoluteDate refineRootTimeHermiteBisection(
            AbsoluteDate t0, Vector3D r0, Vector3D v0,
            AbsoluteDate t1, Vector3D r1, Vector3D v1,
            ScalarFunction f,
            double target,
            double tolSeconds,
            int maxIter
    ) {
        double dt = t1.durationFrom(t0);
        if (dt <= 0) return t0;

        // endpoint 값(타겟 보정)
        double g0 = f.value(t0, r0, v0) - target;
        double g1 = f.value(t1, r1, v1) - target;

        // 1) bracket 보장 안 되면 fallback(선형 비율)
        if (!hasOppositeSign(g0, g1) && g0 != 0.0 && g1 != 0.0) {
            double denom = (g1 - g0);
            if (Math.abs(denom) < 1e-15) return t0;
            double tau = (-g0) / denom; // 선형 근사
            tau = clamp(tau, 0.0, 1.0);
            return t0.shiftedBy(tau * dt);
        }
        if (g0 == 0.0) return t0;
        if (g1 == 0.0) return t1;

        double lo = 0.0, hi = 1.0;
        double fLo = g0, fHi = g1;

        for (int k = 0; k < maxIter; k++) {
            double mid = 0.5 * (lo + hi);
            AbsoluteDate tm = t0.shiftedBy(mid * dt);

            PV pv = hermitePV(r0, v0, r1, v1, dt, mid);
            double fMid = f.value(tm, pv.pos, pv.vel) - target;

            double spanSec = (hi - lo) * dt;
            if (spanSec < tolSeconds) {
                return tm;
            }

            // 부호 기준으로 bracket 축소
            if (hasOppositeSign(fLo, fMid) || fMid == 0.0) {
                hi = mid;
                fHi = fMid;
            } else {
                lo = mid;
                fLo = fMid;
            }
        }

        return t0.shiftedBy(0.5 * (lo + hi) * dt);
    }

    // =========================================================
    // internal helpers
    // =========================================================

    private static boolean hasOppositeSign(double a, double b) {
        return (a > 0 && b < 0) || (a < 0 && b > 0);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}

