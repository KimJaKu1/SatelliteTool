package org.example.sattool;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.junit.jupiter.api.Test;
import org.sat_tool.SatToolApplication;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.sat_tool.domain.propagation.service.PropagateService;
import org.sat_tool.domain.propagation.service.PropagatorService;
import org.sat_tool.domain.common.converter.TimeConverter;

import lombok.experimental.var;

import java.nio.file.Path;
import java.nio.file.Paths;


@SpringBootTest(classes = SatToolApplication.class)
class Propagetion {

    @Autowired private PropagateService propagateService;
    @Autowired private PropagatorService propagatorService;

    @Autowired private TimeConverter timeConverter;

    String line1 = "1 40536U 15014A   25306.53588389  .00019651  00000-0  46362-3 0  9990";
    String line2 = "2 40536  97.6739 278.7368 0001750 352.4261   7.6958 15.41930762586752";

    String path = "src/out";

    LocalDateTime startTime = LocalDateTime.of(2025, 11, 3, 6, 9, 44);
    LocalDateTime endTime = LocalDateTime.of(2025, 11, 4, 0, 0, 0);

    @Test
    void ECIOutFile() {
        Path parnet = Paths.get(path+"/"+startTime.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))+"_orekit_eci.txt");

        var startAbsoluteDate = timeConverter.localDateTimeUtcToAbsoluteDate(startTime);
        var endAbsoluteDate = timeConverter.localDateTimeUtcToAbsoluteDate(endTime);
        var propagator = propagatorService.sgp4Propagator(line1, line2);
        var ephemerisECI = propagateService.computeEphemerisECI(propagator, startAbsoluteDate, endAbsoluteDate, 60.0);

        propagateService.writeFile(ephemerisECI, parnet);
    }
    
    @Test
    void ECEFOutFile()
    {
        Path parnet = Paths.get(path+"/"+startTime.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))+"_orekit_ecef.txt");

        var startAbsoluteDate = timeConverter.localDateTimeUtcToAbsoluteDate(startTime);
        var endAbsoluteDate = timeConverter.localDateTimeUtcToAbsoluteDate(endTime);
        var propagator = propagatorService.sgp4Propagator(line1, line2);
        var ephemerisECEF = propagateService.computeEphemerisECEF(propagator, startAbsoluteDate, endAbsoluteDate, 60.0);

        propagateService.writeFile(ephemerisECEF, parnet);
    }
    
}
