package com.example.hello.model;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JobStatusTest {

    @Test
    void roundTripsDuplicateCountThroughRedisHash() {
        JobStatus status = new JobStatus();
        status.setJobId("job_1");
        status.setStatus(JobStatus.Status.COMPLETED);
        status.setTotalRecords(5);
        status.setProcessedCount(3);
        status.setFailedCount(1);
        status.setDuplicateCount(2);

        Map<String, String> hash = status.toHash();

        assertThat(hash).containsEntry("duplicate_count", "2");

        JobStatus restored = JobStatus.fromHash("job_1", new HashMap<Object, Object>(hash));
        assertThat(restored.getDuplicateCount()).isEqualTo(2);
        assertThat(restored.getProcessedCount()).isEqualTo(3);
        assertThat(restored.getFailedCount()).isEqualTo(1);
        assertThat(restored.getStatus()).isEqualTo(JobStatus.Status.COMPLETED);
    }

    @Test
    void missingDuplicateCountDefaultsToZero() {
        Map<Object, Object> hash = new HashMap<>();
        hash.put("status", "PENDING");
        JobStatus restored = JobStatus.fromHash("job_2", hash);
        assertThat(restored.getDuplicateCount()).isZero();
    }
}