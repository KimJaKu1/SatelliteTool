package org.sat_tool.domain.nodalcrossing.writer;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;

import org.sat_tool.domain.common.helper.PathLocks;

import org.orekit.time.AbsoluteDate;
import org.sat_tool.orekit.TimeConverter;
import org.sat_tool.domain.nodalcrossing.model.NodalCrossing;
import org.springframework.stereotype.Service;

@Service
public class NodalCrossingReportWriter {


    public NodalCrossingReportWriter() {
    }

    public void writeReport(List<NodalCrossing> passes, String satelliteName, Path directory) {
        Path file = directory.resolve(satelliteName + "_Nodal_Crossing.dat");
        try {
            Files.createDirectories(directory);

            ReentrantLock lock = PathLocks.forPath(file);
            lock.lock();
            try {
                Files.deleteIfExists(file);

                try (BufferedWriter writer = Files.newBufferedWriter(
                        file,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE)) {
                    writer.write(String.format("%101s%n",
                            TimeConverter.UTC_DT_HDR_ABBR.format(ZonedDateTime.now(ZoneOffset.UTC))));
                    writer.write("Satellite-" + satelliteName);
                    writer.newLine();
                    writer.newLine();
                    writer.newLine();

                    writer.write("Pass Number    Time of Ascen Node (UTCG)    Time of Descen Node (UTCG)     Time of Min Lat (UTCG)      Time of Max Lat (UTCG) ");
                    writer.newLine();
                    writer.write("-----------    -------------------------    --------------------------    ------------------------    ------------------------");
                    writer.newLine();

                    for (NodalCrossing pass : passes) {
                        writer.write(String.format(Locale.US, "%11d    %-25s    %-26s    %-24s    %-24s%n",
                                pass.getOrbitNumber(),
                                formatTime(pass.getAscendingNodeTime()),
                                formatTime(pass.getDescendingNodeTime()),
                                formatTime(pass.getMinLatTime()),
                                formatTime(pass.getMaxLatTime())));
                    }
                }
            } finally {
                lock.unlock();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write nodal crossing report: " + file, e);
        }
    }

    private String formatTime(AbsoluteDate date) {
        return date == null ? "             Not in Pass" : TimeConverter.toUtcAbbrMSec(date);
    }
}
