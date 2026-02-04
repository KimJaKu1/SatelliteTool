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

    @Autowired
    private AntennaTrackingWoker atWorker;

    // 파일 단위 락
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

    @Async
    public CompletableFuture<Void> generateATFile(
            Set<Map.Entry<String, List<List<AntennaTracking>>>> entries,
            Path baseDir) {

        for (Map.Entry<String, List<List<AntennaTracking>>> e : entries) {
            KeyParts kp = parseKey(e.getKey());
            if (kp == null) continue;

            writeAT(e.getValue(), kp.sat(), kp.stn(), kp.mask(), baseDir);
        }
        return CompletableFuture.completedFuture(null);
    }

    private record KeyParts(String sat, String stn, int mask) {}

    /**
     * key 형식: sat_stn_mask
     * - sat = 첫 '_' 전
     * - mask = 마지막 '_' 뒤
     * - stn = 그 사이(underscore 포함 가능)
     */
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
                 Path baseDir) {

        try {
            Files.createDirectories(baseDir);

            Path file = baseDir.resolve(sat + "_" + stn + "_" + mask + ".txt");

            // 파일 단위 락 (삭제/생성/쓰기까지 한 번에 보호)
            ReentrantLock lock = FILE_LOCK.computeIfAbsent(file, k -> new ReentrantLock());
            lock.lock();
            try {
                // 매번 새로 생성(기존 있으면 삭제)
                if (Files.exists(file)) {
                    Files.delete(file);
                }

                try (BufferedWriter w = Files.newBufferedWriter(
                        file, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE)) {

                    // 헤더
                    w.write(String.format("%57s%n", HDR_FMT.format(ZonedDateTime.now(ZoneOffset.UTC))));
                    w.write("Facility-" + stn + "_EL_" + mask +
                            "_Deg-To-Satellite-" + sat +
                            ":  Antenna Tracking Table for CSG");
                    w.newLine();
                    w.newLine();
                    w.newLine();

                    // Pass 별 데이터
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
                        w.newLine(); // Pass 간 공백
                    }

                    w.flush();
                }
            } finally {
                lock.unlock();
                // 필요하면 메모리 누수 방지용으로 cleanup 가능(선택)
                // FILE_LOCK.remove(file, lock);
            }

        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }
}
