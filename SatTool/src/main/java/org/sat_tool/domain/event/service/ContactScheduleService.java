package org.sat_tool.domain.event.service;

import java.sql.Time;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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
import org.sat_tool.domain.event.worker.ContactScheduleWoker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@DependsOn("orekitInitializer")
public class ContactScheduleService {

    @Autowired
    private ContactScheduleWoker csAsyncWorker;

    public CompletableFuture<Map<String, List<ContactSchedule>>> generateContactSchedule(
            Satellite satellite, List<Station> stations,
            AbsoluteDate startDate, AbsoluteDate endDate, double intervalSeconds) {

        ConcurrentMap<String, List<ContactSchedule>> total = new ConcurrentHashMap<>();
        // ① 위성 단위 비동기 작업
        CompletableFuture<?>[] tasks = stations.stream()
                .map(station -> csAsyncWorker.asyncComputeCsByStation(satellite, station, startDate, endDate, intervalSeconds, total)) // asyncSatellite 는
                                                                                         // CompletableFuture<Void> 반환
                .toArray(CompletableFuture[]::new);

        return CompletableFuture.allOf(tasks)
                .thenApply(v -> new HashMap<>(total));
    }
}
