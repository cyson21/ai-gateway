package com.example.gateway.batch;

import java.util.List;
import java.util.Optional;

public interface BatchJobStore {

    BatchJob save(BatchJob job);

    Optional<BatchJob> find(String id);

    List<BatchJob> all();
}
