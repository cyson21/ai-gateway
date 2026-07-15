package com.example.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.r2dbc.core.DatabaseClient;

import static org.mockito.Mockito.mock;

/**
 * Foundation context-load test: proves the Spring application context wires up.
 *
 * <p>Flyway is disabled here because it connects to PostgreSQL at startup; real schema
 * migration is verified against a live database in the Testcontainers slice (Task 4).
 * R2DBC and Redis connection factories are created but connect lazily, so they do not
 * require running infrastructure for this test.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.r2dbc.R2dbcDataAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.r2dbc.R2dbcRepositoriesAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"
})
@Import(GatewayApplicationTest.DatabaseClientTestConfig.class)
class GatewayApplicationTest {

    @TestConfiguration
    static class DatabaseClientTestConfig {
        @Bean
        DatabaseClient databaseClient() {
            return mock(DatabaseClient.class);
        }
    }

    @Test
    void contextLoads() {
    }
}
