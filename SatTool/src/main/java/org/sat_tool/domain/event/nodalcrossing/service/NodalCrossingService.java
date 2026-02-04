package org.sat_tool.domain.event.nodalcrossing.service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.orekit.bodies.GeodeticPoint;
import org.orekit.bodies.OneAxisEllipsoid;
import org.orekit.frames.Frame;
import org.orekit.frames.FramesFactory;
import org.orekit.frames.Transform;
import org.orekit.time.AbsoluteDate;
import org.orekit.utils.*;
import org.sat_tool.domain.common.converter.TimeConverter;
import org.sat_tool.domain.common.helper.HermiteEventUtils;
import org.sat_tool.domain.common.model.Satellite;
import org.sat_tool.domain.coordinate.model.EphemerisVector;
import org.sat_tool.domain.event.nodalcrossing._enum.NodeType;
import org.sat_tool.domain.event.nodalcrossing.model.NodalCrossing;
import org.sat_tool.domain.event.nodalcrossing.model.NodeEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;


@Service
@DependsOn("orekitInitializer")
public class NodalCrossingService {

    @Autowired private TimeConverter timeConverter;

    private final ConcurrentMap<Path, ReentrantLock> fileLock = new ConcurrentHashMap<>();


    /**
     * ECEF(ITRF) ephemeris(1초 샘플 등)만으로 NodalCrossing 리스트 생성
     * - Pass 정의: [ASC_i, ASC_{i+1}]
     * - DESC_i: (ASC_i, ASC_{i+1}) 내부의 z=0 교차(Descending)
     * - maxLat: [ASC_i, DESC_i] , minLat: [DESC_i, ASC_{i+1}]
     * - 분석 구간 [start,end] 밖의 이벤트는 null로 저장 → 출력에서 "Not in Pass"
     *
     * 개선:
     *  - 노드 탐지(적도면 교차)는 ICRF에서 z=0 기준 (STK와 더 일치)
     *  - ASC/DESC 분류는 vz 의존 대신 z 부호 변화 방향으로 판정(velocity 정의 흔들림 방지)
     *  - 마지막 pass에서 다음 ASC가 window 밖이면 minLat는 계산하지 않고 Not in Pass(null)
     */
    public List<NodalCrossing> computeNodalCrossingsFromEcef_NoPropagator(
            Satellite sat,
            List<EphemerisVector> vectorsEcef,
            AbsoluteDate start,
            AbsoluteDate end,
            double subStepSec,
            double rootTolSec,
            int rootMaxIter
    ) {
        if (vectorsEcef == null || vectorsEcef.size() < 3) return List.of();
        if (subStepSec <= 0) subStepSec = 1.0;   // 1초 샘플이면 1로 둬도 충분
        if (rootTolSec <= 0) rootTolSec = 1e-3;
        if (rootMaxIter <= 0) rootMaxIter = 60;

        // 1) 시간 정렬
        List<EphemerisVector> v = new ArrayList<>(vectorsEcef);
        v.sort(Comparator.comparing(EphemerisVector::getTime));

        // 2) 프레임/모델
        final Frame itrf = FramesFactory.getITRF(IERSConventions.IERS_2010, true);

        // ★ STK와 맞추기: 노드 탐지용 관성계는 ICRF 권장
        final Frame inertial = FramesFactory.getGCRF(); // <-- 변경 포인트

        final OneAxisEllipsoid earth = new OneAxisEllipsoid(
                Constants.WGS84_EARTH_EQUATORIAL_RADIUS,
                Constants.WGS84_EARTH_FLATTENING,
                itrf
        );

        // 3) 샘플 배열 준비: (a) ECEF (r,v) (b) Inertial(ICRF) (r,v)
        final int n = v.size();
        final AbsoluteDate[] tAbs = new AbsoluteDate[n];

        final Vector3D[] rEcef = new Vector3D[n];
        final Vector3D[] vEcef = new Vector3D[n];

        final Vector3D[] rInert = new Vector3D[n];
        final Vector3D[] vInert = new Vector3D[n];

        for (int i = 0; i < n; i++) {
            AbsoluteDate t = timeConverter.localDateTimeUtcToAbsoluteDate(v.get(i).getTime());
            tAbs[i] = t;

            PVCoordinates pvE = new PVCoordinates(v.get(i).getPos(), v.get(i).getVel());
            rEcef[i] = pvE.getPosition();
            vEcef[i] = pvE.getVelocity();

            Transform tr = itrf.getTransformTo(inertial, t);
            PVCoordinates pvI = tr.transformPVCoordinates(pvE);
            rInert[i] = pvI.getPosition();
            vInert[i] = pvI.getVelocity();
        }

        // 4) 분석구간을 샘플 범위로 클램프
        AbsoluteDate minT = tAbs[0];
        AbsoluteDate maxT = tAbs[n - 1];
        AbsoluteDate winStart = (start.compareTo(minT) < 0) ? minT : start;
        AbsoluteDate winEnd   = (end.compareTo(maxT) > 0) ? maxT : end;
        if (winStart.compareTo(winEnd) >= 0) return List.of();

        // 5) 노드 이벤트(ASC/DESC) 수집: ICRF z=0 교차
        List<NodeEvent> nodes = collectNodeEventsNoProp(
                tAbs, rInert, vInert,
                minT, maxT,
                subStepSec, rootTolSec, rootMaxIter
        );
        if (nodes.isEmpty()) return List.of();

        // 6) ASC 리스트(pass boundary)
        List<AbsoluteDate> ascList = new ArrayList<>();
        for (NodeEvent e : nodes) if (e.getType() == NodeType.ASC) ascList.add(e.getT());
        ascList.sort(AbsoluteDate::compareTo);
        if (ascList.isEmpty()) return List.of();

        // 7) window 시작이 속한 pass 경계(ASC_prev, ASC_next)
        AbsoluteDate ascPrev = lastAscAtOrBefore(ascList, winStart); // null 가능
        AbsoluteDate ascNext = firstAscAfter(ascList, winStart);     // null 가능

        long passNum = (sat.getOrbitNumber() != null) ? sat.getOrbitNumber() : 0L;

        List<NodalCrossing> out = new ArrayList<>();

        AbsoluteDate passStartAsc = ascPrev; // null 가능
        AbsoluteDate passEndAsc   = ascNext; // null 가능

        while (true) {
            // pass 범위 정의(ASC가 null이면 샘플 시작/끝을 사용해 pass의 “절단 구간”을 대표)
            AbsoluteDate passLeft  = (passStartAsc != null) ? passStartAsc : minT;
            AbsoluteDate passRight = (passEndAsc   != null) ? passEndAsc   : maxT;

            // pass-window 겹침이 없으면 다음으로
            if (!(passRight.compareTo(winStart) > 0 && passLeft.compareTo(winEnd) < 0)) {
                if (passEndAsc == null) break;
                passNum++;
                passStartAsc = passEndAsc;
                passEndAsc = firstAscAfter(ascList, passStartAsc);
                continue;
            }

            // PASS 내부 DESC 찾기 (passLeft, passRight)
            AbsoluteDate desc = findDescInPass(nodes, passLeft, passRight);

            // window 내에 들어오는지 여부(밖이면 null => Not in Pass)
            AbsoluteDate ascOut  = inWindowOrNull(passStartAsc, winStart, winEnd);
            AbsoluteDate descOut = inWindowOrNull(desc,        winStart, winEnd);

            // 다음 ASC가 window 안에 있는지(이게 minLat 계산 조건)
            boolean hasNextAscInWindow =
                    (passEndAsc != null) &&
                            (passEndAsc.compareTo(winStart) >= 0) &&
                            (passEndAsc.compareTo(winEnd)   <= 0);

            // -------------------
            // maxLat: ASC~DESC(혹은 window-start~DESC) 구간에서 최대 위도
            // (DESC가 window 안일 때만 의미 있게 기록)
            // -------------------
            AbsoluteDate maxLatOut = null;
            if (descOut != null) {
                // 왼쪽 경계: passStartAsc가 window 안이면 passStartAsc, 아니면 winStart
                AbsoluteDate left = (passStartAsc != null && passStartAsc.compareTo(winStart) > 0) ? passStartAsc : winStart;

                if (left.compareTo(descOut) < 0) {
                    AbsoluteDate maxLat = findLatitudeExtremumTime(
                            left, descOut, true,
                            tAbs, rEcef, vEcef, itrf, earth,
                            rootTolSec
                    );
                    maxLatOut = inWindowOrNull(maxLat, winStart, winEnd);
                }
            }

            // -------------------
            // minLat: DESC~다음 ASC 구간에서 최소 위도
            // ★ 다음 ASC가 window 안에서 완결될 때만 계산
            // -------------------
            AbsoluteDate minLatOut = null;
            if (descOut != null && hasNextAscInWindow) {
                AbsoluteDate right = passEndAsc; // window 안 보장
                if (descOut.compareTo(right) < 0) {
                    AbsoluteDate minLat = findLatitudeExtremumTime(
                            descOut, right, false,
                            tAbs, rEcef, vEcef, itrf, earth,
                            rootTolSec
                    );
                    minLatOut = inWindowOrNull(minLat, winStart, winEnd);
                }
            }
            // else => null => Not in Pass

            out.add(new NodalCrossing(passNum, ascOut, descOut, minLatOut, maxLatOut));

            // 다음 pass로
            if (passEndAsc == null) break;
            passNum++;
            passStartAsc = passEndAsc;
            passEndAsc = firstAscAfter(ascList, passStartAsc);

            if (passStartAsc != null && passStartAsc.compareTo(winEnd) > 0) break;
        }

        return out;
    }

    // =========================================================
    // Node events: inertial z=0 crossing (Hermite + bisection)
    // - ASC/DESC 판정은 velocity 의존 대신 z부호 변화 방향으로 판정(더 안전)
    // =========================================================
    private List<NodeEvent> collectNodeEventsNoProp(
            AbsoluteDate[] tAbs,
            Vector3D[] rInert,
            Vector3D[] vInert,
            AbsoluteDate start,
            AbsoluteDate end,
            double subStepSec,
            double rootTolSec,
            int rootMaxIter
    ) {
        List<NodeEvent> events = new ArrayList<>();

        int i0 = lowerBound(tAbs, start);
        if (i0 > 0) i0--;
        int i1 = upperBound(tAbs, end);
        if (i1 < tAbs.length - 1) i1++;

        final double dedupSec = Math.max(rootTolSec * 5.0, 1e-3);

        // z(t)
        HermiteEventUtils.ScalarFunction fZ = (tt, pos, vel) -> pos.getZ();

        for (int i = Math.max(1, i0 + 1); i <= Math.min(tAbs.length - 1, i1); i++) {

            AbsoluteDate a = tAbs[i - 1];
            AbsoluteDate b = tAbs[i];

            if (b.compareTo(start) <= 0) continue;
            if (a.compareTo(end) >= 0) break;

            AbsoluteDate t0 = tAbs[i - 1];
            AbsoluteDate t1 = tAbs[i];
            Vector3D r0 = rInert[i - 1], vv0 = vInert[i - 1];
            Vector3D r1 = rInert[i],     vv1 = vInert[i];

            AbsoluteDate segStart = (a.compareTo(start) < 0) ? start : a;
            AbsoluteDate segEnd   = (b.compareTo(end)   > 0) ? end   : b;

            double dtSeg = segEnd.durationFrom(segStart);
            if (dtSeg <= 0) continue;

            // 1초 샘플이면 baseStep≈1초, subStepSec는 그 이하로만 의미
            double baseStep = Math.max(1e-9, b.durationFrom(a));
            double effStep = Math.min(subStepSec, baseStep);
            int m = Math.max(1, (int) Math.ceil(dtSeg / effStep));

            AbsoluteDate prevT = segStart;
            HermiteEventUtils.PV pvPrev = pvInterpolateHermite(prevT, t0, r0, vv0, t1, r1, vv1);
            double prevF = fZ.value(prevT, pvPrev.pos(), pvPrev.vel());

            for (int s = 1; s <= m; s++) {
                double frac = (double) s / (double) m;
                AbsoluteDate curT = segStart.shiftedBy(dtSeg * frac);

                HermiteEventUtils.PV pvCur = pvInterpolateHermite(curT, t0, r0, vv0, t1, r1, vv1);
                double curF = fZ.value(curT, pvCur.pos(), pvCur.vel());

                // 부호 변화 감지
                if (prevF == 0.0 || curF == 0.0 || prevF * curF < 0.0) {
                    AbsoluteDate tRoot = HermiteEventUtils.refineRootTimeHermiteBisection(
                            prevT, pvPrev.pos(), pvPrev.vel(),
                            curT,  pvCur.pos(),  pvCur.vel(),
                            fZ,
                            0.0,
                            rootTolSec,
                            rootMaxIter
                    );

                    // ★ ASC/DESC 판정: z 부호 변화 방향(velocity 의존 제거)
                    // z가 - -> + : ASC,  + -> - : DESC
                    // (가장 안전: 입력 vel 정의가 흔들려도 안정)
                    double zA = prevF;
                    double zB = curF;
                    NodeType type;
                    if (zA < zB) type = NodeType.ASC;
                    else         type = NodeType.DESC;

                    if (events.isEmpty() || Math.abs(tRoot.durationFrom(events.get(events.size() - 1).getT())) > dedupSec) {
                        events.add(new NodeEvent(tRoot, type));
                    }
                }

                prevT = curT;
                pvPrev = pvCur;
                prevF = curF;
            }
        }

        events.sort(Comparator.comparing(NodeEvent::getT));
        return events;
    }

    // =========================================================
    // DESC in a pass: (passLeft, passRight) 내부의 DESC 1개 찾기
    // =========================================================
    private AbsoluteDate findDescInPass(List<NodeEvent> nodes, AbsoluteDate passLeft, AbsoluteDate passRight) {
        for (NodeEvent e : nodes) {
            if (e.getT().compareTo(passLeft) <= 0) continue;
            if (e.getT().compareTo(passRight) >= 0) break;
            if (e.getType() == NodeType.DESC) return e.getT();
        }
        return null;
    }

    // =========================================================
    // Latitude extremum (geodetic latitude in ECEF)
    // - ternary search (unimodal assumption) + Hermite PV
    // =========================================================
    private AbsoluteDate findLatitudeExtremumTime(
            AbsoluteDate tL,
            AbsoluteDate tR,
            boolean findMax,
            AbsoluteDate[] tAbs,
            Vector3D[] rEcef,
            Vector3D[] vEcef,
            Frame itrf,
            OneAxisEllipsoid earth,
            double tolSec
    ) {
        if (tL == null || tR == null) return null;
        if (tL.compareTo(tR) >= 0) return null;

        int iL = lowerBound(tAbs, tL);
        int iR = upperBound(tAbs, tR) - 1;
        iL = Math.max(0, Math.min(iL, tAbs.length - 1));
        iR = Math.max(0, Math.min(iR, tAbs.length - 1));
        if (iL > iR) return null;

        int bestIdx = iL;
        double bestVal = latitudeRadAtSample(bestIdx, tAbs, rEcef, itrf, earth);

        for (int i = iL + 1; i <= iR; i++) {
            double lat = latitudeRadAtSample(i, tAbs, rEcef, itrf, earth);
            if (findMax) {
                if (lat > bestVal) { bestVal = lat; bestIdx = i; }
            } else {
                if (lat < bestVal) { bestVal = lat; bestIdx = i; }
            }
        }

        int leftIdx  = Math.max(iL, bestIdx - 1);
        int rightIdx = Math.min(iR, bestIdx + 1);
        AbsoluteDate a = maxDate(tL, tAbs[leftIdx]);
        AbsoluteDate b = minDate(tR, tAbs[rightIdx]);
        if (a.compareTo(b) >= 0) return tAbs[bestIdx];

        for (int iter = 0; iter < 80; iter++) {
            double span = b.durationFrom(a);
            if (span <= tolSec) break;

            AbsoluteDate m1 = a.shiftedBy(span / 3.0);
            AbsoluteDate m2 = b.shiftedBy(-span / 3.0);

            double f1 = latitudeRadAtTime(m1, tAbs, rEcef, vEcef, itrf, earth);
            double f2 = latitudeRadAtTime(m2, tAbs, rEcef, vEcef, itrf, earth);

            if (findMax) {
                if (f1 < f2) a = m1; else b = m2;
            } else {
                if (f1 > f2) a = m1; else b = m2;
            }
        }
        return a.shiftedBy(0.5 * b.durationFrom(a));
    }

    private double latitudeRadAtSample(int idx, AbsoluteDate[] tAbs, Vector3D[] rEcef, Frame itrf, OneAxisEllipsoid earth) {
        AbsoluteDate t = tAbs[idx];
        GeodeticPoint gp = earth.transform(rEcef[idx], itrf, t);
        return gp.getLatitude();
    }

    private double latitudeRadAtTime(
            AbsoluteDate t,
            AbsoluteDate[] tAbs,
            Vector3D[] rEcef,
            Vector3D[] vEcef,
            Frame itrf,
            OneAxisEllipsoid earth
    ) {
        PVCoordinates pv = pvAtTimeNoPropEcef(t, tAbs, rEcef, vEcef);
        GeodeticPoint gp = earth.transform(pv.getPosition(), itrf, t);
        return gp.getLatitude();
    }

    private PVCoordinates pvAtTimeNoPropEcef(
            AbsoluteDate t,
            AbsoluteDate[] tAbs,
            Vector3D[] rEcef,
            Vector3D[] vEcef
    ) {
        int idx = lowerBound(tAbs, t);
        if (idx <= 0) return new PVCoordinates(rEcef[0], vEcef[0]);
        if (idx >= tAbs.length) {
            int last = tAbs.length - 1;
            return new PVCoordinates(rEcef[last], vEcef[last]);
        }

        AbsoluteDate t0 = tAbs[idx - 1];
        AbsoluteDate t1 = tAbs[idx];

        HermiteEventUtils.PV pv = pvInterpolateHermite(
                t,
                t0, rEcef[idx - 1], vEcef[idx - 1],
                t1, rEcef[idx],     vEcef[idx]
        );
        return new PVCoordinates(pv.pos(), pv.vel());
    }

    private HermiteEventUtils.PV pvInterpolateHermite(
            AbsoluteDate t,
            AbsoluteDate t0, Vector3D r0, Vector3D v0,
            AbsoluteDate t1, Vector3D r1, Vector3D v1
    ) {
        double dt = t1.durationFrom(t0);
        if (dt <= 0) return new HermiteEventUtils.PV(r0, v0);
        double tau = t.durationFrom(t0) / dt;
        return HermiteEventUtils.hermitePV(r0, v0, r1, v1, dt, tau);
    }

    // =========================================================
    // "Not in Pass" 처리: window 밖이면 null
    // =========================================================
    private AbsoluteDate inWindowOrNull(AbsoluteDate t, AbsoluteDate winStart, AbsoluteDate winEnd) {
        if (t == null) return null;
        if (t.compareTo(winStart) < 0) return null;
        if (t.compareTo(winEnd) > 0) return null;
        return t;
    }

    // =========================================================
    // ASC list helpers
    // =========================================================
    private AbsoluteDate lastAscAtOrBefore(List<AbsoluteDate> asc, AbsoluteDate t) {
        AbsoluteDate best = null;
        for (AbsoluteDate a : asc) {
            if (a.compareTo(t) <= 0) best = a;
            else break;
        }
        return best;
    }

    private AbsoluteDate firstAscAfter(List<AbsoluteDate> asc, AbsoluteDate t) {
        for (AbsoluteDate a : asc) if (a.compareTo(t) > 0) return a;
        return null;
    }

    // =========================================================
    // Binary search + misc
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

    private AbsoluteDate maxDate(AbsoluteDate a, AbsoluteDate b) { return (a.compareTo(b) >= 0) ? a : b; }
    private AbsoluteDate minDate(AbsoluteDate a, AbsoluteDate b) { return (a.compareTo(b) <= 0) ? a : b; }

    // 파일 출력
    public void generateNCFile(List<NodalCrossing> passes, String satName, Path path) {
        try {
            Files.createDirectories(path);
            Path file = path.resolve(satName + "_Nodal_Crossing" + ".dat");
            if (Files.exists(file)) {
                Files.delete(file);
            }
            ReentrantLock lock = fileLock.computeIfAbsent(file, k -> new ReentrantLock());
            lock.lock();
            try (BufferedWriter w = Files.newBufferedWriter(
                    file,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                    StandardOpenOption.WRITE))
            {
                boolean newFile = Files.size(file) == 0;

                if (newFile) {
                    w.write(String.format("%101s%n",
                            TimeConverter.UTC_DT_HDR_ABBR.format(ZonedDateTime.now(ZoneOffset.UTC))));
                    w.write("Satellite-" + satName);
                    w.newLine(); w.newLine();
                    w.newLine();

                    w.write("Pass Number    Time of Ascen Node (UTCG)    Time of Descen Node (UTCG)     Time of Min Lat (UTCG)      Time of Max Lat (UTCG) ");
                    w.newLine();
                    w.write("-----------    -------------------------    --------------------------    ------------------------    ------------------------");
                    w.newLine();
                }

                for (NodalCrossing p : passes) {
                    String asc  = p.getAscendingNodeTime() == null ? "             Not in Pass"
                            : timeConverter.toUtcAbbrMSec(p.getAscendingNodeTime());
                    String desc = p.getDescendingNodeTime() == null ? "             Not in Pass"
                            : timeConverter.toUtcAbbrMSec(p.getDescendingNodeTime());
                    String minL = p.getMinLatTime() == null ? "             Not in Pass"
                            : timeConverter.toUtcAbbrMSec(p.getMinLatTime());
                    String maxL = p.getMaxLatTime() == null ? "             Not in Pass"
                            : timeConverter.toUtcAbbrMSec(p.getMaxLatTime());

                    w.write(String.format(Locale.US, "%11d    %-25s    %-26s    %-24s    %-24s%n",
                            p.getOrbitNumber(), asc, desc, minL, maxL));
                }
            } finally {
                lock.unlock();
            }

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}

