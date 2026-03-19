package org.sat_tool.domain.event.antennatracking.service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

import org.sat_tool.domain.common.model.Satellite;
import org.sat_tool.domain.common.model.Station;
import org.sat_tool.domain.coordinate.model.EphemerisVector;
import org.sat_tool.domain.event.antennatracking.model.AntennaTracking;
import org.sat_tool.domain.event.antennatracking.worker.AntennaTrackingWoker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@DependsOn("orekitInitializer")
@Service
public class AntennaTrackingService {

    private final AntennaTrackingWoker atWorker;

    public AntennaTrackingService(AntennaTrackingWoker atWorker) {
        this.atWorker = atWorker;
    }

    private static final ConcurrentMap<Path, ReentrantLock> FILE_LOCK = new ConcurrentHashMap<>();

    private static final DateTimeFormatter HDR_FMT =
            DateTimeFormatter.ofPattern("dd MMM uuuu HH:mm:ss", Locale.US);

    public CompletableFuture<Map<String, List<List<AntennaTracking>>>> generateAntennaTracking(
            Satellite satellite, List<Station> stations, List<EphemerisVector> ecefVectors) {

        ConcurrentMap<String, List<List<AntennaTracking>>> totalAt = new ConcurrentHashMap<>();

        CompletableFuture<?>[] tasks = stations.stream()
                .map(st -> atWorker.asyncComputeAtByStation(
                        satellite.getSatelliteName(), st, ecefVectors, totalAt))
                .toArray(CompletableFuture[]::new);

        return CompletableFuture.allOf(tasks)
                .thenApply(v -> {
                    totalAt.values().forEach(passLists ->
                            passLists.forEach(list ->
                                    list.sort(Comparator.comparing(AntennaTracking::getTime))));
                    return Map.copyOf(totalAt);
                });
    }

    /**
     * ✅ 중요:
     * - @Async라서 호출 측은 반드시 join()/get()으로 대기해야 "파일 생성이 보장"됨.
     * - 내부에서 예외가 발생하면 Future를 exceptional로 종료시켜 호출 측에서 바로 알 수 있게 함.
     */
    @Async
    public CompletableFuture<Void> generateATFile(
            Set<Map.Entry<String, List<List<AntennaTracking>>>> entries,
            Path baseDir) {
        try {
            for (Map.Entry<String, List<List<AntennaTracking>>> e : entries) {
                KeyParts kp = parseKey(e.getKey());
                if (kp == null) continue;

                writeAT(e.getValue(), kp.sat(), kp.stn(), kp.mask(), baseDir);
            }
            return CompletableFuture.completedFuture(null);
        } catch (Exception ex) {
            CompletableFuture<Void> f = new CompletableFuture<>();
            f.completeExceptionally(ex);
            return f;
        }
    }

    private record KeyParts(String sat, String stn, int mask) {}

    private KeyParts parseKey(String rawKey) {
        if (rawKey == null) return null;

        int firstIdx = rawKey.indexOf('_');
        int lastIdx = rawKey.lastIndexOf('_');

        if (firstIdx < 0 || lastIdx <= firstIdx) return null;

        String sat = rawKey.substring(0, firstIdx);
        String stn = rawKey.substring(firstIdx + 1, lastIdx);
        String maskStr = rawKey.substring(lastIdx + 1);

        try {
            int mask = Integer.parseInt(maskStr);
            return new KeyParts(sat, stn, mask);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    void writeAT(List<List<AntennaTracking>> passes,
                 String sat, String stn, int mask,
                 Path baseDir) throws IOException {

        Files.createDirectories(baseDir);

        Path file = baseDir.resolve(sat + "_" + stn + "_" + mask + ".txt");

        ReentrantLock lock = FILE_LOCK.computeIfAbsent(file, k -> new ReentrantLock());
        lock.lock();
        try {
            if (Files.exists(file)) {
                Files.delete(file);
            }

            try (BufferedWriter w = Files.newBufferedWriter(
                    file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE)) {

                w.write(String.format("%57s%n", HDR_FMT.format(ZonedDateTime.now(ZoneOffset.UTC))));
                w.write("Facility-" + stn + "_EL_" + mask +
                        "_Deg-To-Satellite-" + sat +
                        ":  Antenna Tracking Table for CSG");
                w.newLine();
                w.newLine();
                w.newLine();

                for (List<AntennaTracking> pass : passes) {
                    w.write(String.format("%-24s    %-13s    %-15s%n",
                            "Time (UTCG)", "Azimuth (deg)", "Elevation (deg)"));
                    w.write("------------------------    -------------    ---------------");
                    w.newLine();

                    for (AntennaTracking dto : pass) {
                        w.write(String.format(Locale.US,
                                "%-24s    %13.3f    %15.3f%n",
                                dto.getTime(),
                                dto.getAzimuth(),
                                dto.getElevation()));
                    }
                    w.newLine();
                }

                w.flush();
            }
        } finally {
            lock.unlock();
        }
    }
}
