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

    private final TopocentricService topocentricService;
    private final TimeConverter timeConverter;

    // ✅ ContactScheduleService와 동일 구분자
    private static final String KEY_SEP = "|";

    public ContactScheduleWoker(TopocentricService topocentricService, TimeConverter timeConverter) {
        this.topocentricService = topocentricService;
        this.timeConverter = timeConverter;
    }

    @Async
    public CompletableFuture<Void> asyncComputeCsByStation(
            Satellite satellite, Station station,
            List<EphemerisVector> ecefVectors,
            ConcurrentMap<String, List<ContactSchedule>> total) {

        TopocentricFrame stFrame = station.getStationFrame();

        List<ContactSchedule> passes =
                calcContactHorizonFromEcefVectors(satellite, stFrame, ecefVectors);

        // ✅ 안전한 key: sat|stn|mask
        int mask = 0; // 현재 로직은 horizon(0deg) 기준이므로 0
        String key = satellite.getSatelliteName() + KEY_SEP + station.getStationName() + KEY_SEP + mask;

        total.computeIfAbsent(key, k -> Collections.synchronizedList(new ArrayList<>(passes.size())))
                .addAll(passes);

        return CompletableFuture.completedFuture(null);
    }

    private List<ContactSchedule> calcContactHorizonFromEcefVectors(
            Satellite sat,
            TopocentricFrame stFrame,
            List<EphemerisVector> ecefVectors) {

        if (ecefVectors == null || ecefVectors.size() < 2) return List.of();

        // ✅ 시간 정렬 보장
        List<EphemerisVector> v = new ArrayList<>(ecefVectors);
        v.sort(Comparator.comparing(EphemerisVector::getTime));

        HermiteEventUtils.ScalarFunction elevFn =
                (tAbs, posEcef, velEcef) -> topocentricService.getElevation(posEcef, stFrame, tAbs);

        long passNo = (sat.getOrbitNumber() != null) ? sat.getOrbitNumber() : 0L;

        List<ContactSchedule> out = new ArrayList<>(64);

        ContactSchedule curPass = null;
        double maxEl = Double.NEGATIVE_INFINITY;

        EphemerisVector prev = v.get(0);
        AbsoluteDate tPrev = timeConverter.localDateTimeUtcToAbsoluteDate(prev.getTime());
        Vector3D rPrev = prev.getPos();
        Vector3D vPrev = prev.getVel();
        double elPrev = topocentricService.getElevation(rPrev, stFrame, tPrev);

        for (int i = 1; i < v.size(); i++) {
            EphemerisVector cur = v.get(i);

            AbsoluteDate tCur = timeConverter.localDateTimeUtcToAbsoluteDate(cur.getTime());
            Vector3D rCur = cur.getPos();
            Vector3D vCur = cur.getVel();

            double dt = tCur.durationFrom(tPrev);
            if (dt <= 0) {
                tPrev = tCur; rPrev = rCur; vPrev = vCur;
                elPrev = topocentricService.getElevation(rPrev, stFrame, tPrev);
                continue;
            }

            double elCur = topocentricService.getElevation(rCur, stFrame, tCur);

            // AOS: el crosses 0 upward
            if (curPass == null && elPrev <= 0.0 && elCur > 0.0) {
                AbsoluteDate aos = HermiteEventUtils.refineRootTimeHermiteBisection(
                        tPrev, rPrev, vPrev,
                        tCur,  rCur,  vCur,
                        elevFn,
                        0.0,
                        1e-3,
                        60
                );

                curPass = new ContactSchedule(passNo, aos, null, 0.0, 0.0);
                maxEl = 0.0;
            }

            if (curPass != null) {
                if (elCur > maxEl) maxEl = elCur;
            }

            // LOS: el crosses 0 downward
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
                curPass.setDuration(los.durationFrom(curPass.getAos()));
                curPass.setMaxElevation(maxEl);

                out.add(curPass);

                curPass = null;
                maxEl = Double.NEGATIVE_INFINITY;
                passNo++;
            }

            tPrev = tCur;
            rPrev = rCur;
            vPrev = vCur;
            elPrev = elCur;
        }

        // 끝까지 LOS가 없으면 “윈도우 밖”으로 취급하고 싶다면 null로 두는 방식도 가능
        // (현재는 마지막 시각으로 마감)
        if (curPass != null) {
            AbsoluteDate end = timeConverter.localDateTimeUtcToAbsoluteDate(v.get(v.size() - 1).getTime());
            curPass.setLos(end);
            curPass.setDuration(end.durationFrom(curPass.getAos()));
            curPass.setMaxElevation(maxEl);
            out.add(curPass);
        }

        return out;
    }
}
