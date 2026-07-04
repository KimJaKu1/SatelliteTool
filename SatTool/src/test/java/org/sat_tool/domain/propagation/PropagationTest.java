package org.sat_tool.domain.propagation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.orekit.frames.FramesFactory;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.utils.IERSConventions;
import org.sat_tool.SatToolApplication;
import org.sat_tool.domain.coordinate.model.EphemerisVector;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.sat_tool.domain.propagation.service.EphemerisService;
import org.sat_tool.domain.propagation.service.PropagatorService;
import org.sat_tool.domain.propagation.writer.OemEphemerisWriter;
import org.sat_tool.domain.propagation.writer.TabularEphemerisWriter;
import org.sat_tool.orekit.TimeConverter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = SatToolApplication.class)
class PropagationTest {

    @Autowired private EphemerisService ephemerisService;
    @Autowired private PropagatorService propagatorService;
    @Autowired private OemEphemerisWriter oemEphemerisWriter;
    @Autowired private TabularEphemerisWriter tabularEphemerisWriter;

    String line1 = "1 40536U 15014A   25306.53588389  .00019651  00000-0  46362-3 0  9990";
    String line2 = "2 40536  97.6739 278.7368 0001750 352.4261   7.6958 15.41930762586752";

    LocalDateTime startTime = LocalDateTime.of(2025, 11, 4, 6, 9, 44);
    LocalDateTime endTime = LocalDateTime.of(2025, 11, 5, 0, 0, 0);

    private static final double STEP_SECONDS = 60.0;

    /** [start, end]를 STEP 간격으로 샘플링했을 때 기대되는 샘플 수 (양 끝 포함) */
    private long expectedSampleCount() {
        long spanSeconds = java.time.Duration.between(startTime, endTime).getSeconds();
        return spanSeconds / (long) STEP_SECONDS + 1;
    }

    @Test
    void eciEphemerisAndOemFile(@TempDir Path dir) throws IOException {
        Path oemPath = dir.resolve("orekit_eci.oem");

        var startAbsoluteDate = TimeConverter.localDateTimeUtcToAbsoluteDate(startTime);
        var endAbsoluteDate = TimeConverter.localDateTimeUtcToAbsoluteDate(endTime);
        TLE tle = new TLE(line1, line2);
        var propagator = propagatorService.createPropagatorFromTle(tle);
        List<EphemerisVector> ephemerisECI =
                ephemerisService.computeEphemerisECI(propagator, startAbsoluteDate, endAbsoluteDate, STEP_SECONDS);

        assertEquals(expectedSampleCount(), ephemerisECI.size(), "sample count mismatch");
        assertEquals(startTime, ephemerisECI.get(0).getTime(), "first sample time");
        // LEO 위성 지심 거리 sanity check (지구 반경 ~6378 km + 고도 수백 km)
        double r0 = ephemerisECI.get(0).getPos().getNorm();
        assertTrue(r0 > 6.5e6 && r0 < 8.0e6, "geocentric radius out of LEO range: " + r0);

        oemEphemerisWriter.writeOemFile(
                ephemerisECI,
                FramesFactory.getGCRF(),
                oemPath,
                String.valueOf(tle.getSatelliteNumber()),
                "SAT-" + tle.getSatelliteNumber());

        assertTrue(Files.exists(oemPath), "OEM file not created");
        String content = Files.readString(oemPath);
        assertTrue(content.contains("CCSDS_OEM_VERS"), "missing OEM header");
        assertTrue(content.contains(String.valueOf(tle.getSatelliteNumber())), "missing object id");
    }

    @Test
    void ecefEphemerisAndTabularFile(@TempDir Path dir) throws IOException {
        Path txtPath = dir.resolve("orekit_ecef.txt");
        Path oemPath = dir.resolve("orekit_ecef.oem");

        var startAbsoluteDate = TimeConverter.localDateTimeUtcToAbsoluteDate(startTime);
        var endAbsoluteDate = TimeConverter.localDateTimeUtcToAbsoluteDate(endTime);
        TLE tle = new TLE(line1, line2);
        var propagator = propagatorService.createPropagatorFromTle(tle);
        List<EphemerisVector> ephemerisECEF =
                ephemerisService.computeEphemerisECEF(propagator, startAbsoluteDate, endAbsoluteDate, STEP_SECONDS);

        assertEquals(expectedSampleCount(), ephemerisECEF.size(), "sample count mismatch");

        tabularEphemerisWriter.writeTabularFile(ephemerisECEF, txtPath);
        oemEphemerisWriter.writeOemFile(
                ephemerisECEF,
                FramesFactory.getITRF(IERSConventions.IERS_2010, true),
                oemPath,
                String.valueOf(tle.getSatelliteNumber()),
                "SAT-" + tle.getSatelliteNumber());

        assertTrue(Files.exists(txtPath), "tabular file not created");
        List<String> lines = Files.readAllLines(txtPath);
        assertEquals(ephemerisECEF.size(), lines.size(), "tabular line count mismatch");
        assertEquals(7, lines.get(0).split("\t").length, "tabular column count (time + pos3 + vel3)");
        assertTrue(Files.exists(oemPath), "OEM file not created");
    }
}
