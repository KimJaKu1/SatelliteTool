package org.sat_tool.domain.event.contactschedule.worker;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;

import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.orekit.frames.TopocentricFrame;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.time.AbsoluteDate;
import org.sat_tool.domain.common.converter.TimeConverter;
import org.sat_tool.domain.common.helper.HermiteEventUtils;
import org.sat_tool.domain.common.model.Satellite;
import org.sat_tool.domain.common.model.Station;
import org.sat_tool.domain.coordinate.model.EphemerisVector;
import org.sat_tool.domain.coordinate.service.CoordinateService;
import org.sat_tool.domain.coordinate.service.SatPosService;
import org.sat_tool.domain.coordinate.service.TopocentricService;
import org.sat_tool.domain.event.contactschedule.model.ContactSchedule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@DependsOn("orekitInitializer")
public class ContactScheduleWoker {

    @Autowired
    private TopocentricService topocentricService;
    @Autowired
    private CoordinateService coordinateService;

    @Autowired
    private TimeConverter timeConverter;
    @Autowired
    private SatPosService satPosService;

    @Async
    public CompletableFuture<Void> asyncComputeCsByStation(Satellite satellite, Station station,
                                                           List<EphemerisVector> ecefVectors,
                                                           ConcurrentMap<String, List<ContactSchedule>> total)
    {
        TopocentricFrame stFrame = station.getStationFrame();

        List<ContactSchedule> passes =
                calcContactHorizonFromEcefVectors(satellite, stFrame, ecefVectors);

        // 기존 파일 키/파서 유지 목적이면 마지막에 "_0" 고정
        String key = satellite.getSatelliteName() + "_" + station.getStationName() + "_0";

        total.computeIfAbsent(key, k -> Collections.synchronizedList(new ArrayList<>(passes.size())))
                .addAll(passes);

        return CompletableFuture.completedFuture(null);
    }

    private List<ContactSchedule> calcContactHorizonFromEcefVectors(
            Satellite sat,
            TopocentricFrame stFrame,
            List<EphemerisVector> ecefVectors
    ) {
        if (ecefVectors == null || ecefVectors.size() < 2) return List.of();

        // elevation 함수: pos는 ECEF(ITRF)로 들어와야 station frame과 일관됨
        HermiteEventUtils.ScalarFunction elevFn =
                (tAbs, posEcef, velEcef) -> topocentricService.getElevation(posEcef, stFrame, tAbs);

        // pass 번호(또는 orbitNumber 필드) 시작값
        long passNo = (sat.getOrbitNumber() != null) ? sat.getOrbitNumber() : 0L;

        List<ContactSchedule> out = new ArrayList<>(64);

        ContactSchedule curPass = null;
        double maxEl = Double.NEGATIVE_INFINITY;

        EphemerisVector prev = ecefVectors.get(0);
        AbsoluteDate tPrev = timeConverter.localDateTimeUtcToAbsoluteDate(prev.getTime());
        Vector3D rPrev = prev.getPos();
        Vector3D vPrev = prev.getVel();
        double elPrev = topocentricService.getElevation(rPrev, stFrame, tPrev);

        for (int i = 1; i < ecefVectors.size(); i++) {
            EphemerisVector cur = ecefVectors.get(i);

            AbsoluteDate tCur = timeConverter.localDateTimeUtcToAbsoluteDate(cur.getTime());
            Vector3D rCur = cur.getPos();
            Vector3D vCur = cur.getVel();

            double dt = tCur.durationFrom(tPrev);
            if (dt <= 0) {
                // 이상 데이터(동일/역전 시간) 스킵
                tPrev = tCur; rPrev = rCur; vPrev = vCur;
                elPrev = topocentricService.getElevation(rPrev, stFrame, tPrev);
                continue;
            }

            double elCur = topocentricService.getElevation(rCur, stFrame, tCur);

            // ------------------------
            // AOS: el crosses 0 upward
            // ------------------------
            if (curPass == null && elPrev <= 0.0 && elCur > 0.0) {
                AbsoluteDate aos = HermiteEventUtils.refineRootTimeHermiteBisection(
                        tPrev, rPrev, vPrev,
                        tCur,  rCur,  vCur,
                        elevFn,
                        0.0,     // target elevation = 0
                        1e-3,    // tolSeconds
                        60       // maxIter
                );

                // AOS는 정확히 el=0인 시각
                curPass = new ContactSchedule(
                        passNo,
                        aos,
                        null,
                        0.0,   // maxEl 초기값
                        0.0
                );
                maxEl = 0.0;
            }

            // pass 내부 max elevation (샘플 기반)
            if (curPass != null) {
                if (elCur > maxEl) {
                    maxEl = elCur;
                }
            }

            // ------------------------
            // LOS: el crosses 0 downward
            // ------------------------
            if (curPass != null && elPrev > 0.0 && elCur <= 0.0) {
                AbsoluteDate los = HermiteEventUtils.refineRootTimeHermiteBisection(
                        tPrev, rPrev, vPrev,
                        tCur,  rCur,  vCur,
                        elevFn,
                        0.0,
                        1e-3,
                        60
                );

                curPass.setLos(los);
                curPass.setDuration((int) los.durationFrom(curPass.getAos()));
                curPass.setMaxElevation(maxEl);

                out.add(curPass);

                // 다음 패스로
                curPass = null;
                maxEl = Double.NEGATIVE_INFINITY;
                passNo++;
            }

            // 다음 구간
            tPrev = tCur;
            rPrev = rCur;
            vPrev = vCur;
            elPrev = elCur;
        }

        // 열린 pass flush (끝까지 el>0)
        if (curPass != null) {
            // 마지막 벡터 시각을 LOS로 마감(데이터 범위 밖의 LOS는 알 수 없음)
            AbsoluteDate end = timeConverter.localDateTimeUtcToAbsoluteDate(ecefVectors.get(ecefVectors.size() - 1).getTime());
            curPass.setLos(end);
            curPass.setDuration((int) end.durationFrom(curPass.getAos()));
            curPass.setMaxElevation(maxEl);
            out.add(curPass);
        }

        return out;
    }

    private long calculateInitialOrbitNumber(TLE tle, AbsoluteDate startDate, Long orbitNum) {
        AbsoluteDate epochDate = tle.getDate();

        double meanMotion = tle.getMeanMotion();

        double meanMotionRevsPerDay = meanMotion * 86400 / (2 * Math.PI);

        double elapsedTimeInSeconds = startDate.durationFrom(epochDate);

        double revolutionsSinceEpoch = meanMotionRevsPerDay * (elapsedTimeInSeconds / 86400.0);

        return (long) (orbitNum + Math.floor(revolutionsSinceEpoch));
    }

}
