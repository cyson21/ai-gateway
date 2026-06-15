package com.example.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Unit tests for {@link ApiKeyAuthFilter} driven by an in-memory {@link ApiKeyRepository}, so the
 * authorization decision is exercised without a live database. The Testcontainers slice (Task 4)
 * covers the real migration and SQL lookup.
 */
class ApiKeyAuthFilterTest {

    private static final String VALID_KEY = "sk-live-valid";

    private final Map<String, ApiKeyRecord> byHash = new HashMap<>();
    private final ApiKeyRepository repository = keyHash -> {
        ApiKeyRecord record = byHash.get(keyHash);
        return record == null ? Mono.empty() : Mono.just(record);
    };
    private final ApiKeyAuthFilter filter = new ApiKeyAuthFilter(repository);

    private void seed(String rawKey, ApiKeyRecord record) {
        byHash.put(ApiKeyHasher.sha256Hex(rawKey), record);
    }

    private MockServerWebExchange exchangeWithKey(String rawKey) {
        return MockServerWebExchange.from(MockServerHttpRequest
                .post("/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + rawKey));
    }

    @Test
    void activeKeyResolvesTenantAndPassesThrough() {
        seed(VALID_KEY, new ApiKeyRecord("tenant-1", "ACTIVE", "ACTIVE"));
        MockServerWebExchange exchange = exchangeWithKey(VALID_KEY);
        RecordingChain chain = new RecordingChain();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chain.invoked).isTrue();
        assertThat(exchange.<String>getAttribute(ApiKeyAuthFilter.TENANT_ID_ATTRIBUTE)).isEqualTo("tenant-1");
        assertThat(chain.contextTenant).isEqualTo("tenant-1");
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    void unknownKeyIsRejected() {
        seed(VALID_KEY, new ApiKeyRecord("tenant-1", "ACTIVE", "ACTIVE"));
        MockServerWebExchange exchange = exchangeWithKey("sk-live-typo");
        RecordingChain chain = new RecordingChain();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chain.invoked).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void inactiveKeyIsRejected() {
        seed(VALID_KEY, new ApiKeyRecord("tenant-1", "REVOKED", "ACTIVE"));
        MockServerWebExchange exchange = exchangeWithKey(VALID_KEY);
        RecordingChain chain = new RecordingChain();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chain.invoked).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void suspendedTenantIsRejected() {
        seed(VALID_KEY, new ApiKeyRecord("tenant-1", "ACTIVE", "SUSPENDED"));
        MockServerWebExchange exchange = exchangeWithKey(VALID_KEY);
        RecordingChain chain = new RecordingChain();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chain.invoked).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void missingAuthorizationHeaderIsRejected() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/v1/chat/completions"));
        RecordingChain chain = new RecordingChain();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chain.invoked).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void actuatorPathIsExemptFromAuth() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/actuator/health"));
        RecordingChain chain = new RecordingChain();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chain.invoked).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    /** Captures whether the downstream chain ran and what tenant id reached the Reactor context. */
    private static final class RecordingChain implements WebFilterChain {
        boolean invoked;
        String contextTenant;

        @Override
        public Mono<Void> filter(ServerWebExchange exchange) {
            invoked = true;
            return Mono.deferContextual(ctx -> {
                contextTenant = ctx.getOrDefault(ApiKeyAuthFilter.TENANT_ID_CONTEXT_KEY, null);
                return Mono.empty();
            });
        }
    }
}
