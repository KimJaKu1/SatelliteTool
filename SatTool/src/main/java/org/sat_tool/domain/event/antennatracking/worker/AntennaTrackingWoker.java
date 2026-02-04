package org.sat_tool.domain.event.antennatracking.worker;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;

import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.orekit.frames.TopocentricFrame;
import org.orekit.time.AbsoluteDate;
import org.sat_tool.domain.common.converter.TimeConverter;
import org.sat_tool.domain.common.helper.HermiteEventUtils;
import org.sat_tool.domain.common.model.Station;
import org.sat_tool.domain.coordinate.model.EphemerisVector;
import org.sat_tool.domain.coordinate.service.TopocentricService;
import org.sat_tool.domain.event.antennatracking.model.AntennaTracking;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class AntennaTrackingWoker {
    @Autowired private TopocentricService topocentricService;
    @Autowired private TimeConverter timeConverter;

    /** 위성 ECEF(ITRF) ephemeris를 EphemerisVector로 1회 생성(공유용) */
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

    private Map<String, List<List<AntennaTracking>>> computeAntennaTrackingFrom(
            String satName,
            Station station,
            List<EphemerisVector> ecefVectors
    ) {
        if (ecefVectors == null || ecefVectors.size() < 2) return Map.of();

        TopocentricFrame frame = station.getStationFrame();

        int[] masks = station.getAngles().stream().sorted().mapToInt(Integer::intValue).toArray();
        int mCnt = masks.length;

        boolean[] inPass = new boolean[mCnt];

        @SuppressWarnings("unchecked")
        List<AntennaTracking>[] buf = Stream.generate(ArrayList<AntennaTracking>::new)
                .limit(mCnt).toArray(List[]::new);

        Map<String, List<List<AntennaTracking>>> out = new HashMap<>();

        // Elevation 스칼라 함수 (단위 주의: 아래 코드는 "deg 반환"을 가정)
        HermiteEventUtils.ScalarFunction elevFnDeg =
                (t, pos, vel) -> topocentricService.getElevation(pos, frame, t);

        EphemerisVector prev = null;

        for (EphemerisVector cur : ecefVectors) {
            if (prev == null) {
                // 첫 샘플: 시작부터 마스크 위에 있으면 pass를 열고 첫 점을 기록(경계는 이전 샘플이 없으니 정밀 계산 불가)
                AbsoluteDate t = timeConverter.localDateTimeUtcToAbsoluteDate(cur.getTime());
                double az = topocentricService.getAzimuth(cur.getPos(), frame, t);
                double el = topocentricService.getElevation(cur.getPos(), frame, t);

                for (int mi = 0; mi < mCnt; mi++) {
                    double thr = masks[mi];
                    if (el > thr) {
                        inPass[mi] = true;
                        buf[mi].add(new AntennaTracking(timeConverter.toUtcAbbrMSec(t), (float) az, (float) el));
                    }
                }

                prev = cur;
                continue;
            }

            // prev-cur 구간
            AbsoluteDate t0 = timeConverter.localDateTimeUtcToAbsoluteDate(prev.getTime());
            AbsoluteDate t1 = timeConverter.localDateTimeUtcToAbsoluteDate(cur.getTime());

            Vector3D r0 = prev.getPos();
            Vector3D v0 = prev.getVel();
            Vector3D r1 = cur.getPos();
            Vector3D v1 = cur.getVel();

            double dt = t1.durationFrom(t0);
            if (dt <= 0) {
                prev = cur;
                continue;
            }

            double el0 = topocentricService.getElevation(r0, frame, t0);
            double el1 = topocentricService.getElevation(r1, frame, t1);

            // cur 샘플 값(필요시 pass 내부에 그대로 기록)
            double az1 = topocentricService.getAzimuth(r1, frame, t1);

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
                    // 이후 아래 "pass 내부 샘플 추가" 로직에서 cur(t1) 샘플이 추가됨
                }

                // ---- 2) pass 내부: inPass && el1 > thr  ----
                if (inPass[mi] && el1 > thr) {
                    // 간격 유지: cur 시각(t1)을 그대로 기록
                    buf[mi].add(new AntennaTracking(timeConverter.toUtcAbbrMSec(t1), (float) az1, (float) el1));
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
                    int m = masks[mi];
                    String key = satName + "_" + station.getStationName() + "_" + m;
                    out.computeIfAbsent(key, k -> new ArrayList<>()).add(buf[mi]);
                    buf[mi] = new ArrayList<>();
                }
            }

            prev = cur;
        }

        // 열린 pass flush
        for (int mi = 0; mi < mCnt; mi++) {
            if (inPass[mi] && !buf[mi].isEmpty()) {
                int m = masks[mi];
                String key = satName + "_" + station.getStationName() + "_" + m;
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

        // ms까지 출력(경계시각 정밀도가 보이도록)
        return new AntennaTracking(timeConverter.toUtcAbbrMSec(tBoundary), (float) az, (float) el);
    }
}
