package org.sat_tool.domain.contact.worker;

import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.orekit.frames.TopocentricFrame;
import org.orekit.time.AbsoluteDate;
import org.sat_tool.orekit.TimeConverter;
import org.sat_tool.domain.common.helper.HermiteEventUtils;
import org.sat_tool.domain.common.helper.OrbitNumbers;
import org.sat_tool.domain.common.model.ReportKey;
import org.sat_tool.domain.common.model.Satellite;
import org.sat_tool.domain.common.model.Station;
import org.sat_tool.domain.coordinate.model.EphemerisVector;
import org.sat_tool.domain.coordinate.service.TopocentricService;
import org.sat_tool.domain.contact.model.ContactSchedule;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;

@Component
@DependsOn("orekitInitializer")
public class ContactScheduleWorker {

    private final TopocentricService topocentricService;

    public ContactScheduleWorker(TopocentricService topocentricService) {
        this.topocentricService = topocentricService;
    }

    @Async
    public CompletableFuture<Void> asyncComputeCsByStation(
            Satellite satellite, Station station,
            List<EphemerisVector> ecefVectors,
            ConcurrentMap<String, List<ContactSchedule>> total,
            double orbitPeriodSeconds) {

        TopocentricFrame stFrame = station.getStationFrame();
        List<Integer> masks = masksFor(station);

        List<List<ContactSchedule>> passesByMask =
                calcContactScheduleFromEcefVectors(satellite, stFrame, ecefVectors, masks, orbitPeriodSeconds);

        for (int mi = 0; mi < masks.size(); mi++) {
            List<ContactSchedule> passes = passesByMask.get(mi);
            if (passes.isEmpty()) {
                continue;
            }

            String key = new ReportKey(satellite.getSatelliteName(), station.getStationName(), masks.get(mi)).format();
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

    /**
     * ephemeris를 한 번만 정렬/고도 계산하고, 마스크별 pass 상태머신을 내부 루프로 돌린다.
     * (고도는 마스크와 무관하므로 마스크마다 재계산하지 않는다)
     */
    private List<List<ContactSchedule>> calcContactScheduleFromEcefVectors(
            Satellite sat,
            TopocentricFrame stFrame,
            List<EphemerisVector> ecefVectors,
            List<Integer> masks,
            double orbitPeriodSeconds) {

        int mCnt = masks.size();

        List<List<ContactSchedule>> out = new ArrayList<>(mCnt);
        for (int mi = 0; mi < mCnt; mi++) {
            out.add(new ArrayList<>(64));
        }

        if (ecefVectors == null || ecefVectors.size() < 2) return out;

        List<EphemerisVector> v = new ArrayList<>(ecefVectors);
        v.sort(Comparator.comparing(EphemerisVector::getTime));

        HermiteEventUtils.ScalarFunction elevFn =
                (tAbs, posEcef, velEcef) -> topocentricService.getElevation(posEcef, stFrame, tAbs);

        long baseOrbit = (sat.getOrbitNumber() != null) ? sat.getOrbitNumber() : 0L;

        ContactSchedule[] curPass = new ContactSchedule[mCnt];
        double[] maxEl = new double[mCnt];

        EphemerisVector prev = v.get(0);
        AbsoluteDate tPrev = TimeConverter.localDateTimeUtcToAbsoluteDate(prev.getTime());
        Vector3D rPrev = prev.getPos();
        Vector3D vPrev = prev.getVel();
        double elPrev = topocentricService.getElevation(rPrev, stFrame, tPrev);

        // 궤도 번호 기준 시각: 첫 ephemeris 샘플 시각 (sat.orbitNumber가 이 시점 기준으로 계산되어 있어야 함)
        final AbsoluteDate orbitBaseDate = tPrev;

        // 첫 샘플이 이미 마스크 위(AOS가 윈도우 이전에 발생)여도 진행 중 pass로 잡지 않는다 —
        // AOS를 윈도우 내에서 관측하지 못한 이벤트는 생성하지 않는다.
        for (int mi = 0; mi < mCnt; mi++) {
            curPass[mi] = null;
            maxEl[mi] = Double.NEGATIVE_INFINITY;
        }

        for (int i = 1; i < v.size(); i++) {
            EphemerisVector cur = v.get(i);

            AbsoluteDate tCur = TimeConverter.localDateTimeUtcToAbsoluteDate(cur.getTime());
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

            for (int mi = 0; mi < mCnt; mi++) {
                int maskDeg = masks.get(mi);

                if (curPass[mi] == null && elPrev <= maskDeg && elCur > maskDeg) {
                    AbsoluteDate aos = HermiteEventUtils.refineRootTimeHermiteBisection(
                            tPrev, rPrev, vPrev,
                            tCur, rCur, vCur,
                            elevFn,
                            maskDeg,
                            1e-3,
                            60
                    );

                    // 궤도 번호는 일련번호가 아니라 AOS 시각의 실제 궤도 번호 (주기 기반, Eclipse와 동일 규약)
                    long orbitNum = OrbitNumbers.at(baseOrbit, orbitBaseDate, aos, orbitPeriodSeconds);
                    curPass[mi] = new ContactSchedule(orbitNum, aos, null, 0.0, 0.0);
                    maxEl[mi] = maskDeg;
                }

                if (curPass[mi] != null && elCur > maxEl[mi]) {
                    maxEl[mi] = elCur;
                }

                if (curPass[mi] != null && elPrev > maskDeg && elCur <= maskDeg) {
                    AbsoluteDate los = HermiteEventUtils.refineRootTimeHermiteBisection(
                            tPrev, rPrev, vPrev,
                            tCur, rCur, vCur,
                            elevFn,
                            maskDeg,
                            1e-3,
                            60
                    );

                    curPass[mi].setLos(los);
                    curPass[mi].setDuration(los.durationFrom(curPass[mi].getAos()));
                    curPass[mi].setMaxElevation(maxEl[mi]);

                    out.get(mi).add(curPass[mi]);

                    curPass[mi] = null;
                    maxEl[mi] = Double.NEGATIVE_INFINITY;
                }
            }

            tPrev = tCur;
            rPrev = rCur;
            vPrev = vCur;
            elPrev = elCur;
        }

        // 윈도우 끝까지 LOS가 관측되지 않은 pass(교신 종료 여부 불확실)는 생성하지 않고 폐기한다.
        return out;
    }
}
