package org.sat_tool.domain.contact.service;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.sat_tool.domain.common.model.Satellite;
import org.sat_tool.domain.common.model.Station;
import org.sat_tool.domain.contact.model.ContactSchedule;
import org.sat_tool.domain.contact.worker.ContactScheduleWorker;
import org.sat_tool.domain.contact.writer.ContactScheduleReportWriter;
import org.sat_tool.domain.coordinate.model.EphemerisVector;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@DependsOn("orekitInitializer")
public class ContactScheduleService {

    private final ContactScheduleWorker worker;
    private final ContactScheduleReportWriter reportWriter;

    public ContactScheduleService(ContactScheduleWorker worker, ContactScheduleReportWriter reportWriter) {
        this.worker = worker;
        this.reportWriter = reportWriter;
    }

    /**
     * @param orbitPeriodSeconds 궤도 주기(초) — 각 pass의 궤도 번호 계산에 사용
     *                           (TLE 기준 2π/meanMotion). 0 이하이면 대략적 LEO 주기로 폴백.
     */
    public CompletableFuture<Map<String, List<ContactSchedule>>> generateContactSchedule(
            Satellite satellite,
            List<Station> stations,
            List<EphemerisVector> ephemerisVector,
            double orbitPeriodSeconds) {

        ConcurrentMap<String, List<ContactSchedule>> total = new ConcurrentHashMap<>();
        CompletableFuture<?>[] tasks = stations.stream()
                .map(station -> worker.asyncComputeCsByStation(satellite, station, ephemerisVector, total, orbitPeriodSeconds))
                .toArray(CompletableFuture[]::new);

        return CompletableFuture.allOf(tasks)
                .thenApply(v -> new HashMap<>(total));
    }

    @Async
    public CompletableFuture<Void> generateCSFile(Set<Map.Entry<String, List<ContactSchedule>>> entries, Path dir) {
        try {
            reportWriter.writeFiles(entries, dir);
            return CompletableFuture.completedFuture(null);
        } catch (Exception ex) {
            log.error("Contact Schedule 파일 생성 실패 (dir={}, entries={})", dir, entries.size(), ex);
            CompletableFuture<Void> failed = new CompletableFuture<>();
            failed.completeExceptionally(ex);
            return failed;
        }
    }
}
