package org.sat_tool.domain.event.contactschedule.worker;

import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.orekit.frames.TopocentricFrame;
import org.orekit.time.AbsoluteDate;
import org.sat_tool.domain.common.converter.TimeConverter;
import org.sat_tool.domain.common.helper.HermiteEventUtils;
import org.sat_tool.domain.common.model.Satellite;
import org.sat_tool.domain.common.model.Station;
import org.sat_tool.domain.coordinate.model.EphemerisVector;
import org.sat_tool.domain.coordinate.service.TopocentricService;
import org.sat_tool.domain.event.contactschedule.model.ContactSchedule;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;

@Service
@DependsOn("orekitInitializer")
public class ContactScheduleWoker {

    private final TopocentricService topocentricService;
    private final TimeConverter timeConverter;

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

        for (int mask : masksFor(station)) {
            List<ContactSchedule> passes =
                    calcContactScheduleFromEcefVectors(satellite, stFrame, ecefVectors, mask);

            if (passes.isEmpty()) {
                continue;
            }

            String key = satellite.getSatelliteName() + KEY_SEP + station.getStationName() + KEY_SEP + mask;
            total.computeIfAbsent(key, k -> Collections.synchronizedList(new ArrayList<>(passes.size())))
                    .addAll(passes);
        }

        return CompletableFuture.completedFuture(null);
    }

    private List<Integer> masksFor(Station station) {
        if (station.getAngles() == null || station.getAngles().isEmpty()) {
            return List.of(0);
        }

        return station.getAngles().stream()
                .distinct()
                .sorted()
                .toList();
    }

    private List<ContactSchedule> calcContactScheduleFromEcefVectors(
            Satellite sat,
            TopocentricFrame stFrame,
            List<EphemerisVector> ecefVectors,
            int maskDeg) {

        if (ecefVectors == null || ecefVectors.size() < 2) return List.of();

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
                tPrev = tCur;
                rPrev = rCur;
                vPrev = vCur;
                elPrev = topocentricService.getElevation(rPrev, stFrame, tPrev);
                continue;
            }

            double elCur = topocentricService.getElevation(rCur, stFrame, tCur);

            if (curPass == null && elPrev <= maskDeg && elCur > maskDeg) {
                AbsoluteDate aos = HermiteEventUtils.refineRootTimeHermiteBisection(
                        tPrev, rPrev, vPrev,
                        tCur, rCur, vCur,
                        elevFn,
                        maskDeg,
                        1e-3,
                        60
                );

                curPass = new ContactSchedule(passNo, aos, null, 0.0, 0.0);
                maxEl = maskDeg;
            }

            if (curPass != null && elCur > maxEl) {
                maxEl = elCur;
            }

            if (curPass != null && elPrev > maskDeg && elCur <= maskDeg) {
                AbsoluteDate los = HermiteEventUtils.refineRootTimeHermiteBisection(
                        tPrev, rPrev, vPrev,
                        tCur, rCur, vCur,
                        elevFn,
                        maskDeg,
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
