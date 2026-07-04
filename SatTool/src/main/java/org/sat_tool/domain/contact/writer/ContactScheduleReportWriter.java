package org.sat_tool.domain.contact.writer;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import org.sat_tool.domain.common.helper.PathLocks;

import org.sat_tool.orekit.TimeConverter;
import org.sat_tool.domain.common.model.ReportKey;
import org.sat_tool.domain.contact.model.ContactSchedule;
import org.springframework.stereotype.Service;

@Service
public class ContactScheduleReportWriter {


    public ContactScheduleReportWriter() {
    }

    public void writeFiles(Set<Map.Entry<String, List<ContactSchedule>>> entries, Path directory) throws IOException {
        for (Map.Entry<String, List<ContactSchedule>> entry : entries) {
            ReportKey key = ReportKey.parse(entry.getKey());
            if (key == null) {
                continue;
            }

            // 호출자 리스트를 변경하지 않도록 복사본을 정렬
            // (AOS/LOS가 모두 윈도우 내에서 관측된 완전한 pass만 존재 — null 없음)
            List<ContactSchedule> passes = new ArrayList<>(entry.getValue());
            passes.sort(Comparator.comparing(ContactSchedule::getAos));
            writeFile(passes, key.sat(), key.station(), key.mask(), directory);
        }
    }

    private void writeFile(List<ContactSchedule> passes,
                           String satelliteName,
                           String stationName,
                           int mask,
                           Path directory) throws IOException {
        Files.createDirectories(directory);

        Path file = directory.resolve(satelliteName + "_" + stationName + "_EL_" + mask + ".txt");
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
                        + ":  Raw Contact Schedule for CSG");
                writer.newLine();
                writer.newLine();
                writer.newLine();

                writer.write("To Pass        Start Time (UTCG)           Stop Time (UTCG)        Duration (sec)    Max Elevation (deg)");
                writer.newLine();
                writer.write("-------    ------------------------    ------------------------    --------------    -------------------");
                writer.newLine();

                for (ContactSchedule pass : passes) {
                    writer.write(String.format(Locale.US,
                            "%-7d      %-24s    %-24s    %14.3f    %21.3f%n",
                            pass.getOrbitNumber(),
                            TimeConverter.toUtcAbbrMSec(pass.getAos()),
                            TimeConverter.toUtcAbbrMSec(pass.getLos()),
                            pass.getDuration(),
                            pass.getMaxElevation()));
                }

                writer.flush();
            }
        } finally {
            lock.unlock();
        }
    }
}
