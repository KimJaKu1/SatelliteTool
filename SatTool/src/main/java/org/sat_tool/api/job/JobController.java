package org.sat_tool.api.job;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/jobs")
@RequiredArgsConstructor
public class JobController {

    private final JobManager jobManager;

    @GetMapping("/{jobId}")
    public ResponseEntity<JobManager.JobView> get(@PathVariable String jobId) {
        JobManager.JobView job = jobManager.find(jobId);
        return (job == null) ? ResponseEntity.notFound().build() : ResponseEntity.ok(job);
    }
}
