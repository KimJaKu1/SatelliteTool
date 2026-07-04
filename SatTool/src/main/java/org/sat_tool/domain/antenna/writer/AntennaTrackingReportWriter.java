package org.sat_tool.domain.antenna.writer;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import org.sat_tool.domain.common.helper.PathLocks;

import org.sat_tool.orekit.TimeConverter;
import org.sat_tool.domain.antenna.model.AntennaTracking;
import org.sat_tool.domain.common.model.ReportKey;
import org.springframework.stereotype.Service;

@Service
public class AntennaTrackingReportWriter {


    public void writeFiles(Set<Map.Entry<String, List<List<AntennaTracking>>>> entries, Path baseDir) throws IOException {
        for (Map.Entry<String, List<List<AntennaTracking>>> entry : entries) {
            ReportKey key = ReportKey.parse(entry.getKey());
            if (key == null) {
                continue;
            }

            writeFile(entry.getValue(), key.sat(), key.station(), key.mask(), baseDir);
        }
    }

    private void writeFile(List<List<AntennaTracking>> passes,
                           String satelliteName,
                           String stationName,
                           int mask,
                           Path baseDir) throws IOException {
        Files.createDirectories(baseDir);

        Path file = baseDir.resolve(satelliteName + "_" + stationName + "_" + mask + ".txt");
        ReentrantLock lock = PathLocks.forPath(file);
        lock.lock();
        try {
            Files.deleteIfExists(file);

            try (BufferedWriter writer = Files.newBufferedWriter(
                    file,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE)) {
                writer.write(String.format("%57s%n",
                        TimeConverter.UTC_DT_HDR_ABBR.format(ZonedDateTime.now(ZoneOffset.UTC))));
                writer.write("Facility-" + stationName + "_EL_" + mask
                        + "_Deg-To-Satellite-" + satelliteName
                        + ":  Antenna Tracking Table for CSG");
                writer.newLine();
                writer.newLine();
                writer.newLine();

                for (List<AntennaTracking> pass : passes) {
                    writer.write(String.format("%-24s    %-13s    %-15s%n",
                            "Time (UTCG)", "Azimuth (deg)", "Elevation (deg)"));
                    writer.write("------------------------    -------------    ---------------");
                    writer.newLine();

                    for (AntennaTracking tracking : pass) {
                        writer.write(String.format(Locale.US,
                                "%-24s    %13.3f    %15.3f%n",
                                tracking.getTime(),
                                tracking.getAzimuth(),
                                tracking.getElevation()));
                    }
                    writer.newLine();
                }

                writer.flush();
            }
        } finally {
            lock.unlock();
        }
    }
}
