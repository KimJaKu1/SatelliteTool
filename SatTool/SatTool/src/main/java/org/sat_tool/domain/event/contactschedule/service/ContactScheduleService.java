package org.sat_tool.domain.event.contactschedule.service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.*;
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

    private final ContactScheduleWoker csAsyncWorker;
    private final TimeConverter timeConverter;

    public ContactScheduleService(ContactScheduleWoker csAsyncWorker, TimeConverter timeConverter) {
        this.csAsyncWorker = csAsyncWorker;
        this.timeConverter = timeConverter;
    }

    // 파일 단위 락
    private final ConcurrentMap<Path, ReentrantLock> fileLock = new ConcurrentHashMap<>();

    // ✅ sat/stn에 '_'가 있어도 안전한 구분자
    private static final String KEY_SEP = "|";

    public CompletableFuture<Map<String, List<ContactSchedule>>> generateContactSchedule(
            Satellite satellite, List<Station> stations, List<EphemerisVector> ephemerisVector) {

        ConcurrentMap<String, List<ContactSchedule>> total = new ConcurrentHashMap<>();

        CompletableFuture<?>[] tasks = stations.stream()
                .map(st -> csAsyncWorker.asyncComputeCsByStation(satellite, st, ephemerisVector, total))
                .toArray(CompletableFuture[]::new);

        return CompletableFuture.allOf(tasks)
                .thenApply(v -> new HashMap<>(total));
    }

    @Async
    public CompletableFuture<Void> generateCSFile(Set<Map.Entry<String, List<ContactSchedule>>> entries, Path dir) {
        try {
            for (Map.Entry<String, List<ContactSchedule>> e : entries) {
                KeyParts kp = parseKey(e.getKey());
                if (kp == null) continue;

                List<ContactSchedule> passes = e.getValue();
                passes.sort(Comparator.comparing(ContactSchedule::getAos,
                        Comparator.nullsLast(Comparator.naturalOrder())));

                writeCS(passes, kp.sat(), kp.stn(), kp.mask(), dir);
            }
            return CompletableFuture.completedFuture(null);
        } catch (Exception ex) {
            CompletableFuture<Void> f = new CompletableFuture<>();
            f.completeExceptionally(ex);
            return f;
        }
    }

    private record KeyParts(String sat, String stn, int mask) {}

    private KeyParts parseKey(String key) {
        if (key == null) return null;
        // key = sat|stn|mask
        int first = key.indexOf(KEY_SEP);
        int last  = key.lastIndexOf(KEY_SEP);
        if (first < 0 || last <= first) return null;

        String sat = key.substring(0, first);
        String stn = key.substring(first + 1, last);
        String maskStr = key.substring(last + 1);

        try {
            int mask = Integer.parseInt(maskStr);
            return new KeyParts(sat, stn, mask);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private void writeCS(List<ContactSchedule> passes, String sat, String stn, int mask, Path dir) throws IOException {
        Files.createDirectories(dir);

        Path file = dir.resolve(sat + "_" + stn + "_EL_" + mask + ".txt");

        ReentrantLock lock = fileLock.computeIfAbsent(file, k -> new ReentrantLock());
        lock.lock();
        try {
            // ✅ 삭제/생성/쓰기 모두 락 내부에서
            if (Files.exists(file)) Files.delete(file);

            try (BufferedWriter w = Files.newBufferedWriter(
                    file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE)) {

                // 헤더(항상 새 파일이므로 항상 출력)
                w.write(String.format("%57s%n",
                        TimeConverter.UTC_DT_HDR_ABBR.format(ZonedDateTime.now(ZoneOffset.UTC))));
                w.write("Facility-" + stn + "_EL_" + mask +
                        "_Deg-To-Satellite-" + sat +
                        ":  Raw Contact Schedule for CSG");
                w.newLine(); w.newLine(); w.newLine();

                w.write("To Pass        Start Time (UTCG)           Stop Time (UTCG)        Duration (sec)    Max Elevation (deg)");
                w.newLine();
                w.write("-------    ------------------------    ------------------------    --------------    -------------------");
                w.newLine();

                for (ContactSchedule pass : passes) {
                    String aosStr = (pass.getAos() == null)
                            ? "             Not in Pass"
                            : timeConverter.toUtcAbbrMSec(pass.getAos());

                    String losStr = (pass.getLos() == null)
                            ? "             Not in Pass"
                            : timeConverter.toUtcAbbrMSec(pass.getLos());

                    double durationSec = pass.getDuration();       // getDuration()이 double/float라고 가정(기존 포맷과 동일)
                    double maxElDeg    = pass.getMaxElevation();

                    w.write(String.format(Locale.US,
                            "%-7d      %-24s    %-24s    %14.3f    %21.3f%n",
                            pass.getOrbitnumber(),
                            aosStr,
                            losStr,
                            durationSec,
                            maxElDeg
                    ));
                }

                w.flush();
            }
        } finally {
            // ✅ lock 해제 보장
            lock.unlock();
        }
    }
}
