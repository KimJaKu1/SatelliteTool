package org.sat_tool.integration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.propagation.analytical.tle.TLEPropagator;
import org.sat_tool.SatToolApplication;
import org.sat_tool.domain.antenna.service.AntennaTrackingService;
import org.sat_tool.domain.common.model.Satellite;
import org.sat_tool.domain.common.model.Station;
import org.sat_tool.domain.contact.service.ContactScheduleService;
import org.sat_tool.domain.coordinate.model.EphemerisVector;
import org.sat_tool.domain.eclipse.service.EclipseService;
import org.sat_tool.domain.nodalcrossing.service.NodalCrossingService;
import org.sat_tool.domain.propagation.service.EphemerisService;
import org.sat_tool.orekit.TimeConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = SatToolApplication.class)
public class EventReportGenerationTest {

    @Autowired private EphemerisService ephemerisService;
    @Autowired private AntennaTrackingService atService;
    @Autowired private NodalCrossingService ncService;
    @Autowired private ContactScheduleService csService;
    @Autowired private EclipseService eclipseService;

    String line1 = "1 40536U 15014A   25306.53588389  .00019651  00000-0  46362-3 0  9990";
    String line2 = "2 40536  97.6739 278.7368 0001750 352.4261   7.6958 15.41930762586752";

    LocalDateTime startTime = LocalDateTime.of(2025, 11, 3, 6, 9, 44);
    LocalDateTime endTime = LocalDateTime.of(2025, 11, 4, 0, 0, 0);

    private Satellite newSatellite(TLE tle) {
        Satellite sat = new Satellite();
        sat.setSatelliteName("TestSat_20260204");
        sat.setOrbitNumFromTle(tle, TimeConverter.localDateTimeUtcToAbsoluteDate(startTime));
        return sat;
    }

    private List<EphemerisVector> ecefEphemeris(TLE tle, double stepSeconds) {
        TLEPropagator propagator = TLEPropagator.selectExtrapolator(tle);
        return ephemerisService.computeEphemerisECEF(propagator,
                TimeConverter.localDateTimeUtcToAbsoluteDate(startTime),
                TimeConverter.localDateTimeUtcToAbsoluteDate(endTime),
                stepSeconds);
    }

    private static void assertDirHasFiles(Path dir) throws IOException {
        try (var files = Files.list(dir)) {
            assertTrue(files.findAny().isPresent(), "no report file generated in " + dir);
        }
    }

    @Test
    void generateATFile(@TempDir Path dir) throws IOException {
        TLE tle = new TLE(line1, line2);
        Satellite sat = newSatellite(tle);
        List<Station> stations = new ArrayList<>();
        stations.add(new Station("TestStn", 36.8663, 127.1530, 100.0, List.of(0)));

        var map = atService.generateAntennaTracking(sat, stations, ecefEphemeris(tle, 60)).join();

        // 약 17.8시간 윈도우 — 대전 인근 지상국이면 최소 1회 이상 패스가 있어야 한다
        assertFalse(map.isEmpty(), "no antenna tracking key generated");
        assertTrue(map.values().stream().anyMatch(lists -> !lists.isEmpty()),
                "no tracking samples generated");

        atService.generateATFile(map.entrySet(), dir).join();
        assertDirHasFiles(dir);
    }

    @Test
    void generateNCFile(@TempDir Path dir) {
        TLE tle = new TLE(line1, line2);
        Satellite sat = newSatellite(tle);

        var nc = ncService.computeNodalCrossingsFromEcef_NoPropagator(
                sat, ecefEphemeris(tle, 1),
                TimeConverter.localDateTimeUtcToAbsoluteDate(startTime),
                TimeConverter.localDateTimeUtcToAbsoluteDate(endTime),
                1, 1e-3, 50);

        // 주기 약 97분, 윈도우 약 17.8시간 → 승/강교점 통과가 다수 존재해야 한다
        assertFalse(nc.isEmpty(), "no nodal crossings detected");

        ncService.generateNCFile(nc, sat.getSatelliteName(), dir);
        assertTrue(Files.exists(dir.resolve(sat.getSatelliteName() + "_Nodal_Crossing.dat")),
                "nodal crossing report file not created");
    }

    @Test
    void generateCSFile(@TempDir Path dir) throws IOException {
        TLE tle = new TLE(line1, line2);
        Satellite sat = newSatellite(tle);
        List<Station> stations = new ArrayList<>();
        stations.add(new Station("TestStn", 36.8663, 127.1530, 100.0, List.of(0)));

        var map = csService.generateContactSchedule(sat, stations, ecefEphemeris(tle, 60),
                2.0 * Math.PI / tle.getMeanMotion()).join();

        assertFalse(map.isEmpty(), "no contact schedule key generated");
        assertTrue(map.values().stream().anyMatch(list -> !list.isEmpty()),
                "no contact passes detected over ~17.8h window");

        // 궤도 번호는 pass 일련번호가 아니라 AOS 시각의 실제 궤도 번호 —
        // 매 궤도마다 교신이 생기지는 않으므로 연속 pass 사이에 번호 간격이 있어야 한다
        for (var passes : map.values()) {
            for (int i = 1; i < passes.size(); i++) {
                long gap = passes.get(i).getOrbitNumber() - passes.get(i - 1).getOrbitNumber();
                double hoursBetween = passes.get(i).getAos().durationFrom(passes.get(i - 1).getAos()) / 3600.0;
                assertTrue(gap >= 1, "orbit number must not decrease");
                if (hoursBetween > 2.0) {
                    assertTrue(gap > 1, "passes " + hoursBetween + "h apart must skip orbit numbers (period ~1.6h)");
                }
            }
        }

        csService.generateCSFile(map.entrySet(), dir).join();
        assertDirHasFiles(dir);
    }

    @Test
    void eclipseFile(@TempDir Path dir) {
        TLE tle = new TLE(line1, line2);
        Satellite sat = newSatellite(tle);

        var rows = eclipseService.computeEclipseReportRowsFromEcef_NoPropagator(
                sat, ecefEphemeris(tle, 1),
                TimeConverter.localDateTimeUtcToAbsoluteDate(startTime),
                TimeConverter.localDateTimeUtcToAbsoluteDate(endTime),
                1, 1e-3, 50,
                2.0 * Math.PI / tle.getMeanMotion());

        // LEO는 사실상 매 궤도 식(eclipse)에 진입 — 빈 결과면 회귀
        assertFalse(rows.isEmpty(), "no eclipse rows detected");

        eclipseService.generateEclipseFileReport(rows, sat.getSatelliteName(), dir);
        assertTrue(Files.exists(dir.resolve(sat.getSatelliteName() + "_Eclipse.txt")),
                "eclipse report file not created");
    }
}
