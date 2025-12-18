package org.example.sattool;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.junit.jupiter.api.Test;
import org.orekit.propagation.analytical.tle.TLE;
import org.sat_tool.SatToolApplication;
import org.sat_tool.domain.common.converter.TimeConverter;
import org.sat_tool.domain.event.model.NodalCrossing;
import org.sat_tool.domain.event.service.NodalCrossingService;
import org.sat_tool.domain.propagation.service.PropagatorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(classes = SatToolApplication.class)
public class EventCreate {

    @Autowired private PropagatorService propagatorService;
    @Autowired private NodalCrossingService ncService;

    @Autowired
    private TimeConverter timeConverter;

    String line1 = "1 40536U 15014A   25306.53588389  .00019651  00000-0  46362-3 0  9990";
    String line2 = "2 40536  97.6739 278.7368 0001750 352.4261   7.6958 15.41930762586752";

    String path = "src/out";

    LocalDateTime startTime = LocalDateTime.of(2025, 11, 3, 6, 9, 44);
    LocalDateTime endTime = LocalDateTime.of(2025, 11, 4, 0, 0, 0);

    @Test
    void OutNCFile() {
        TLE tle = new TLE(line1, line2);

        var startAbsoluteDate = timeConverter.localDateTimeUtcToAbsoluteDate(startTime);
        var endAbsoluteDate = timeConverter.localDateTimeUtcToAbsoluteDate(endTime);
        var propagator = propagatorService.sgp4Propagator(line1, line2);
        var nc = ncService.computeNodalCrossing(
                startAbsoluteDate, endAbsoluteDate, 60.0,
                propagator, tle.getRevolutionNumberAtEpoch(), true);
        ncService.writeNodalCrossingFile(
                nc, tle.getSatelliteNumber(), Path.of(path + "/"));
    }

}
