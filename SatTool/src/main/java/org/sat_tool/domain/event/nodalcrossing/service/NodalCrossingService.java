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
import org.orekit.bodies.CelestialBody;
import org.orekit.bodies.CelestialBodyFactory;
import org.orekit.bodies.GeodeticPoint;
import org.orekit.bodies.OneAxisEllipsoid;
import org.orekit.frames.Frame;
import org.orekit.frames.FramesFactory;
import org.orekit.frames.Transform;
import org.orekit.orbits.CartesianOrbit;
import org.orekit.orbits.Orbit;
import org.orekit.propagation.SpacecraftState;
import org.orekit.propagation.events.EclipseDetector;
import org.orekit.time.AbsoluteDate;
import org.orekit.utils.*;
import org.sat_tool.domain.common.converter.TimeConverter;
import org.sat_tool.domain.common.helper.HermiteEventUtils;
import org.sat_tool.domain.common.model.Satellite;
import org.sat_tool.domain.coordinate.model.EphemerisVector;
import org.sat_tool.domain.event.model.Eclipse;
import org.sat_tool.domain.event.nodalcrossing.model.NodalCrossing;
import org.sat_tool.domain.event.nodalcrossing._enum.NCEventType;
import org.sat_tool.domain.event.nodalcrossing.model.NCEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;


@Service
@DependsOn("orekitInitializer")
public class NodalCrossingService {

    @Autowired private TimeConverter timeConverter;

    private final ConcurrentMap<Path, ReentrantLock> fileLock = new ConcurrentHashMap<>();

    /** 노드 타입 */
    private enum NodeType { ASC, DESC }

    /** 노드 이벤트(시간+타입) */
    private static class NodeEvent {
        final AbsoluteDate t;
        final NodeType type;
        NodeEvent(AbsoluteDate t, NodeType type) { this.t = t; this.type = type; }
        AbsoluteDate getTime() { return t; }
    }

    /**
     * Propagator 없이 ECEF ephemeris만으로 NodalCrossing(ASC/DESC + min/max latitude time) 계산
     *
     * @param sat         Satellite(orbitNumber는 "출력 첫 orbitNumber의 기준값"으로 사용)
     * @param vectorsEcef ECEF(ITRF) ephemeris vectors (time=UTC LocalDateTime)
     * @param start       탐색 시작
     * @param end         탐색 종료
     * @param subStepSec  구간 내 서브샘플링 간격(1초 샘플이면 0.25~0.5 권장)
     * @param rootTolSec  root 정밀화 시간 허용오차(예: 1e-3 = 1ms)
     * @param rootMaxIter 이분법 반복 (예: 60~80)
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
        if (subStepSec <= 0) subStepSec = 0.5;

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

        // 3) 샘플 배열 준비: (a) ECEF (r,v)  (b) Inertial (r,v)
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

        // 4) start/end 클램프
        AbsoluteDate minT = tAbs[0];
        AbsoluteDate maxT = tAbs[n - 1];
        AbsoluteDate tStart = (start.compareTo(minT) < 0) ? minT : start;
        AbsoluteDate tEnd   = (end.compareTo(maxT) > 0) ? maxT : end;
        if (tStart.compareTo(tEnd) >= 0) return List.of();

        // 5) 노드 이벤트(ASC/DESC) 수집: 관성계 z=0 교차
        List<NodeEvent> nodes = collectNodeEventsNoProp(
                tAbs, rInert, vInert,
                tStart, tEnd,
                subStepSec, rootTolSec, rootMaxIter
        );
        if (nodes.isEmpty()) return List.of();

        // 6) ASC -> DESC -> 다음 ASC 로 orbit 레코드 구성
        //    (불완전 구간은 제외: ASC가 없거나, DESC가 없거나, 다음 ASC가 없으면 스킵)
        List<AbsoluteDate> ascList = new ArrayList<>();
        List<AbsoluteDate> descList = new ArrayList<>();
        for (NodeEvent e : nodes) {
            if (e.type == NodeType.ASC) ascList.add(e.t);
            else descList.add(e.t);
        }
        ascList.sort(AbsoluteDate::compareTo);
        descList.sort(AbsoluteDate::compareTo);

        long baseOrbit = (sat.getOrbitNumber() != null) ? sat.getOrbitNumber() : 0L;
        long orbit = baseOrbit;

        List<NodalCrossing> out = new ArrayList<>();

        int dPtr = 0;
        for (int aIdx = 0; aIdx < ascList.size(); aIdx++) {

            AbsoluteDate asc = ascList.get(aIdx);

            // asc 이후 첫 desc 찾기
            while (dPtr < descList.size() && descList.get(dPtr).compareTo(asc) <= 0) dPtr++;
            if (dPtr >= descList.size()) break;
            AbsoluteDate desc = descList.get(dPtr);

            // desc 이후 다음 asc(=다음 orbit 시작) 필요
            AbsoluteDate nextAsc = null;
            for (int j = aIdx + 1; j < ascList.size(); j++) {
                if (ascList.get(j).compareTo(desc) > 0) {
                    nextAsc = ascList.get(j);
                    break;
                }
            }
            if (nextAsc == null) break;

            // 위도 극값 시각 계산
            AbsoluteDate maxLatTime = findLatitudeExtremumTime(
                    asc, desc, true,
                    tAbs, rEcef, vEcef, itrf, earth,
                    rootTolSec
            );
            AbsoluteDate minLatTime = findLatitudeExtremumTime(
                    desc, nextAsc, false,
                    tAbs, rEcef, vEcef, itrf, earth,
                    rootTolSec
            );

            // orbitNumber 부여:
            // - 여기서는 "ASC가 한 orbit 레코드의 시작"이라고 보고 ASC마다 orbit++ 하도록 설계.
            // - 만약 sat.orbitNumber가 이미 ASC 기준이라면 아래 orbit++ 위치만 조정하면 됨.
            orbit++;

            out.add(new NodalCrossing(
                    orbit,
                    asc,
                    desc,
                    minLatTime,
                    maxLatTime
            ));
        }

        return out;
    }

    // =========================================================
    // 1) Node events (inertial z=0 crossing)
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

        // f(t)=z(t)
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

            // 과분할 방지(입력 샘플 간격보다 작은 subStep만 허용)
            double baseSampleStep = Math.max(1e-9, b.durationFrom(a));
            double effStep = Math.min(subStepSec, baseSampleStep);

            int m = (int) Math.ceil(dtSeg / effStep);
            m = Math.max(m, 1);

            AbsoluteDate prevT = segStart;
            HermiteEventUtils.PV pvPrev = pvInterpolateHermite(prevT, t0, r0, vv0, t1, r1, vv1);
            double prevF = fZ.value(prevT, pvPrev.pos(), pvPrev.vel());

            for (int s = 1; s <= m; s++) {
                double frac = (double) s / (double) m;
                AbsoluteDate curT = segStart.shiftedBy(dtSeg * frac);

                HermiteEventUtils.PV pvCur = pvInterpolateHermite(curT, t0, r0, vv0, t1, r1, vv1);
                double curF = fZ.value(curT, pvCur.pos(), pvCur.vel());

                if (prevF == 0.0 || curF == 0.0 || prevF * curF < 0.0) {

                    AbsoluteDate tRoot = HermiteEventUtils.refineRootTimeHermiteBisection(
                            prevT, pvPrev.pos(), pvPrev.vel(),
                            curT,  pvCur.pos(),  pvCur.vel(),
                            fZ,
                            0.0,
                            rootTolSec,
                            rootMaxIter
                    );

                    // root에서 vz 부호로 ASC/DESC 판정
                    HermiteEventUtils.PV pvRoot = pvInterpolateHermite(tRoot, t0, r0, vv0, t1, r1, vv1);
                    double vz = pvRoot.vel().getZ();
                    NodeType type = (vz >= 0.0) ? NodeType.ASC : NodeType.DESC;

                    if (events.isEmpty() || Math.abs(tRoot.durationFrom(events.get(events.size() - 1).t)) > dedupSec) {
                        events.add(new NodeEvent(tRoot, type));
                    }
                }

                prevT = curT;
                pvPrev = pvCur;
                prevF = curF;
            }
        }

        events.sort(Comparator.comparing(NodeEvent::getTime));
        return events;
    }

    // =========================================================
    // 2) Latitude extremum time (ECEF geodetic lat)
    //    - max in [asc, desc]
    //    - min in [desc, nextAsc]
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
        if (tL.compareTo(tR) >= 0) return tL;

        // (1) coarse: 1초 샘플(배열 인덱스)에서 극값 후보 찾기
        int iL = lowerBound(tAbs, tL);
        int iR = upperBound(tAbs, tR) - 1;
        iL = Math.max(0, Math.min(iL, tAbs.length - 1));
        iR = Math.max(0, Math.min(iR, tAbs.length - 1));
        if (iL > iR) return tL;

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

        // (2) bracket: bestIdx 주변 [idx-1, idx+1] 범위로 ternary search
        int leftIdx  = Math.max(iL, bestIdx - 1);
        int rightIdx = Math.min(iR, bestIdx + 1);

        // best가 경계에 걸리면 한 칸 더 확장 시도
        if (leftIdx == bestIdx && bestIdx + 2 <= iR) rightIdx = bestIdx + 2;
        if (rightIdx == bestIdx && bestIdx - 2 >= iL) leftIdx = bestIdx - 2;

        AbsoluteDate a = maxDate(tL, tAbs[leftIdx]);
        AbsoluteDate b = minDate(tR, tAbs[rightIdx]);
        if (a.compareTo(b) >= 0) return tAbs[bestIdx];

        // ternary search (unimodal 가정)
        // 폭이 tolSec 이하가 될 때까지 반복 (최대 80회)
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

    // =========================================================
    // Hermite PV interpolation helpers (ECEF)
    // =========================================================
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
        double tau = t.durationFrom(t0) / dt; // 0~1
        return HermiteEventUtils.hermitePV(r0, v0, r1, v1, dt, tau);
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

    public void generateNCFile(List<NodalCrossing> passes, String satName, Path path) {
        try {
            Files.createDirectories(path);
            Path file = path.resolve(satName+"_Nodal_Crossing" + ".dat");
            if (Files.exists(file)) {          // 이전 결과가 있으면
                Files.delete(file);            // 먼저 삭제
            }
            ReentrantLock lock = fileLock.computeIfAbsent(file, k -> new ReentrantLock());
            lock.lock();
            try (BufferedWriter w = Files.newBufferedWriter(
                    file,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                    StandardOpenOption.WRITE))
            {
                boolean newFile = Files.size(file) == 0;          // 최초 생성 여부

                if (newFile) {
                    w.write(String.format("%101s%n", TimeConverter.UTC_DT_HDR_ABBR.format(ZonedDateTime.now(ZoneOffset.UTC))));
                    w.write("Satellite-" + satName);
                    w.newLine(); w.newLine();
                    w.newLine();

                    w.write( "Pass Number    Time of Ascen Node (UTCG)    Time of Descen Node (UTCG)     Time of Min Lat (UTCG)      Time of Max Lat (UTCG) ");
                    w.newLine();
                    w.write("-----------    -------------------------    --------------------------    ------------------------    ------------------------");  w.newLine();
                }

                for (NodalCrossing p : passes)
                {
                    String asc  = p.getAscendingNodeTime() == null ? "             Not in Pass"
                            : timeConverter.toUtcAbbrMSec(p.getAscendingNodeTime());
                    String desc = p.getDescendingNodeTime() == null ? "             Not in Pass"
                            : timeConverter.toUtcAbbrMSec(p.getDescendingNodeTime());
                    String minL = p.getMinLatTime()   == null ? "             Not in Pass"
                            : timeConverter.toUtcAbbrMSec(p.getMinLatTime());
                    String maxL = p.getMaxLatTime()   == null ? "             Not in Pass"
                            : timeConverter.toUtcAbbrMSec(p.getMaxLatTime());

                    w.write(String.format(Locale.US,  "%11d    %-25s    %-26s    %-24s    %-24s%n",
                            p.getOrbitNumber(), asc, desc, minL, maxL));
                }
            }

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}

