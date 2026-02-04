package org.example.sattool;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.propagation.analytical.tle.TLEPropagator;
import org.sat_tool.SatToolApplication;
import org.sat_tool.domain.common.converter.TimeConverter;
import org.sat_tool.domain.common.model.Satellite;
import org.sat_tool.domain.common.model.Station;
import org.sat_tool.domain.coordinate.model.EphemerisVector;
import org.sat_tool.domain.event.antennatracking.service.AntennaTrackingService;
import org.sat_tool.domain.event.service.ContactScheduleService;
import org.sat_tool.domain.event.nodalcrossing.service.NodalCrossingService;
import org.sat_tool.domain.propagation.service.PropagateService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(classes = SatToolApplication.class)
public class GenerateNCEvent {

    @Autowired
    private PropagateService propagateService;

    @Autowired
    private AntennaTrackingService atService;
    @Autowired
    private NodalCrossingService ncService;
    @Autowired
    private ContactScheduleService csService;
//    @Autowired
//    private EclipseService eclipseService;

    @Autowired
    private TimeConverter timeConverter;


    String line1 = "1 40536U 15014A   25306.53588389  .00019651  00000-0  46362-3 0  9990";
    String line2 = "2 40536  97.6739 278.7368 0001750 352.4261   7.6958 15.41930762586752";

    LocalDateTime startTime = LocalDateTime.of(2025, 11, 3, 6, 9, 44);
    LocalDateTime endTime = LocalDateTime.of(2025, 11, 4, 0, 0, 0);

    String path = "src/out";

    @Test
    void generateATFile() {

        Satellite sat = new Satellite();
        sat.setSatelliteName("TestSat");
        TLE tle = new TLE(line1, line2);
        sat.setOrbitNumber((long) tle.getRevolutionNumberAtEpoch());
        TLEPropagator propagator = TLEPropagator.selectExtrapolator(tle);

        List<EphemerisVector> ephemerisVectors = propagateService.computeEphemerisECEF(propagator,timeConverter.localDateTimeUtcToAbsoluteDate(startTime),
                timeConverter.localDateTimeUtcToAbsoluteDate(endTime),
                60.0);

        List<Station> stations = new ArrayList<>();
        stations.add(new Station("TestStn", 36.8663, 127.1530, 100.0, List.of(0)));

        atService.generateAntennaTracking(sat, stations, ephemerisVectors)
                .thenCompose(map -> atService.generateATFile(map.entrySet(), Path.of(path))).join();
    }

    @Test
    void generateNCFile() {

        Satellite sat = new Satellite();
        sat.setSatelliteName("TestSat");
        TLE tle = new TLE(line1, line2);
        sat.setOrbitNumber((long) tle.getRevolutionNumberAtEpoch());
        TLEPropagator propagator = TLEPropagator.selectExtrapolator(tle);

        List<EphemerisVector> ephemerisVectors = propagateService.computeEphemerisECEF(propagator,timeConverter.localDateTimeUtcToAbsoluteDate(startTime),
                timeConverter.localDateTimeUtcToAbsoluteDate(endTime),
                60.0);

        var nc = ncService.computeNodalCrossingsFromEcef_NoPropagator(sat, ephemerisVectors, timeConverter.localDateTimeUtcToAbsoluteDate(startTime),
                timeConverter.localDateTimeUtcToAbsoluteDate(endTime), 1,1e-3, 50
                );
        ncService.generateNCFile(
                nc, sat.getSatelliteName(), Path.of(path));

//        var temp  = 1;
    }

    @Test
    void generateCSFile() {
        Satellite sat = new Satellite();
        sat.setSatelliteName("TestSat");
        TLE tle = new TLE(line1, line2);
        sat.setOrbitNumber((long) tle.getRevolutionNumberAtEpoch());
        TLEPropagator propagator = TLEPropagator.selectExtrapolator(tle);

        List<Station> stations = new ArrayList<>();
        stations.add(new Station("TestStn", 36.8663, 127.1530, 100.0, List.of(0)));

        List<EphemerisVector> ephemerisVectors = propagateService.computeEphemerisECEF(propagator,timeConverter.localDateTimeUtcToAbsoluteDate(startTime),
                timeConverter.localDateTimeUtcToAbsoluteDate(endTime),
                60.0);

        csService.generateContactSchedule(
                sat,
                stations,
                ephemerisVectors).thenCompose(map -> csService.generateCSFile(map.entrySet(), Path.of(path))).join();
    }


//    @Test
//    void eclipseFile() {
//        Satellite sat = new Satellite();
//        sat.setSatelliteName("TestSat");
//        TLE tle = new TLE(line1, line2);
//        sat.setOrbitNumber((long) tle.getRevolutionNumberAtEpoch());
//        TLEPropagator propagator = TLEPropagator.selectExtrapolator(tle);
//
//        var tmep = eclipseService.computeEclipses(
//                sat,
//                timeConverter.localDateTimeUtcToAbsoluteDate(startTime),
//                timeConverter.localDateTimeUtcToAbsoluteDate(endTime),
//                60.0);
//
//        eclipseService.generateNCFile(tmep, sat.getSatelliteName(), Path.of(path));
//    }
}
