package com.example.gateway.batch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class InMemoryBatchJobStore implements BatchJobStore {

    private final Map<String, BatchJob> jobs = new LinkedHashMap<>();

    @Override
    public synchronized BatchJob save(BatchJob job) {
        jobs.put(job.id(), job);
        return job;
    }

    @Override
    public synchronized Optional<BatchJob> find(String id) {
        return Optional.ofNullable(jobs.get(id));
    }

    @Override
    public synchronized List<BatchJob> all() {
        return List.copyOf(new ArrayList<>(jobs.values()));
    }
}
