package org.sat_tool.domain.antenna.service;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.sat_tool.domain.antenna.model.AntennaTracking;
import org.sat_tool.domain.antenna.worker.AntennaTrackingWorker;
import org.sat_tool.domain.antenna.writer.AntennaTrackingReportWriter;
import org.sat_tool.domain.common.model.Satellite;
import org.sat_tool.domain.common.model.Station;
import org.sat_tool.domain.coordinate.model.EphemerisVector;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@DependsOn("orekitInitializer")
@Service
public class AntennaTrackingService {

    private final AntennaTrackingWorker worker;
    private final AntennaTrackingReportWriter reportWriter;

    public AntennaTrackingService(AntennaTrackingWorker worker, AntennaTrackingReportWriter reportWriter) {
        this.worker = worker;
        this.reportWriter = reportWriter;
    }

    public CompletableFuture<Map<String, List<List<AntennaTracking>>>> generateAntennaTracking(
            Satellite satellite,
            List<Station> stations,
            List<EphemerisVector> ecefVectors) {

        ConcurrentMap<String, List<List<AntennaTracking>>> total = new ConcurrentHashMap<>();
        CompletableFuture<?>[] tasks = stations.stream()
                .map(station -> worker.asyncComputeAtByStation(
                        satellite.getSatelliteName(), station, ecefVectors, total))
                .toArray(CompletableFuture[]::new);

        // 키별 리스트는 단일 워커 스레드가 시간순으로 append하므로 별도 정렬 불필요
        return CompletableFuture.allOf(tasks)
                .thenApply(v -> Map.copyOf(total));
    }

    @Async
    public CompletableFuture<Void> generateATFile(
            Set<Map.Entry<String, List<List<AntennaTracking>>>> entries,
            Path baseDir) {
        try {
            reportWriter.writeFiles(entries, baseDir);
            return CompletableFuture.completedFuture(null);
        } catch (Exception ex) {
            log.error("Antenna Tracking 파일 생성 실패 (dir={}, entries={})", baseDir, entries.size(), ex);
            CompletableFuture<Void> failed = new CompletableFuture<>();
            failed.completeExceptionally(ex);
            return failed;
        }
    }
}
