package org.sat_tool.domain.antenna.worker;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;

import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.orekit.frames.TopocentricFrame;
import org.orekit.time.AbsoluteDate;
import org.sat_tool.orekit.TimeConverter;
import org.sat_tool.domain.common.helper.HermiteEventUtils;
import org.sat_tool.domain.common.model.ReportKey;
import org.sat_tool.domain.common.model.Station;
import org.sat_tool.domain.coordinate.model.EphemerisVector;
import org.sat_tool.domain.coordinate.service.TopocentricService;
import org.sat_tool.domain.antenna.model.AntennaTracking;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@DependsOn("orekitInitializer")
@Component
public class AntennaTrackingWorker {

    private final TopocentricService topocentricService;

    public AntennaTrackingWorker(TopocentricService topocentricService) {
        this.topocentricService = topocentricService;
    }

    // =========================
    // ✅ 방법 2: 저장 시점 스냅(3자리 출력 기준)
    // =========================
    private static final double PRINT_3DP_EPS_DEG = 5e-4; // 0.0005 deg

    /** "-0.000" elevation 표시 방지용 스냅 — elevation에만 적용한다 */
    private static double snapNegZero(double x) {
        return (Math.abs(x) < PRINT_3DP_EPS_DEG) ? 0.0 : x;
    }

    @Async
    public CompletableFuture<Void> asyncComputeAtByStation(
            String satName,
            Station station,
            List<EphemerisVector> ecefVectors,
            ConcurrentMap<String, List<List<AntennaTracking>>> total
    ) {
        Map<String, List<List<AntennaTracking>>> part =
                computeAntennaTrackingFrom(satName, station, ecefVectors);

        part.forEach((k, v) ->
                total.computeIfAbsent(k, kk -> Collections.synchronizedList(new ArrayList<>()))
                        .addAll(v)
        );

        return CompletableFuture.completedFuture(null);
    }

    private int[] masksFor(Station station) {
        if (station.getAngles() == null || station.getAngles().isEmpty()) {
            return new int[]{0};
        }

        return station.getAngles().stream()
                .distinct()
                .sorted()
                .mapToInt(Integer::intValue)
                .toArray();
    }

    private Map<String, List<List<AntennaTracking>>> computeAntennaTrackingFrom(
            String satName,
            Station station,
            List<EphemerisVector> ecefVectors
    ) {
        if (ecefVectors == null || ecefVectors.size() < 2) return Map.of();

        TopocentricFrame frame = station.getStationFrame();

        int[] masks = masksFor(station);
        int mCnt = masks.length;

        boolean[] inPass = new boolean[mCnt];

        @SuppressWarnings("unchecked")
        List<AntennaTracking>[] buf = Stream.generate(ArrayList<AntennaTracking>::new)
                .limit(mCnt).toArray(List[]::new);

        Map<String, List<List<AntennaTracking>>> out = new HashMap<>();

        // Elevation 스칼라 함수 (deg 반환 가정)
        HermiteEventUtils.ScalarFunction elevFnDeg =
                (t, pos, vel) -> topocentricService.getElevation(pos, frame, t);

        EphemerisVector prev = null;
        double el0 = Double.NaN; // 직전 샘플의 elevation 캐시 (구간마다 재계산 방지)

        for (EphemerisVector cur : ecefVectors) {

            if (prev == null) {
                AbsoluteDate t = TimeConverter.localDateTimeUtcToAbsoluteDate(cur.getTime());
                double el = topocentricService.getElevation(cur.getPos(), frame, t);

                // az는 pass 내부일 때만 필요 → 지연 계산
                double az = Double.NaN;
                double elOut = snapNegZero(el);

                for (int mi = 0; mi < mCnt; mi++) {
                    double thr = masks[mi];
                    if (el > thr) {
                        if (Double.isNaN(az)) {
                            az = topocentricService.getAzimuth(cur.getPos(), frame, t);
                        }
                        inPass[mi] = true;
                        buf[mi].add(new AntennaTracking(TimeConverter.toUtcAbbrMSec(t), az, elOut));
                    }
                }

                prev = cur;
                el0 = el;
                continue;
            }

            // prev-cur 구간
            AbsoluteDate t0 = TimeConverter.localDateTimeUtcToAbsoluteDate(prev.getTime());
            AbsoluteDate t1 = TimeConverter.localDateTimeUtcToAbsoluteDate(cur.getTime());

            Vector3D r0 = prev.getPos();
            Vector3D v0 = prev.getVel();
            Vector3D r1 = cur.getPos();
            Vector3D v1 = cur.getVel();

            double dt = t1.durationFrom(t0);
            if (dt <= 0) {
                prev = cur;
                el0 = topocentricService.getElevation(r1, frame, t1);
                continue;
            }

            double el1 = topocentricService.getElevation(r1, frame, t1);
            double az1 = Double.NaN; // pass 내부 샘플이 있을 때만 계산 → 지연 계산

            for (int mi = 0; mi < mCnt; mi++) {
                double thr = masks[mi];

                // ---- 1) 진입: el0 <= thr && el1 > thr  ----
                if (!inPass[mi] && el0 <= thr && el1 > thr) {
                    AbsoluteDate tEnter = HermiteEventUtils.refineRootTimeHermiteBisection(
                            t0, r0, v0,
                            t1, r1, v1,
                            elevFnDeg,
                            thr,     // target = maskAngle
                            1e-3,     // tolSeconds
                            60        // maxIter
                    );

                    // 경계점(정확히 el==thr) 한 줄 삽입
                    buf[mi].add(buildTrackingAtBoundary(t0, r0, v0, t1, r1, v1, tEnter, frame));

                    inPass[mi] = true;
                    // 이후 pass 내부 처리에서 cur 샘플이 추가됨
                }

                // ---- 2) pass 내부: inPass && el1 > thr  ----
                if (inPass[mi] && el1 > thr) {
                    if (Double.isNaN(az1)) {
                        az1 = topocentricService.getAzimuth(r1, frame, t1);
                    }

                    // ✅ 스냅 (elevation만)
                    double elOut = snapNegZero(el1);

                    buf[mi].add(new AntennaTracking(TimeConverter.toUtcAbbrMSec(t1), az1, elOut));
                    continue;
                }

                // ---- 3) 이탈: inPass && el0 > thr && el1 <= thr  ----
                if (inPass[mi] && el0 > thr && el1 <= thr) {
                    AbsoluteDate tExit = HermiteEventUtils.refineRootTimeHermiteBisection(
                            t0, r0, v0,
                            t1, r1, v1,
                            elevFnDeg,
                            thr,
                            1e-3,
                            60
                    );

                    // 경계점(정확히 el==thr) 한 줄 삽입
                    buf[mi].add(buildTrackingAtBoundary(t0, r0, v0, t1, r1, v1, tExit, frame));

                    inPass[mi] = false;

                    // pass flush
                    String key = new ReportKey(satName, station.getStationName(), masks[mi]).format();
                    out.computeIfAbsent(key, k -> new ArrayList<>()).add(buf[mi]);
                    buf[mi] = new ArrayList<>();
                }
            }

            prev = cur;
            el0 = el1;
        }

        // 열린 pass flush
        for (int mi = 0; mi < mCnt; mi++) {
            if (inPass[mi] && !buf[mi].isEmpty()) {
                String key = new ReportKey(satName, station.getStationName(), masks[mi]).format();
                out.computeIfAbsent(key, k -> new ArrayList<>()).add(buf[mi]);
            }
        }

        return out;
    }

    /**
     * tBoundary에서 Hermite 보간으로 pos를 만들고, 그 pos로 az/el을 계산해 row 생성
     * (tBoundary는 refineRootTime... 결과로 elevation == maskAngle인 시각)
     */
    private AntennaTracking buildTrackingAtBoundary(
            AbsoluteDate t0, Vector3D r0, Vector3D v0,
            AbsoluteDate t1, Vector3D r1, Vector3D v1,
            AbsoluteDate tBoundary,
            TopocentricFrame frame
    ) {
        double dt = t1.durationFrom(t0);
        double tau = (dt <= 0) ? 0.0 : tBoundary.durationFrom(t0) / dt;
        tau = Math.max(0.0, Math.min(1.0, tau));

        HermiteEventUtils.PV pv = HermiteEventUtils.hermitePV(r0, v0, r1, v1, dt, tau);

        double az = topocentricService.getAzimuth(pv.pos(), frame, tBoundary);
        double el = topocentricService.getElevation(pv.pos(), frame, tBoundary);

        // ✅ 스냅(여기가 -0.000 방지의 핵심, elevation만)
        double elOut = snapNegZero(el);

        return new AntennaTracking(TimeConverter.toUtcAbbrMSec(tBoundary), az, elOut);
    }
}
