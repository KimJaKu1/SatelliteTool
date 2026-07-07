package org.sat_tool.domain.capture.writer;

import org.orekit.time.AbsoluteDate;
import org.sat_tool.domain.capture.model.ImagingOpportunity;
import org.sat_tool.orekit.TimeConverter;
import org.sat_tool.domain.coordinate.model.LLA;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
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
import java.util.StringJoiner;
import java.util.concurrent.locks.ReentrantLock;

import org.sat_tool.domain.common.helper.PathLocks;

@Service
public class CaptureReportWriter {


    public void writeCaptureFile(List<ImagingOpportunity> opportunities, String satName, Path outputDir) {
        String normalizedSatName = normalizeSatName(satName);
        Path file = outputDir.resolve(normalizedSatName + "_Capture.txt");
        try {
            Files.createDirectories(outputDir);

            ReentrantLock lock = PathLocks.forPath(file);
            lock.lock();
            try {
                if (Files.exists(file)) {
                    Files.delete(file);
                }

                try (BufferedWriter writer = Files.newBufferedWriter(
                        file,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE)) {

                    writer.write(String.format("%141s%n",
                            TimeConverter.UTC_DT_HDR_ABBR.format(ZonedDateTime.now(ZoneOffset.UTC))));
                    writer.write("Satellite-" + normalizedSatName + ":  Roll-Only Capture Opportunity Schedule");
                    writer.newLine();
                    writer.newLine();
                    writer.newLine();

                    writer.write("Capture #      Start Time (UTCG)           Capture Time (UTCG)         Stop Time (UTCG)            Roll (deg)    Center Err (deg)");
                    writer.newLine();
                    writer.write("---------      ------------------------    ------------------------    ------------------------    ----------    ----------------");
                    writer.newLine();

                    List<ImagingOpportunity> sorted = new ArrayList<>();
                    if (opportunities != null) {
                        sorted.addAll(opportunities);
                    }
                    sorted.sort(Comparator.comparing(
                            ImagingOpportunity::captureUtc,
                            Comparator.nullsLast(AbsoluteDate::compareTo)
                    ));

                    for (int i = 0; i < sorted.size(); i++) {
                        ImagingOpportunity opportunity = sorted.get(i);
                        writer.write(String.format(Locale.US,
                                "%-9d      %-24s    %-24s    %-24s    %10.3f    %16.6f%n",
                                i + 1,
                                formatTime(opportunity.startUtc()),
                                formatTime(opportunity.captureUtc()),
                                formatTime(opportunity.endUtc()),
                                opportunity.usedRollDegAtCapture(),
                                opportunity.boresightErrorDegAtCapture()));
                        writer.write(String.format("%-9s      %s%n",
                                "Footprint",
                                formatFootprint(opportunity.footprintAtCapture())));
                    }

                    writer.flush();
                }
            } finally {
                lock.unlock();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write capture report: " + file, e);
        }
    }

    private String formatTime(AbsoluteDate date) {
        return (date == null) ? "                     N/A" : TimeConverter.toUtcAbbrMSec(date);
    }

    private String formatFootprint(List<LLA> footprint) {
        if (footprint == null || footprint.isEmpty()) {
            return "No footprint at capture time";
        }

        StringJoiner joiner = new StringJoiner(" | ");
        for (int i = 0; i < footprint.size(); i++) {
            LLA point = footprint.get(i);
            joiner.add(String.format(Locale.US,
                    "P%d (%.6f, %.6f)",
                    i + 1,
                    point.getLatitude(),
                    point.getLongitude()));
        }
        return joiner.toString();
    }

    private String normalizeSatName(String satName) {
        if (satName == null || satName.isBlank()) {
            return "Capture";
        }
        return satName.trim();
    }
}
