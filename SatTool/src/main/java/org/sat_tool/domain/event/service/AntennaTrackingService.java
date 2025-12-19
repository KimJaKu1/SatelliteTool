package org.sat_tool.domain.event.service;

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
import org.orekit.time.AbsoluteDate;
import org.sat_tool.domain.common.model.Satellite;
import org.sat_tool.domain.common.model.Station;
import org.sat_tool.domain.event.model.AntennaTracking;
import org.sat_tool.domain.event.worker.AntennaTrackingWoker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@DependsOn("orekitInitializer")
public class AntennaTrackingService {

    @Autowired
    private AntennaTrackingWoker atWorker;

    private static final ConcurrentMap<Path, ReentrantLock> fileLock = new ConcurrentHashMap<>();

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS");
    private static final DateTimeFormatter HDR_FMT  = DateTimeFormatter.ofPattern("dd MMM uuuu HH:mm:ss", Locale.US);


    public CompletableFuture<Map<String, List<List<AntennaTracking>>>> generateAntennaTracking(
            Satellite satellite, List<Station> stations,
            AbsoluteDate startDate, AbsoluteDate endDate, double intervalSeconds) {

        ConcurrentMap<String, List<List<AntennaTracking>>> totalAt = new ConcurrentHashMap<>();

        CompletableFuture<?>[] tasks = stations.stream()
                .map(st -> atWorker.asyncComputeAtByStation(satellite, st, startDate, endDate, intervalSeconds, totalAt))
                .toArray(CompletableFuture[]::new);

        return CompletableFuture.allOf(tasks)
                .thenApply(v -> {
                    totalAt.values().forEach(passLists -> passLists
                            .forEach(list -> list.sort(Comparator.comparing(AntennaTracking::getTime))));
                    return Map.copyOf(totalAt);
                });
    }

     @Async
    public CompletableFuture<Void> generateATFile(Set<Map.Entry<String, List<List<AntennaTracking>>>> entries, Path path) {
        for (Map.Entry<String, List<List<AntennaTracking>>> e : entries)
        {
            String rawKey = e.getKey();                   // 예) KOMPSAT2_KGS_5
            int lastIdx = rawKey.lastIndexOf('_');
            if (lastIdx < 0) continue;                    // 형식 오류 skip

            String sat  = rawKey.substring(0, rawKey.indexOf('_'));
            String stn  = rawKey.substring(sat.length() + 1, lastIdx);
            int    mask = Integer.parseInt(rawKey.substring(lastIdx + 1));

            writeAT(e.getValue(), sat, stn, mask, path);

        }
        return CompletableFuture.completedFuture(null);
    }

    private void writeAT(List<List<AntennaTracking>> passes,
                         String sat, String stn, int mask, Path baseDir) {
        try {
            Files.createDirectories(baseDir);

            Path file = baseDir.resolve(sat + '_' + stn + "_" + mask + ".txt");

            if (Files.exists(file)) {          // 이전 결과가 있으면
                Files.delete(file);            // 먼저 삭제
            }

            ReentrantLock lock = fileLock.computeIfAbsent(file, k -> new ReentrantLock());
            lock.lock();

            try (BufferedWriter w = Files.newBufferedWriter(
                    file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,          // 없으면 만들고
                    StandardOpenOption.APPEND,
                    StandardOpenOption.WRITE))
            {
                boolean newFile = Files.size(file) == 0;

                /* 헤더 (파일 최초) */
                if (newFile) {
                    w.write(String.format("%57s%n", HDR_FMT.format(ZonedDateTime.now(ZoneOffset.UTC))));
                    w.write("Facility-" + stn + "_EL_" + mask +
                            "_Deg-To-Satellite-" + sat +
                            ":  Antenna Tracking Table for CSG");
                    w.newLine(); w.newLine(); w.newLine();
                }

                for (List<AntennaTracking>  pass: passes )
                {
                    w.write(String.format("%-24s    %-13s    %-15s%n",
                            "Time (UTCG)", "Azimuth (deg)", "Elevation (deg)"));
                    w.write("------------------------    -------------    ---------------");
                    w.newLine();

                    /* 데이터 행 */
                    for (AntennaTracking dto : pass) {
                        w.write(String.format(Locale.US,
                                "%-24s    %13.3f    %15.3f%n",
                                dto.getTime(),
                                dto.getAzimuth(),
                                dto.getElevation()));
                    }
                    w.newLine();   // Pass 간 공백
                }
                /* Pass 구분용 열 제목 */

                w.flush();
            } finally {
                lock.unlock();
            }

        } catch (IOException ex) {
            // 필요하면 로깅
            ex.printStackTrace();
        }
    }
}
