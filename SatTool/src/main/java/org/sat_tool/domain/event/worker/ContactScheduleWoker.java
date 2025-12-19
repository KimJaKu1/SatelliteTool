package org.sat_tool.domain.event.worker;

import java.sql.Time;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;

import org.orekit.frames.TopocentricFrame;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.propagation.analytical.tle.TLEPropagator;
import org.orekit.time.AbsoluteDate;
import org.sat_tool.domain.common.converter.TimeConverter;
import org.sat_tool.domain.common.model.Satellite;
import org.sat_tool.domain.common.model.Station;
import org.sat_tool.domain.coordinate.service.CoordinateService;
import org.sat_tool.domain.coordinate.service.SatPosService;
import org.sat_tool.domain.coordinate.service.TopocentricService;
import org.sat_tool.domain.event.model.ContactSchedule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@DependsOn("orekitInitializer")
public class ContactScheduleWoker {

    @Autowired private TopocentricService topocentricService;
    @Autowired private CoordinateService coordinateService;

    @Autowired private TimeConverter timeConverter;
    @Autowired private SatPosService satPosService;

    @Async
    public CompletableFuture<Void> asyncSatellite(
            Satellite satellite, List<Station> stations,
            AbsoluteDate start, AbsoluteDate end, double step,
            ConcurrentMap<String, List<ContactSchedule>> total) {

        TLEPropagator propagator = TLEPropagator.selectExtrapolator(satellite.getTle());

        for (Station station : stations) {
            TopocentricFrame stFrame = station.getStationFrame();

            Map<Integer, List<ContactSchedule>> part = calcContactForStation(propagator,
                    station, stFrame, start, end, step, satellite.OrbitNumber);
            /* merge */
            part.forEach((m, list) -> {
                String key = satellite.getSatelliteName() + '_' + station.getStationName() + '_' + m;
                total.computeIfAbsent(key,
                        k -> Collections.synchronizedList(new ArrayList<>(list.size())))
                        .addAll(list);
            });
        }
        return CompletableFuture.completedFuture(null);
    }

    private Map<Integer, List<ContactSchedule>> calcContactForStation(
            TLEPropagator prop, Station st, TopocentricFrame stFrame,
            AbsoluteDate start, AbsoluteDate end, double step, Integer orbitNumber) {

        Set<Integer> masks = new HashSet<>(st.angle);
        Map<Integer, List<ContactSchedule>> out = new HashMap<>();
        masks.forEach(m -> out.put(m, new ArrayList<>(64))); // 초기 용량 예측

        Map<Integer, ContactSchedule> cur = new HashMap<>();
        Map<Integer, Double> maxEl = new HashMap<>();

        for (AbsoluteDate t = start; t.compareTo(end) <= 0; t = t.shiftedBy(step)) {

            final AbsoluteDate ts = t; // ❗ 람다용 복사본

            double el = topocentricService.getElevation(
                    coordinateService.toECEF(
                            t, satPosService.getSatECI(prop, t)).getPosition(),
                    stFrame, t);

            for (int m : masks) {
                if (el > m) { // 가시
                    cur.computeIfAbsent(m, k -> {
                        ContactSchedule dto = new ContactSchedule(
                                calculateInitialOrbitNumber(prop.getTLE(), ts, orbitNumber),
                                timeConverter.toUtcAbbrMSec(ts), null, el, (double) 0);
                        maxEl.put(k, el);
                        return dto;
                    });
                    if (el > maxEl.get(m)) {
                        maxEl.put(m, el);
                        cur.get(m).setMaxElevation(el);
                    }
                } else if (cur.containsKey(m)) { // 가시 종료
                    ContactSchedule dto = cur.remove(m);
                    dto.setLos(timeConverter.toUtcAbbrMSec(t));
                    dto.setDuration((int) t.durationFrom(timeConverter.fromCompactUtcString(dto.getAos())));
                    out.get(m).add(dto);
                    maxEl.remove(m);
                }
            }
        }
        /* 열린 pass flush */
        cur.forEach((m, dto) -> {
            dto.setLos(timeConverter.toUtcAbbrMSec(end)); // 끝 시점으로 마감
            dto.setDuration((int) end.durationFrom(timeConverter.fromCompactUtcString(dto.getAos())));
            out.get(m).add(dto);
        });

        return out;
    }

    private long calculateInitialOrbitNumber(TLE tle, AbsoluteDate startDate, Integer orbitNum) {
        AbsoluteDate epochDate = tle.getDate();

        double meanMotion = tle.getMeanMotion();

        double meanMotionRevsPerDay = meanMotion * 86400 / (2 * Math.PI);

        double elapsedTimeInSeconds = startDate.durationFrom(epochDate);

        double revolutionsSinceEpoch = meanMotionRevsPerDay * (elapsedTimeInSeconds / 86400.0);

        return (long) (orbitNum + Math.floor(revolutionsSinceEpoch));
    }

}
