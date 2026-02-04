package org.sat_tool.domain.event.contactschedule.service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

import org.sat_tool.domain.common.converter.TimeConverter;
import org.sat_tool.domain.common.model.Satellite;
import org.sat_tool.domain.common.model.Station;
import org.sat_tool.domain.coordinate.model.EphemerisVector;
import org.sat_tool.domain.event.contactschedule.model.ContactSchedule;
import org.sat_tool.domain.event.contactschedule.worker.ContactScheduleWoker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@DependsOn("orekitInitializer")
public class ContactScheduleService {

    @Autowired
    private ContactScheduleWoker csAsyncWorker;

    private final ConcurrentMap<Path, ReentrantLock> fileLock = new ConcurrentHashMap<>();

    public CompletableFuture<Map<String, List<ContactSchedule>>> generateContactSchedule(
            Satellite satellite, List<Station> stations,
            List<EphemerisVector> ephemerisVector) {

        ConcurrentMap<String, List<ContactSchedule>> total = new ConcurrentHashMap<>();
        // ① 위성 단위 비동기 작업
        CompletableFuture<?>[] tasks = stations.stream()
                .map(station -> csAsyncWorker.asyncComputeCsByStation(satellite, station, ephemerisVector, total)) // asyncSatellite 는
                // CompletableFuture<Void> 반환
                .toArray(CompletableFuture[]::new);

        return CompletableFuture.allOf(tasks)
                .thenApply(v -> new HashMap<>(total));
    }

    public CompletableFuture<Map<String, List<ContactSchedule>>> generateContactScheduleTemp(
            Satellite satellite, List<Station> stations, List<EphemerisVector> ephemerisEcef) {

        ConcurrentMap<String, List<ContactSchedule>> total = new ConcurrentHashMap<>();
        // ① 위성 단위 비동기 작업
        CompletableFuture<?>[] tasks = stations.stream()
                .map(station -> csAsyncWorker.asyncComputeCsByStation(satellite, station, ephemerisEcef, total)) // asyncSatellite 는
                // CompletableFuture<Void> 반환
                .toArray(CompletableFuture[]::new);

        return CompletableFuture.allOf(tasks)
                .thenApply(v -> new HashMap<>(total));
    }

    @Async
    public CompletableFuture<Void> generateCSFile(Set<Map.Entry<String, List<ContactSchedule>>> entries, Path path) {

        for (Map.Entry<String, List<ContactSchedule>> e : entries) {
            String rawKey = e.getKey(); // 예) KOMPSAT2_KGS_5
            // stationNama 명에 '_' 가 들어올 수도 있으므로 마지막 '_' 를 mask 구분자로 사용
            int lastIdx = rawKey.lastIndexOf('_');
            if (lastIdx < 0)
                continue; // 형식 오류 skip

            String sat = rawKey.substring(0, rawKey.indexOf('_'));
            String stn = rawKey.substring(sat.length() + 1, lastIdx);
            int mask = Integer.parseInt(rawKey.substring(lastIdx + 1));

            writeCS(e.getValue(), sat, stn, mask, path);
        }
        return CompletableFuture.completedFuture(null);
    }

    private void writeCS(List<ContactSchedule> passes, String sat, String stn, int mask, Path path) {
        try {
            Files.createDirectories(path);
            Path file = path.resolve(sat + '_' + stn + "_EL_" + mask + ".txt");
            if (Files.exists(file)) { // 이전 결과가 있으면
                Files.delete(file); // 먼저 삭제
            }
            ReentrantLock lock = fileLock.computeIfAbsent(file, k -> new ReentrantLock());
            lock.lock();
            try (BufferedWriter w = Files.newBufferedWriter(
                    file,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                    StandardOpenOption.WRITE)) {
                boolean newFile = Files.size(file) == 0; // 최초 생성 여부

                if (newFile) {
                    w.write(String.format("%57s%n",
                            TimeConverter.UTC_DT_HDR_ABBR.format(ZonedDateTime.now(ZoneOffset.UTC))));
                    w.write("Facility-" + stn + mask +
                            "_Deg-To-Satellite-" + sat +
                            ":  Raw Contact Schedule for CSG");
                    w.newLine();
                    w.newLine();
                    w.newLine();
                    w.write("To Pass        Start Time (UTCG)           Stop Time (UTCG)        Duration (sec)    Max Elevation (deg)");
                    w.newLine();
                    w.write("-------    ------------------------    ------------------------    --------------    -------------------");
                    w.newLine();
                }
                for (ContactSchedule pass : passes) {
                    w.write(String.format(Locale.US,
                            "%-7d      %-24s    %-24s    %14.3f    %21.3f%n",
                            pass.getOrbitnumber(), // To Pass (또는 getPassId)
                            pass.getAos(), // Start Time
                            pass.getLos(), // Stop Time
                            pass.getDuration(), // Duration(sec)
                            pass.getMaxElevation())); // Max Elevation(deg)
                    w.flush();
                }
            }

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
