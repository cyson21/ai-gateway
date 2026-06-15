package com.example.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Foundation context-load test: proves the Spring application context wires up.
 *
 * <p>Flyway is disabled here because it connects to PostgreSQL at startup; real schema
 * migration is verified against a live database in the Testcontainers slice (Task 4).
 * R2DBC and Redis connection factories are created but connect lazily, so they do not
 * require running infrastructure for this test.
 */
@SpringBootTest(properties = "spring.flyway.enabled=false")
class GatewayApplicationTest {

    @Test
    void contextLoads() {
    }
}
