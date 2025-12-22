package org.sat_tool.domain.event.worker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;

import org.orekit.frames.FramesFactory;
import org.orekit.frames.TopocentricFrame;
import org.orekit.propagation.analytical.tle.TLEPropagator;
import org.orekit.time.AbsoluteDate;
import org.orekit.utils.PVCoordinates;
import org.sat_tool.domain.common.converter.TimeConverter;
import org.sat_tool.domain.common.model.Satellite;
import org.sat_tool.domain.common.model.Station;
import org.sat_tool.domain.coordinate.service.CoordinateService;
import org.sat_tool.domain.coordinate.service.TopocentricService;
import org.sat_tool.domain.event.model.AntennaTracking;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@DependsOn("orekitInitializer")
public class AntennaTrackingWoker {

    @Autowired
    private CoordinateService coordinateService;
    @Autowired
    private TopocentricService topocentricService;

    @Autowired
    private TimeConverter timeConverter;

    @Async
    public CompletableFuture<Void> asyncComputeAtByStation(
            Satellite satellite, Station station,
            AbsoluteDate start, AbsoluteDate end, double dt,
            ConcurrentMap<String, List<List<AntennaTracking>>> total) {

        TLEPropagator prop = TLEPropagator.selectExtrapolator(satellite.getTle());

        Map<String, List<List<AntennaTracking>>> part = computeAntennaTracking(satellite.getSatelliteName(), prop,
                station, start, end, dt);

        part.forEach((k, v) -> total.computeIfAbsent(k, kk -> Collections.synchronizedList(new ArrayList<>()))
                .addAll(v));

        return CompletableFuture.completedFuture(null);
    }

    private Map<String, List<List<AntennaTracking>>> computeAntennaTracking(
            String satName, TLEPropagator prop,
            Station station, AbsoluteDate start, AbsoluteDate end, double dt) {

        TopocentricFrame frame = station.getStationFrame();

        int[] masks = station.getAngles().stream()
                .sorted()
                .mapToInt(Integer::intValue)
                .toArray();
        int mCnt = masks.length;

        boolean[] inPass = new boolean[mCnt];
        List<AntennaTracking>[] buf = Stream.generate(ArrayList<AntennaTracking>::new)
                .limit(mCnt).toArray(List[]::new);

        Map<String, List<List<AntennaTracking>>> out = new HashMap<>();

        for (AbsoluteDate t = start; t.compareTo(end) <= 0; t = t.shiftedBy(dt)) {
            PVCoordinates ecef = coordinateService.toECEF(
                    t, prop.getPVCoordinates(t, FramesFactory.getGCRF()));

            double az = topocentricService.getAzimuth(ecef.getPosition(), frame, t);
            double el = topocentricService.getElevation(ecef.getPosition(), frame, t);

            for (int i = 0; i < mCnt; i++) {
                int m = masks[i];
                if (el > m) { // pass 내부
                    inPass[i] = true;
                    buf[i].add(new AntennaTracking(timeConverter.toUtcAbbrSec(t), (float) az, (float) el));
                } else if (inPass[i]) { // pass 종료
                    inPass[i] = false;
                    String key = satName + '_' + station.getStationName() + "_EL_" + m;
                    out.computeIfAbsent(key, k -> new ArrayList<>()).add(buf[i]);
                    buf[i] = new ArrayList<>();
                }
            }
        }
        // 열린 pass flush
        for (int i = 0; i < mCnt; i++) {
            if (inPass[i] && !buf[i].isEmpty()) {
                String key = satName + '_' + station.getStationName() + "_EL_" + masks[i];
                out.computeIfAbsent(key, k -> new ArrayList<>()).add(buf[i]);
            }
        }
        return out;
    }
}
