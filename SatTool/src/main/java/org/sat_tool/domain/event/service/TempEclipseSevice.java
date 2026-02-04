package org.sat_tool.domain.event.service;

import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.hipparchus.ode.events.Action;
import org.orekit.bodies.CelestialBody;
import org.orekit.bodies.CelestialBodyFactory;
import org.orekit.bodies.OneAxisEllipsoid;
import org.orekit.frames.Frame;
import org.orekit.frames.FramesFactory;
import org.orekit.frames.Transform;
import org.orekit.orbits.CartesianOrbit;
import org.orekit.orbits.Orbit;
import org.orekit.propagation.Propagator;
import org.orekit.propagation.SpacecraftState;
import org.orekit.propagation.analytical.Ephemeris;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.propagation.events.EclipseDetector;
import org.orekit.propagation.events.EventDetector;
import org.orekit.propagation.events.handlers.EventHandler;
import org.orekit.time.AbsoluteDate;
import org.orekit.time.TimeScalesFactory;
import org.orekit.utils.*;
import org.sat_tool.domain.common.converter.TimeConverter;
import org.sat_tool.domain.common.model.Satellite;
import org.sat_tool.domain.coordinate.model.EphemerisVector;
import org.sat_tool.domain.event.model.Eclipse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

@Service
@DependsOn("orekitInitializer")
public class TempEclipseSevice {

    @Autowired
    private TimeConverter timeConverter;

    /** 이벤트(경계) 타입 */
    private enum EdgeType { ENTRY, EXIT }

    /** 경계 이벤트 */
    private static class EdgeEvent {
        final AbsoluteDate t;
        final EdgeType type;
        EdgeEvent(AbsoluteDate t, EdgeType type) { this.t = t; this.type = type; }
        AbsoluteDate getTime() { return t; }
    }

    /** 구간 */
    private static class Interval {
        final AbsoluteDate start;
        final AbsoluteDate end;
        Interval(AbsoluteDate start, AbsoluteDate end) { this.start = start; this.end = end; }
    }

    /**
     * @param sat              Satellite(orbitNumber는 "이 구간의 시작 orbit/pass 번호")
     * @param vectorsEcef      ECEF(ITRF) ephemeris vectors (time=UTC LocalDateTime)
     * @param start            탐색 시작
     * @param end              탐색 종료
     * @param subStepSec       구간 내 서브샘플링 간격(권장 0.25s ~ 0.5s; 1초 샘플이면 0.25s 추천)
     * @param rootTolSec       root 정밀화 시간 허용오차(예: 1e-3 = 1ms)
     * @param rootMaxIter      이분법 반복
     */
    public List<Eclipse> computeEclipsesFromEcef_NoPropagator(
            Satellite sat,
            List<EphemerisVector> vectorsEcef,
            AbsoluteDate start,
            AbsoluteDate end,
            double subStepSec,
            double rootTolSec,
            int rootMaxIter
    ) {
        if (vectorsEcef == null || vectorsEcef.size() < 3) return List.of();
        if (subStepSec <= 0) subStepSec = 0.25;

        // 1) 시간 정렬
        List<EphemerisVector> v = new ArrayList<>(vectorsEcef);
        v.sort(Comparator.comparing(EphemerisVector::getTime));

        // 2) 프레임/모델
        final Frame itrf = FramesFactory.getITRF(IERSConventions.IERS_2010, true);
        final Frame inertial = FramesFactory.getEME2000();
        final OneAxisEllipsoid earth = new OneAxisEllipsoid(
                Constants.WGS84_EARTH_EQUATORIAL_RADIUS,
                Constants.WGS84_EARTH_FLATTENING,
                itrf
        );
        final CelestialBody sun = CelestialBodyFactory.getSun();

        // 3) Detector (Propagator에 붙이지 않음)
        final EclipseDetector penDet = new EclipseDetector(sun, Constants.SUN_RADIUS, earth).withPenumbra();
        final EclipseDetector umbDet = new EclipseDetector(sun, Constants.SUN_RADIUS, earth).withUmbra();

        // 4) 샘플을 AbsoluteDate + inertial PV로 변환해 배열로 준비
        final int n = v.size();
        final AbsoluteDate[] tAbs = new AbsoluteDate[n];
        final TimeStampedPVCoordinates[] pvInert = new TimeStampedPVCoordinates[n];

        for (int i = 0; i < n; i++) {
            AbsoluteDate t = timeConverter.localDateTimeUtcToAbsoluteDate(v.get(i).getTime());
            tAbs[i] = t;

            PVCoordinates pvEcef = new PVCoordinates(v.get(i).getPos(), v.get(i).getVel());
            Transform tr = itrf.getTransformTo(inertial, t);
            PVCoordinates pvI = tr.transformPVCoordinates(pvEcef);

            pvInert[i] = new TimeStampedPVCoordinates(t, pvI.getPosition(), pvI.getVelocity(), Vector3D.ZERO);
        }

        // 5) start/end가 샘플 범위 안에 있도록 클램프
        AbsoluteDate minT = tAbs[0];
        AbsoluteDate maxT = tAbs[n - 1];
        AbsoluteDate tStart = (start.compareTo(minT) < 0) ? minT : start;
        AbsoluteDate tEnd   = (end.compareTo(maxT) > 0) ? maxT : end;
        if (tStart.compareTo(tEnd) >= 0) return List.of();

        // 6) penumbra/umbra 각각 경계 이벤트 수집
        List<EdgeEvent> penEdges = collectEdgesNoProp(
                penDet, tAbs, pvInert, tStart, tEnd, subStepSec, rootTolSec, rootMaxIter, inertial
        );
        List<EdgeEvent> umbEdges = collectEdgesNoProp(
                umbDet, tAbs, pvInert, tStart, tEnd, subStepSec, rootTolSec, rootMaxIter, inertial
        );

        // 7) 경계 이벤트 → 구간(Interval)
        List<Interval> penIntervals = buildIntervalsFromEdges(
                penDet, tStart, tEnd, pvAtTimeNoProp(tStart, tAbs, pvInert), inertial, penEdges
        );
        List<Interval> umbIntervals = buildIntervalsFromEdges(
                umbDet, tStart, tEnd, pvAtTimeNoProp(tStart, tAbs, pvInert), inertial, umbEdges
        );

        // 8) Penumbra 구간에 Umbra를 채워 Eclipse 생성
        long baseOrbit = (sat.getOrbitNumber() != null) ? sat.getOrbitNumber() : 0L;
        List<Eclipse> out = new ArrayList<>();

        int j = 0;
        for (Interval pen : penIntervals) {

            while (j < umbIntervals.size() && umbIntervals.get(j).end.compareTo(pen.start) <= 0) j++;

            AbsoluteDate umbEntry = null;
            AbsoluteDate umbExit  = null;

            int k = j;
            while (k < umbIntervals.size() && umbIntervals.get(k).start.compareTo(pen.end) < 0) {
                Interval u = umbIntervals.get(k);
                AbsoluteDate s = (u.start.compareTo(pen.start) < 0) ? pen.start : u.start;
                AbsoluteDate e = (u.end.compareTo(pen.end) > 0) ? pen.end : u.end;
                if (s.compareTo(e) < 0) {
                    if (umbEntry == null || s.compareTo(umbEntry) < 0) umbEntry = s;
                    if (umbExit  == null || e.compareTo(umbExit)  > 0) umbExit  = e;
                }
                k++;
            }

            Eclipse ec = new Eclipse();
            ec.setOrbitNumber(orbitAtByPeriodFallback(baseOrbit, tStart, pen.start, 5400.0));
            ec.setPenumbraEntry(pen.start);
            ec.setPenumbraExit(pen.end);
            ec.setUmbraEntry(umbEntry);
            ec.setUmbraExit(umbExit);

            out.add(ec);
        }

        return out;
    }

    private long orbitAtByPeriodFallback(long baseOrbit, AbsoluteDate baseDate, AbsoluteDate t, double periodSec) {
        double dt = t.durationFrom(baseDate);
        long inc = (long) Math.floor(dt / periodSec);
        return baseOrbit + inc;
    }

    // =========================================================
    // Edge collection (no propagator)
    // =========================================================
    private List<EdgeEvent> collectEdgesNoProp(
            EclipseDetector det,
            AbsoluteDate[] tAbs,
            TimeStampedPVCoordinates[] pvInert,
            AbsoluteDate start,
            AbsoluteDate end,
            double subStepSec,
            double rootTolSec,
            int rootMaxIter,
            Frame inertial
    ) {
        List<EdgeEvent> edges = new ArrayList<>();

        int i0 = lowerBound(tAbs, start);
        if (i0 > 0) i0--;
        int i1 = upperBound(tAbs, end);
        if (i1 < tAbs.length - 1) i1++;

        final double dedupSec = Math.max(rootTolSec * 5.0, 1e-3);
        final double classifyEps = Math.max(rootTolSec * 2.0, 1e-3);

        for (int i = Math.max(1, i0 + 1); i <= Math.min(tAbs.length - 1, i1); i++) {

            AbsoluteDate a = tAbs[i - 1];
            AbsoluteDate b = tAbs[i];

            if (b.compareTo(start) <= 0) continue;
            if (a.compareTo(end) >= 0) break;

            TimeStampedPVCoordinates p0 = pvInert[i - 1];
            TimeStampedPVCoordinates p1 = pvInert[i];

            AbsoluteDate segStart = (a.compareTo(start) < 0) ? start : a;
            AbsoluteDate segEnd   = (b.compareTo(end)   > 0) ? end   : b;

            double dtSeg = segEnd.durationFrom(segStart);
            if (dtSeg <= 0) continue;

            // ✅ 성능 개선: 입력 샘플 간격보다 과도한 subStep 분할 방지
            double baseSampleStep = Math.max(1e-9, b.durationFrom(a));
            double effStep = Math.min(subStepSec, baseSampleStep);   // 과분할 방지
            int m = (int) Math.ceil(dtSeg / effStep);
            m = Math.max(m, 1);

            AbsoluteDate prevT = segStart;
            double prevG = gAt(det, prevT, pvInterpolate(prevT, p0, p1), inertial);

            for (int s = 1; s <= m; s++) {
                double frac = (double) s / (double) m;
                AbsoluteDate curT = segStart.shiftedBy(dtSeg * frac);
                double curG = gAt(det, curT, pvInterpolate(curT, p0, p1), inertial);

                // 부호변화(=경계 통과) 감지
                if (prevG == 0.0 || curG == 0.0 || prevG * curG < 0.0) {

                    // ✅ root 정밀화 (브라켓 불완전 시 fallback 포함)
                    AbsoluteDate tRoot = refineRootBisectionHermite(
                            det, prevT, curT, p0, p1, rootTolSec, rootMaxIter, inertial
                    );

                    // ✅ ENTRY/EXIT 판정 보강: tRoot 주변 부호로 결정
                    EdgeType type = classifyEdge(det, tRoot, p0, p1, classifyEps, inertial);

                    // dedup
                    if (edges.isEmpty()) {
                        edges.add(new EdgeEvent(tRoot, type));
                    } else {
                        EdgeEvent last = edges.get(edges.size() - 1);
                        if (Math.abs(tRoot.durationFrom(last.t)) > dedupSec) {
                            edges.add(new EdgeEvent(tRoot, type));
                        } else {
                            // 너무 가까우면 최신으로 덮어쓰기(노이즈 감소)
                            edges.set(edges.size() - 1, new EdgeEvent(tRoot, type));
                        }
                    }
                }

                prevT = curT;
                prevG = curG;
            }
        }

        edges.sort(Comparator.comparing(EdgeEvent::getTime));
        edges = sanitizeEdges(edges, start, end, Math.max(rootTolSec * 2.0, 1e-3));
        return edges;
    }

    // =========================================================
    // Build intervals from edges + initial-in-shadow handling
    // =========================================================
    private List<Interval> buildIntervalsFromEdges(
            EclipseDetector det,
            AbsoluteDate start,
            AbsoluteDate end,
            PVCoordinates pvAtStart,
            Frame inertial,
            List<EdgeEvent> edges
    ) {
        List<Interval> intervals = new ArrayList<>();

        boolean inShadow = gAt(det, start, pvAtStart, inertial) < 0.0;
        AbsoluteDate curEntry = inShadow ? start : null;

        for (EdgeEvent e : edges) {
            if (e.t.compareTo(start) < 0) continue;
            if (e.t.compareTo(end) > 0) break;

            if (e.type == EdgeType.ENTRY) {
                if (!inShadow) {
                    inShadow = true;
                    curEntry = e.t;
                }
            } else { // EXIT
                if (inShadow) {
                    inShadow = false;
                    AbsoluteDate exit = e.t;
                    if (curEntry != null && curEntry.compareTo(exit) < 0) {
                        intervals.add(new Interval(curEntry, exit));
                    }
                    curEntry = null;
                }
            }
        }

        if (inShadow && curEntry != null && curEntry.compareTo(end) < 0) {
            intervals.add(new Interval(curEntry, end));
        }

        intervals.sort(Comparator.comparing(x -> x.start));
        return intervals;
    }

    // =========================================================
    // g evaluation
    // =========================================================
    private double gAt(EclipseDetector det, AbsoluteDate t, PVCoordinates pv, Frame inertial) {
        Orbit o = new CartesianOrbit(pv, inertial, t, Constants.EGM96_EARTH_MU);
        SpacecraftState s = new SpacecraftState(o);
        return det.g(s);
    }

    // =========================================================
    // Hermite PV interpolation between two samples (custom)
    // =========================================================
    private PVCoordinates pvInterpolate(AbsoluteDate t, TimeStampedPVCoordinates p0, TimeStampedPVCoordinates p1) {

        final AbsoluteDate t0 = p0.getDate();
        final AbsoluteDate t1 = p1.getDate();
        final double h = t1.durationFrom(t0);     // seconds
        if (h <= 0) return p0;                    // 방어 (중복/역순)

        // t를 [t0, t1]로 클램프
        final double dt = Math.max(0.0, Math.min(h, t.durationFrom(t0)));
        final double u  = dt / h;

        final double u2 = u * u;
        final double u3 = u2 * u;

        // Hermite basis (position)
        final double h00 =  2*u3 - 3*u2 + 1;
        final double h10 =      u3 - 2*u2 + u;
        final double h01 = -2*u3 + 3*u2;
        final double h11 =      u3 -    u2;

        final Vector3D r0 = p0.getPosition();
        final Vector3D v0 = p0.getVelocity();
        final Vector3D r1 = p1.getPosition();
        final Vector3D v1 = p1.getVelocity();

        final Vector3D r = new Vector3D(
                h00, r0,
                h10 * h, v0,
                h01, r1,
                h11 * h, v1
        );

        // derivative wrt time (velocity)
        final double dh00 = (6*u2 - 6*u) / h;
        final double dh10 = (3*u2 - 4*u + 1);    // (d/du h10)*h*(du/dt)=d/du h10
        final double dh01 = (-6*u2 + 6*u) / h;
        final double dh11 = (3*u2 - 2*u);        // 동일 논리

        final Vector3D v = new Vector3D(
                dh00, r0,
                dh10, v0,
                dh01, r1,
                dh11, v1
        );

        return new PVCoordinates(r, v);
    }

    private PVCoordinates pvAtTimeNoProp(AbsoluteDate t, AbsoluteDate[] tAbs, TimeStampedPVCoordinates[] pvInert) {
        int idx = lowerBound(tAbs, t);
        if (idx <= 0) return pvInert[0];
        if (idx >= tAbs.length) return pvInert[tAbs.length - 1];
        return pvInterpolate(t, pvInert[idx - 1], pvInert[idx]);
    }

    // =========================================================
    // Root refinement (bisection) inside [ta, tb] + fallback
    // =========================================================
    private AbsoluteDate refineRootBisectionHermite(
            EclipseDetector det,
            AbsoluteDate ta,
            AbsoluteDate tb,
            TimeStampedPVCoordinates p0,
            TimeStampedPVCoordinates p1,
            double tolSec,
            int maxIter,
            Frame inertial
    ) {
        AbsoluteDate a = ta;
        AbsoluteDate b = tb;

        double ga = gAt(det, a, pvInterpolate(a, p0, p1), inertial);
        double gb = gAt(det, b, pvInterpolate(b, p0, p1), inertial);

        // ✅ 브라켓이 확실하지 않으면 fallback(선형 근사 or |g| 작은 쪽)
        if (ga != 0.0 && gb != 0.0 && ga * gb > 0.0) {
            double denom = (gb - ga);
            if (Math.abs(denom) < 1e-15) {
                return (Math.abs(ga) <= Math.abs(gb)) ? a : b;
            }
            double tau = (-ga) / denom; // 선형 근사
            tau = Math.max(0.0, Math.min(1.0, tau));
            return a.shiftedBy(tau * b.durationFrom(a));
        }

        for (int k = 0; k < maxIter; k++) {
            double dt = b.durationFrom(a);
            if (Math.abs(dt) <= tolSec) break;

            AbsoluteDate m = a.shiftedBy(0.5 * dt);
            double gm = gAt(det, m, pvInterpolate(m, p0, p1), inertial);

            if (ga == 0.0) return a;
            if (gb == 0.0) return b;

            if (ga * gm <= 0.0) {
                b = m;
                gb = gm;
            } else {
                a = m;
                ga = gm;
            }
        }

        return a.shiftedBy(0.5 * b.durationFrom(a));
    }

    // =========================================================
    // ENTRY/EXIT classification using g(tRoot -/+ eps)
    // =========================================================
    private EdgeType classifyEdge(
            EclipseDetector det,
            AbsoluteDate tRoot,
            TimeStampedPVCoordinates p0,
            TimeStampedPVCoordinates p1,
            double epsSec,
            Frame inertial
    ) {
        AbsoluteDate tMin = p0.getDate();
        AbsoluteDate tMax = p1.getDate();

        AbsoluteDate tBefore = clampDate(tRoot.shiftedBy(-epsSec), tMin, tMax);
        AbsoluteDate tAfter  = clampDate(tRoot.shiftedBy(+epsSec), tMin, tMax);

        double gBefore = gAt(det, tBefore, pvInterpolate(tBefore, p0, p1), inertial);
        double gAfter  = gAt(det, tAfter,  pvInterpolate(tAfter,  p0, p1), inertial);

        // + -> - : ENTRY, - -> + : EXIT
        if (gBefore > 0.0 && gAfter < 0.0) return EdgeType.ENTRY;
        if (gBefore < 0.0 && gAfter > 0.0) return EdgeType.EXIT;

        // 애매하면 prev/after 크기 비교로 fallback (드문 케이스)
        return (gBefore > gAfter) ? EdgeType.ENTRY : EdgeType.EXIT;
    }

    private AbsoluteDate clampDate(AbsoluteDate t, AbsoluteDate min, AbsoluteDate max) {
        if (t.compareTo(min) < 0) return min;
        if (t.compareTo(max) > 0) return max;
        return t;
    }

    // =========================================================
    // Edge post-processing: remove consecutive duplicates, out-of-range
    // =========================================================
    private List<EdgeEvent> sanitizeEdges(List<EdgeEvent> edges, AbsoluteDate start, AbsoluteDate end, double minGapSec) {
        if (edges.isEmpty()) return edges;

        List<EdgeEvent> out = new ArrayList<>();
        for (EdgeEvent e : edges) {
            if (e.t.compareTo(start) < 0 || e.t.compareTo(end) > 0) continue;

            if (out.isEmpty()) {
                out.add(e);
                continue;
            }

            EdgeEvent last = out.get(out.size() - 1);

            // 너무 가까우면 하나로 통합(최신으로)
            if (Math.abs(e.t.durationFrom(last.t)) < minGapSec) {
                out.set(out.size() - 1, e);
                continue;
            }

            // 같은 타입 연속이면(노이즈) 최신으로 교체
            if (last.type == e.type) {
                out.set(out.size() - 1, e);
                continue;
            }

            out.add(e);
        }
        return out;
    }

    // =========================================================
    // Binary search helpers
    // =========================================================
    private int lowerBound(AbsoluteDate[] arr, AbsoluteDate x) {
        int lo = 0, hi = arr.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (arr[mid].compareTo(x) < 0) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    private int upperBound(AbsoluteDate[] arr, AbsoluteDate x) {
        int lo = 0, hi = arr.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (arr[mid].compareTo(x) <= 0) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }
}