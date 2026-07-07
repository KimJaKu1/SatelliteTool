package org.sat_tool.api.job;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 장시간 계산 요청을 작업 ID로 추적하는 인메모리 작업 저장소.
 * 완료/실패한 작업은 RETENTION 경과 후 새 작업 제출 시점에 정리된다.
 */
@Slf4j
@Component
public class JobManager {

    private static final Duration RETENTION = Duration.ofHours(1);

    public record JobView(String jobId, JobStatus status, Object result, String error,
                          Instant submittedAt, Instant finishedAt) {
    }

    private final ConcurrentMap<String, JobView> jobs = new ConcurrentHashMap<>();
    private final ThreadPoolTaskExecutor executor;

    public JobManager(@Qualifier("satToolTaskExecutor") ThreadPoolTaskExecutor executor) {
        this.executor = executor;
    }

    /** 작업을 제출하고 즉시 jobId를 반환한다. work는 공용 실행기에서 비동기로 수행된다. */
    public String submit(Supplier<?> work) {
        purgeExpired();

        String jobId = UUID.randomUUID().toString();
        jobs.put(jobId, new JobView(jobId, JobStatus.RUNNING, null, null, Instant.now(), null));

        CompletableFuture.supplyAsync(work, executor).whenComplete((result, ex) -> {
            if (ex != null) {
                Throwable cause = (ex.getCause() != null) ? ex.getCause() : ex;
                log.error("job {} failed", jobId, cause);
                jobs.computeIfPresent(jobId, (id, prev) -> new JobView(
                        id, JobStatus.FAILED, null, cause.getMessage(), prev.submittedAt(), Instant.now()));
            } else {
                jobs.computeIfPresent(jobId, (id, prev) -> new JobView(
                        id, JobStatus.COMPLETED, result, null, prev.submittedAt(), Instant.now()));
            }
        });

        return jobId;
    }

    /** @return 작업 상태, 없으면 null (만료·미존재) */
    public JobView find(String jobId) {
        return jobs.get(jobId);
    }

    private void purgeExpired() {
        Instant cutoff = Instant.now().minus(RETENTION);
        jobs.values().removeIf(job ->
                job.finishedAt() != null && job.finishedAt().isBefore(cutoff));
    }
}
