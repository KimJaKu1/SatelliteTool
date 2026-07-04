package org.sat_tool.domain.eclipse.writer;

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

import org.sat_tool.orekit.TimeConverter;
import org.sat_tool.domain.eclipse.model.EclipseReportRow;
import org.sat_tool.domain.eclipse.model.FieldValue;
import org.sat_tool.domain.eclipse.type.FieldState;
import org.springframework.stereotype.Service;

@Service
public class EclipseReportWriter {


    public EclipseReportWriter() {
    }

    public void writeReport(List<EclipseReportRow> rows, String satelliteName, Path directory) {
        Path file = directory.resolve(satelliteName + "_Eclipse.txt");
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

                    writer.write("Pass Number    Penumbra Entry (UTCG)        Umbra Entry (UTCG)            Umbra Exit (UTCG)           Penumbra Exit (UTCG)");
                    writer.newLine();
                    writer.write("-----------    -------------------------    --------------------------    ------------------------    ------------------------");
                    writer.newLine();

                    for (EclipseReportRow row : rows) {
                        writer.write(String.format(Locale.US, "%11d    %-25s    %-26s    %-24s    %-24s%n",
                                row.getOrbitNumber(),
                                formatField(row.getPenEntry()),
                                formatField(row.getUmbEntry()),
                                formatField(row.getUmbExit()),
                                formatField(row.getPenExit())));
                    }
                }
            } finally {
                lock.unlock();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write eclipse report: " + file, e);
        }
    }

    private String formatField(FieldValue field) {
        if (field == null) {
            return "             Not in Pass";
        }
        if (field.getState() == FieldState.TIME && field.getTime() != null) {
            return TimeConverter.toUtcAbbrMSec(field.getTime());
        }
        if (field.getState() == FieldState.NO_UMBRA) {
            return "             No Umbra";
        }
        return "             Not in Pass";
    }
}
