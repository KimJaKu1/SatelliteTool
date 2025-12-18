package org.sat_tool.domain.event.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.orekit.time.AbsoluteDate;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

@Service
@DependsOn("orekitInitializer")
public class AntennaTrackingService {
    public List<List<AntennaTracking>> computeAntennaTracking(
           AbsoluteDate startDate, AbsoluteDate endDate, double intervalSeconds)
    {

        List<List<AntennaTracking>> totalAt = new ArrayList<>();

        CompletableFuture<?>[] tasks = requestEvents.stream()
                .map(ev -> atAsyncWorker.AsyncATData(ev,  startDate, endDate, intervalSeconds, total)) // 위성-단위 @Async
                .toArray(CompletableFuture[]::new);

        return CompletableFuture.allOf(tasks)
                .thenApply(v -> {                      // ❷ 모든 위성이 끝난 뒤 후처리
                    total.values().forEach(passLists ->
                            passLists.forEach(list ->
                                    list.sort(Comparator.comparing(AntennaTrackingDTO::getTime))));
                    return new HashMap<>(total);       // 호출 쪽에서 수정 못하게 복사
                });
    }

}
